import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { AnimatePresence } from 'motion/react';
import { Check, Copy, GitCompare, Layers, Network, Pencil, Search, ShieldCheck } from 'lucide-react';
import { useStateData, useWorkspace } from '../app/workspace';
import type { Workspace } from '../app/workspace';
import { Badge, Empty, Panel } from '../components';
import { nextRevision } from '../features/boms/api';
import BomDialog from '../features/boms/BomDialog';
import { BomDrawer } from '../features/boms/BomDrawer';
import { CompareDialog } from '../features/boms/CompareDialog';
import { ProductMark } from '../features/products/ProductMark';
import { request } from '../lib/api';
import type { BomCost, Row, State } from '../lib/api';
import { fmt, money, num, text } from '../lib/format';
import { Tabs } from '../motion/motion';
import '../features/boms/boms.css';

type Filter = 'all' | 'APPROVED' | 'DRAFT' | 'multi';

/** "Copy to new draft" form: the approved revision's output, yield and components under a new reference. */
function copyRevision({ openForm }: Workspace, data: State, bom: Row) {
  const revisions = data.boms
    .filter((b) => b.product_id === bom.product_id)
    .map((b) => text(b.revision))
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  openForm({
    title: `Copy ${text(bom.revision)} into a new draft`,
    description: `${text(bom.product)} · the draft starts with the same output, yield and components. Edit it, then approve it as the next revision.`,
    fields: [
      {
        name: 'revision',
        label: 'New revision reference',
        defaultValue: nextRevision(revisions),
        hint: 'Unique for this product, e.g. R02.',
      },
    ],
    submit: 'Create draft',
    run: (values, key) => request(`/boms/${bom.id}/copy`, { revision: values.revision }, key),
  });
}

