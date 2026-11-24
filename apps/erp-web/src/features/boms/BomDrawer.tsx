import { useEffect, useState } from 'react';
import { Check, Copy, GitCompare, LoaderCircle, Pencil } from 'lucide-react';
import { useStateData, useWorkspace } from '../../app/workspace';
import { Badge, Overlay, Table } from '../../components';
import type { Row } from '../../lib/api';
import { fmt, money, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { ItemTypeBadge } from '../products/ItemTypeBadge';
import { useCostRollup, useExplosion, useWhereUsed } from './api';
import { ExplosionTree, TreeWarnings, WhereUsedTree } from './BomTree';
import './boms.css';

/**
 * BOM revision side drawer (V7): multi-level structure (indented explosion for any quantity), standard-cost roll-up
 * by level and the product's multi-level where-used. Actions: compare, copy to a new draft, edit or approve a draft.
 */
export function BomDrawer({
  bom,
  onClose,
  onCompare,
  onCopy,
  onEdit,
}: {
  bom: Row;
  onClose: () => void;
  onCompare: (bom: Row) => void;
  onCopy: (bom: Row) => void;
  onEdit: (bom: Row) => void;
}) {
  const { can, busy, mutate } = useWorkspace();
  const data = useStateData();
  const [tab, setTab] = useState('structure');
  const siblings = data.boms.filter((b) => b.product_id === bom.product_id && b.id !== bom.id);
  const planner = can('PLANNER');
  return (
    <Overlay
      title={`${text(bom.product)} · ${text(bom.revision)}`}
      subtitle="Multi-level structure, standard-cost roll-up and where-used"
      onClose={onClose}
      drawer
      wide
    >
      <div className="drawer-body">
        <div className="item-drawer-meta">
          <Badge value={bom.status} />
          <span>{text(bom.sku)}</span>
          <span>
            · {fmt(bom.output_qty, 6)} out · {fmt(num(bom.yield_factor) * 100, 2)}% yield
          </span>
        </div>
        <div className="drawer-tabs">
          <Tabs
            compact
            label="BOM revision sections"
            value={tab}
            onChange={setTab}
            items={[
              { value: 'structure', label: 'Structure' },
              { value: 'cost', label: 'Cost roll-up' },
              { value: 'used', label: 'Where used' },
            ]}
          />
        </div>
        {tab === 'structure' && <Structure bom={bom} />}
        {tab === 'cost' && <CostRollup bomId={bom.id} />}
        {tab === 'used' && <Used productId={text(bom.product_id)} sku={text(bom.sku)} />}
      </div>
      <div className="dialog-footer detail-footer">
        {siblings.length > 0 && (
          <button className="button secondary" onClick={() => onCompare(bom)}>
            <GitCompare size={15} />
            Compare revisions
          </button>
        )}
        <span className="grow" />
        {planner && bom.status === 'APPROVED' && (
          <button className="button secondary" disabled={busy} onClick={() => onCopy(bom)}>
            <Copy size={15} />
            Copy to new draft
          </button>
        )}
        {planner && bom.status === 'DRAFT' && (
          <>
            <button className="button secondary" disabled={busy} onClick={() => onEdit(bom)}>
              <Pencil size={15} />
              Edit draft
            </button>
            <button
              className="button primary"
              disabled={busy}
              onClick={() => mutate(`/boms/${bom.id}/approve`)}
            >
              {busy ? <LoaderCircle className="spin" size={15} /> : <Check size={15} />}
              Approve revision
            </button>
          </>
        )}
      </div>
    </Overlay>
  );
}

function Structure({ bom }: { bom: Row }) {
  const [input, setInput] = useState(text(Number(bom.output_qty)));
  const [quantity, setQuantity] = useState(input);
  useEffect(() => {
    const timeout = setTimeout(() => setQuantity(input), 350);
    return () => clearTimeout(timeout);
  }, [input]);
  const explosion = useExplosion(bom.id, quantity);
  const x = explosion.data;
  return (
    <div className="drawer-section">
      <div className="bom-tree-controls">
        <label className="field">
          <span>Explode for quantity</span>
          <input
            type="number"
            min="0.000001"
            step="any"
            value={input}
            aria-label="Explosion quantity"
            onChange={(e) => setInput(e.target.value)}
          />
        </label>
        {explosion.isFetching && <LoaderCircle className="spin" size={16} />}
      </div>
      {!(Number(quantity) > 0) ? (
        <div className="form-error">Enter a quantity greater than zero.</div>
      ) : explosion.isPending ? (
        <div className="mrp-state">
          <LoaderCircle className="spin" />
        </div>
      ) : explosion.isError ? (
        <div className="form-error" role="alert">
          {(explosion.error as Error).message}
        </div>
      ) : (
        x && (
          <>
            <TreeWarnings warnings={x.warnings} />
            <ExplosionTree root={x.root} />
            <div className="bom-summary">
              <div>
                <span>Material cost</span>
                <strong>{money(x.materialCost)}</strong>
                <small>
                  for {fmt(x.quantity, 6)} {text(x.bom.uom)}
                </small>
              </div>
              <div>
                <span>Per unit</span>
                <strong>{money(x.unitCost)}</strong>
                <small>bought-in materials, all levels</small>
              </div>
              <div>
                <span>Depth</span>
                <strong>
                  {x.depth} {x.depth === 1 ? 'level' : 'levels'}
                </strong>
                <small>{x.materials.length} bought-in materials</small>
              </div>
            </div>
            <h3 className="structure-label">Bought-in requirement (all levels)</h3>
            <div className="panel-inline">
              <Table
                compact
                pageSize={200}
                rows={x.materials.map((m) => ({ id: m.itemId, ...m }))}
                columns={[
                  {
                    label: 'Material',
                    render: (r) => (
                      <div className="cell-stack">
                        <strong>{text(r.name)}</strong>
                        <span>{text(r.sku)}</span>
                      </div>
                    ),
                  },
                  { label: 'Type', render: (r) => <ItemTypeBadge type={r.type} /> },
                  { label: 'Quantity', render: (r) => `${fmt(r.quantity, 6)} ${text(r.uom)}` },
                  { label: 'Standard cost', render: (r) => money(r.extendedCost) },
                ]}
              />
            </div>
          </>
        )
      )}
    </div>
  );
}

function CostRollup({ bomId }: { bomId: string }) {
  const cost = useCostRollup(bomId);
  if (cost.isPending)
    return (
      <div className="mrp-state">
        <LoaderCircle className="spin" />
      </div>
    );
  if (cost.isError)
    return (
      <div className="drawer-section">
        <div className="form-error" role="alert">
          {(cost.error as Error).message}
        </div>
      </div>
    );
  const c = cost.data;
  const max = Math.max(...c.levels.map((l) => num(l.cost)), 0.000001);
  return (
    <>
      <div className="bom-summary">
        <div>
          <span>Rolled-up cost / unit</span>
          <strong>{money(c.unitCost)}</strong>
          <small>standard costs through {c.depth} levels</small>
        </div>
        <div>
          <span>Single level / unit</span>
          <strong>{money(c.singleLevelCost)}</strong>
          <small>components at item standard cost</small>
        </div>
        <div>
          <span>Item standard cost</span>
          <strong>{money(c.standardCost)}</strong>
          <small>
            {num(c.standardCost) > 0
              ? `${num(c.unitCost) >= num(c.standardCost) ? '+' : ''}${money(num(c.unitCost) - num(c.standardCost))} vs roll-up`
              : 'not set'}
          </small>
        </div>
      </div>
      {(c.missingBoms.length > 0 || c.cycle) && (
        <TreeWarnings
          warnings={[
            ...c.missingBoms.map((m) => `${m} has no approved BOM; it is valued at its item standard cost.`),
            ...(c.cycle ? ['The structure contains a cycle; it was cut where it repeats.'] : []),
          ]}
        />
      )}
      <div className="drawer-section">
        <h3>Material cost per unit, by level</h3>
        <div className="bom-levels">
          {c.levels.map((l) => (
            <div className="bom-level" key={l.level}>
              <span>Level {l.level}</span>
              <div className="bom-level-bar">
                <span style={{ width: `${(num(l.cost) / max) * 100}%` }} />
              </div>
              <strong>{money(l.cost)}</strong>
            </div>
          ))}
        </div>
      </div>
      <div className="drawer-section">
        <h3>Direct components</h3>
        <div className="panel-inline">
          <Table
            compact
            pageSize={200}
            rows={c.components.map((m) => ({ ...m, id: m.itemId }))}
            columns={[
              {
                label: 'Component',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.name)}</strong>
                    <span>
                      {text(r.sku)}
                      {r.rolledUp ? ` · rolled up from BOM ${text(r.revision)}` : ''}
                    </span>
                  </div>
                ),
              },
              { label: 'Per unit', render: (r) => `${fmt(r.perUnit, 6)} ${text(r.uom)}` },
              {
                label: 'Unit cost',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{money(r.unitCost)}</strong>
                    {r.rolledUp && <span>standard {money(r.standardCost)}</span>}
                  </div>
                ),
              },
              { label: 'Cost / product unit', render: (r) => money(r.cost) },
            ]}
          />
        </div>
      </div>
    </>
  );
}

function Used({ productId, sku }: { productId: string; sku: string }) {
  const used = useWhereUsed(productId);
  if (used.isPending)
    return (
      <div className="mrp-state">
        <LoaderCircle className="spin" />
      </div>
    );
  if (used.isError)
    return (
      <div className="drawer-section">
        <div className="form-error" role="alert">
          {(used.error as Error).message}
        </div>
      </div>
    );
  const w = used.data;
  return (
    <div className="drawer-section">
      <WhereUsedTree parents={w.parents} sku={sku} />
      {w.endProducts.length > 0 && (
        <>
          <h3 className="structure-label">Ends up in</h3>
          <div className="bom-chips">
            {w.endProducts.map((p) => (
              <span className="bom-chip" key={p.productId}>
                {p.sku} · {p.name}
              </span>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