/** Route `/boms`: BOM revisions with multi-level structure, cost roll-up and revision tools (features/boms). */
export default function BomsPage() {
  const workspace = useWorkspace();
  const { busy, can, mutate, refresh } = workspace;
  const data = useStateData();
  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState<string>();
  const [compare, setCompare] = useState<{ from: string; to: string }>();
  const [editing, setEditing] = useState<string>();
  const qc = useQueryClient();
  const updatedAt = workspace.stateQuery.dataUpdatedAt;
  useEffect(() => {
    // Every posted transaction refetches /api/state; structure, cost and comparison views follow it.
    void qc.invalidateQueries({ queryKey: ['boms'] });
  }, [qc, updatedAt]);
  const costs = new Map<string, BomCost>((data.bomCosts || []).map((c) => [c.bom_id, c]));
  const items = new Map(data.items.map((i) => [i.id, i]));
  const multi = (b: Row) => (costs.get(b.id)?.depth || 1) > 1;
  const q = query.trim().toLowerCase();
  const matches = data.boms.filter(
    (b) => !q || [b.product, b.sku, b.revision].some((v) => text(v).toLowerCase().includes(q)),
  );
  const visible = matches.filter((b) =>
    filter === 'all' ? true : filter === 'multi' ? multi(b) : b.status === filter,
  );
  const siblings = (b: Row) => data.boms.filter((x) => x.product_id === b.product_id);
  const planner = can('PLANNER');
  const byId = (id?: string) => data.boms.find((b) => b.id === id);

  function startCompare(b: Row) {
    const others = siblings(b).filter((x) => x.id !== b.id);
    const approved = others.find((x) => x.status === 'APPROVED');
    const base = b.status === 'DRAFT' ? approved || others[0] : others[0];
    setCompare({ from: base.id, to: b.id });
  }

  return (
    <>
      <div className="bom-toolbar">
        <div className="search-input">
          <Search size={16} />
          <input
            aria-label="Search BOM revisions"
            placeholder="Search product, SKU or revision…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <Tabs
          compact
          label="BOM revision filter"
          value={filter}
          onChange={(v) => setFilter(v as Filter)}
          items={[
            { value: 'all', label: 'All', count: matches.length },
            {
              value: 'APPROVED',
              label: 'Approved',
              count: matches.filter((b) => b.status === 'APPROVED').length,
            },
            { value: 'DRAFT', label: 'Drafts', count: matches.filter((b) => b.status === 'DRAFT').length },
            { value: 'multi', label: 'Multi-level', count: matches.filter(multi).length },
          ]}
        />
      </div>
      {visible.length === 0 ? (
        <Panel>
          <Empty
            title={data.boms.length ? 'No revisions match' : 'No BOM revisions yet'}
            body={
              data.boms.length
                ? 'Try another search or filter.'
                : 'Create a BOM revision for a finished or semi-finished product to start planning production.'
            }
          />
        </Panel>
      ) : (
        <div className="bom-grid">
          {visible.map((b) => {
            const cost = costs.get(b.id);
            const product = items.get(text(b.product_id));
            const uom = text(product?.uom) || 'pcs';
            const lines = data.bomLines.filter((l) => l.bom_id === b.id);
            const maxLevel = Math.max(...(cost?.levels.map((l) => num(l.cost)) || [0]), 0.000001);
            const source = byId(cost?.source_bom_id || undefined);
            return (
              <Panel key={b.id} className="bom-card">
                <div className="bom-card-head">
                  <ProductMark kind={b.product} />
                  <div className="bom-head-badges">
                    {cost?.active && <span className="bom-chip level">active</span>}
                    <Badge value={b.status} />
                  </div>
                </div>
                <h2>{text(b.product)}</h2>
                <p className="muted">
                  {text(b.sku)} · Revision {text(b.revision)}
                  {source ? ` · copied from ${text(source.revision)}` : ''}
                </p>
                <div className="bom-chips">
                  <span className="bom-chip level">
                    <Layers size={11} />
                    {cost?.depth || 1} {(cost?.depth || 1) === 1 ? 'level' : 'levels'}
                  </span>
                  {(cost?.semi_finished || 0) > 0 && (
                    <span className="bom-chip">{cost?.semi_finished} semi-finished</span>
                  )}
                  {cost?.missingBoms.map((m) => (
                    <span className="bom-chip warn" key={m}>
                      {m}: no approved BOM
                    </span>
                  ))}
                  {cost?.cycle && <span className="bom-chip warn">cycle</span>}
                </div>
                <div className="bom-meta">
                  <span>
                    Base output
                    <strong>
                      {fmt(b.output_qty)} {uom}
                    </strong>
                  </span>
                  <span>
                    Expected yield<strong>{fmt(num(b.yield_factor) * 100, 1)}%</strong>
                  </span>
                  <span>
                    Material cost / unit
                    <strong>{money(cost ? cost.unitCost : b.unit_cost)}</strong>
                    {cost && (cost.depth > 1 || num(cost.singleLevelCost) !== num(cost.unitCost)) && (
                      <small>single level {money(cost.singleLevelCost)}</small>
                    )}
                  </span>
                </div>
                {cost && cost.levels.length > 1 && (
                  <div className="bom-levels" aria-label="Material cost per unit by level">
                    {cost.levels.map((l) => (
                      <div className="bom-level" key={l.level}>
                        <span>Level {l.level}</span>
                        <div className="bom-level-bar">
                          <span style={{ width: `${(num(l.cost) / maxLevel) * 100}%` }} />
                        </div>
                        <strong>{money(l.cost)}</strong>
                      </div>
                    ))}
                  </div>
                )}
                <div className="bom-lines">
                  {lines.map((l) => {
                    const component = items.get(text(l.item_id));
                    const semi = component?.type === 'SEMI_FINISHED';
                    const sub = semi
                      ? data.boms.find((x) => x.product_id === l.item_id && costs.get(x.id)?.active)
                      : undefined;
                    return (
                      <div key={l.id} className={semi ? 'bom-line-sf' : ''}>
                        <span>
                          <span className="bom-line-dot" />
                          {text(l.name).split(' · ')[0]}
                          {semi && <em>{sub ? `BOM ${text(sub.revision)}` : 'no approved BOM'}</em>}
                        </span>
                        <strong>
                          {fmt(l.quantity, 3)} {text(l.uom)}
                        </strong>
                      </div>
                    );
                  })}
                </div>
                <div className="bom-actions">
                  <button className="button secondary small" onClick={() => setOpen(b.id)}>
                    <Network size={14} />
                    Structure
                  </button>
                  {siblings(b).length > 1 && (
                    <button className="button secondary small" onClick={() => startCompare(b)}>
                      <GitCompare size={14} />
                      Compare
                    </button>
                  )}
                  {b.status === 'APPROVED' && planner && (
                    <button
                      className="button secondary small"
                      disabled={busy}
                      onClick={() => copyRevision(workspace, data, b)}
                    >
                      <Copy size={14} />
                      Copy to draft
                    </button>
                  )}
                  {b.status === 'DRAFT' && planner && (
                    <>
                      <button
                        className="button secondary small"
                        disabled={busy}
                        onClick={() => setEditing(b.id)}
                      >
                        <Pencil size={14} />
                        Edit
                      </button>
                      <button
                        className="button secondary small"
                        disabled={busy}
                        onClick={() => mutate(`/boms/${b.id}/approve`)}
                      >
                        <Check size={14} />
                        Approve revision
                      </button>
                    </>
                  )}
                </div>
                <div className="bom-note">
                  <ShieldCheck size={14} />
                  Released orders retain this exact material snapshot.
                </div>
              </Panel>
            );
          })}
        </div>
      )}
      <AnimatePresence>
        {byId(open) && (
          <BomDrawer
            key={`drawer-${open}`}
            bom={byId(open)!}
            onClose={() => setOpen(undefined)}
            onCompare={(b) => startCompare(b)}
            onCopy={(b) => copyRevision(workspace, data, b)}
            onEdit={(b) => setEditing(b.id)}
          />
        )}
        {compare && byId(compare.from) && (
          <CompareDialog
            key="compare"
            revisions={siblings(byId(compare.from)!)}
            initialFrom={compare.from}
            initialTo={compare.to}
            onClose={() => setCompare(undefined)}
          />
        )}
        {byId(editing) && (
          <BomDialog
            key={`edit-${editing}`}
            data={data}
            draft={byId(editing)}
            onClose={() => setEditing(undefined)}
            onSuccess={() => {
              void refresh();
              workspace.notify('Draft revision saved successfully.');
            }}
          />
        )}
      </AnimatePresence>
    </>
  );
}
