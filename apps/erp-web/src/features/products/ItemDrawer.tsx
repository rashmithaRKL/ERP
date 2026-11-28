import { useState } from 'react';
import {
  ArrowRight,
  ChevronLeft,
  ChevronRight,
  LoaderCircle,
  Lock,
  Pencil,
  Plus,
  Power,
  PowerOff,
  Ruler,
  Trash2,
} from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Badge, Empty, Overlay, Table } from '../../components';
import { UnitQuantityInput } from '../../components/UnitQuantityInput';
import type { UnitQuantity } from '../../components/UnitQuantityInput';
import type { Row } from '../../lib/api';
import { date, fmt, humanizeText, labelState, money, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { auditLabel } from '../audit/auditLabel';
import { addBarcode, editConversion, editItem } from './forms';
import { ItemTypeBadge } from './ItemTypeBadge';
import { useItemDetail, useItemMovements } from './itemMaster';
import type { ItemDetail } from './itemMaster';
import { ProductMark } from './ProductMark';
import { ItemTaxSection } from '../tax/TaxProfiles';

/** Item master side drawer: overview, units & conversions, barcodes, variants, stock, where-used, movements. */
export function ItemDrawer({
  itemId,
  onClose,
  onOpenItem,
  onOpenFamily,
}: {
  itemId: string;
  onClose: () => void;
  onOpenItem: (id: string) => void;
  onOpenFamily: (id: string) => void;
}) {
  const detail = useItemDetail(itemId);
  return (
    <Overlay
      title={text(detail.data?.item.sku) || 'Item'}
      subtitle="Item master · units, barcodes, stock and history"
      onClose={onClose}
      drawer
      wide
    >
      {detail.isPending ? (
        <div className="drawer-body item-list-state">
          <LoaderCircle className="spin" />
        </div>
      ) : detail.isError ? (
        <div className="drawer-body">
          <div className="form-error" role="alert">
            {(detail.error as Error).message}
          </div>
        </div>
      ) : (
        <ItemBody data={detail.data} onOpenItem={onOpenItem} onOpenFamily={onOpenFamily} />
      )}
    </Overlay>
  );
}

function ItemBody({
  data,
  onOpenItem,
  onOpenFamily,
}: {
  data: ItemDetail;
  onOpenItem: (id: string) => void;
  onOpenFamily: (id: string) => void;
}) {
  const workspace = useWorkspace();
  const { can, busy, mutate } = workspace;
  const [tab, setTab] = useState('overview');
  const item = data.item;
  const uom = text(item.uom);
  const editable = can('PLANNER');
  const tabs = [
    { value: 'overview', label: 'Overview' },
    { value: 'units', label: 'Units', count: data.units.length },
    { value: 'barcodes', label: 'Barcodes', count: data.barcodes.length },
    ...(data.variants ? [{ value: 'variants', label: 'Variants', count: data.variants.items.length }] : []),
    { value: 'stock', label: 'Stock', count: data.stock.lots.length },
    { value: 'used', label: 'Where used', count: data.whereUsed.length + data.producedBy.length },
    { value: 'movements', label: 'Movements' },
  ];
  return (
    <div className="item-drawer">
      <div className="drawer-body">
        <div className="detail-product">
          <ProductMark kind={item.name} type={item.type} />
          <div>
            <h2>{text(item.name)}</h2>
            <div className="item-drawer-meta">
              <ItemTypeBadge type={item.type} />
              <span>{data.categoryPath.map((c) => text(c.name)).join(' / ') || 'No category'}</span>
              {data.variants && (
                <span className="chip">
                  <strong>{data.variants.code}</strong> {text(item.variant_label)}
                </span>
              )}
            </div>
          </div>
          <Badge value={item.active ? 'ACTIVE' : 'INACTIVE'} />
        </div>
        {data.usage.skuLocked && (
          <div className="lock-note">
            <Lock size={14} />
            <span>
              SKU and base unit are locked: this item already has{' '}
              {data.usage.movements ? 'stock movements' : 'document or BOM references'}. Names, cost, planning
              fields and units for documents stay editable.
            </span>
          </div>
        )}
        {!item.active && (
          <div className="lock-note">
            <PowerOff size={14} />
            <span>
              Inactive: new documents and BOMs cannot use this item. History and stock stay visible.
            </span>
          </div>
        )}
        <div className="detail-stats">
          <div>
            <span>On hand</span>
            <strong>
              {fmt(item.on_hand, 3)} <small>{uom}</small>
            </strong>
          </div>
          <div>
            <span>Available</span>
            <strong>{fmt(item.available, 3)}</strong>
          </div>
          <div>
            <span>Stock value</span>
            <strong>{money(item.stock_value)}</strong>
          </div>
          <div>
            <span>Standard cost</span>
            <strong>
              {money(item.standard_cost)} <small>/ {uom}</small>
            </strong>
          </div>
        </div>
        <div className="drawer-tabs">
          <Tabs compact label="Item detail sections" items={tabs} value={tab} onChange={setTab} />
        </div>
        {tab === 'overview' && <Overview data={data} />}
        {tab === 'units' && <Units data={data} editable={editable} />}
        {tab === 'barcodes' && <Barcodes data={data} editable={editable} />}
        {tab === 'variants' && data.variants && (
          <div className="drawer-section">
            <h3>
              <span>
                {data.variants.name} · {data.variants.code}
              </span>
              <button
                className="button secondary small"
                onClick={() => onOpenFamily(data.variants!.templateId)}
              >
                Open family
                <ArrowRight size={13} />
              </button>
            </h3>
            <div className="family-attributes">
              {data.variants.attributes.map((a) => (
                <span className="chip" key={a.name}>
                  <strong>{a.name}</strong> {a.values.join(' / ')}
                </span>
              ))}
            </div>
            <div className="drawer-section">
              <Table
                compact
                pageSize={500}
                rows={data.variants.items}
                onRow={(r) => r.id !== item.id && onOpenItem(r.id)}
                columns={[
                  {
                    label: 'Variant',
                    render: (r) => (
                      <div className="cell-stack">
                        <strong>{text(r.variant_label)}</strong>
                        <span>
                          {text(r.sku)}
                          {r.id === item.id ? ' · this item' : ''}
                        </span>
                      </div>
                    ),
                  },
                  { label: 'Standard cost', render: (r) => money(r.standard_cost) },
                  { label: 'On hand', render: (r) => `${fmt(r.on_hand, 3)} ${text(r.uom)}` },
                  { label: 'State', render: (r) => <Badge value={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
                ]}
              />
            </div>
          </div>
        )}
        {tab === 'stock' && <Stock data={data} />}
        {tab === 'used' && <WhereUsed data={data} />}
        {tab === 'movements' && <Movements itemId={item.id} />}
      </div>
      {editable && (
        <div className="dialog-footer detail-footer">
          {item.active ? (
            <button
              className="button danger-ghost"
              disabled={busy}
              onClick={() => mutate(`/items/${item.id}/deactivate`)}
            >
              <PowerOff size={15} />
              Deactivate
            </button>
          ) : (
            <button
              className="button secondary"
              disabled={busy}
              onClick={() => mutate(`/items/${item.id}/activate`)}
            >
              <Power size={15} />
              Reactivate
            </button>
          )}
          <span className="grow" />
          <button className="button primary" disabled={busy} onClick={() => void editItem(workspace, data)}>
            <Pencil size={15} />
            Edit item
          </button>
        </div>
      )}
    </div>
  );
}

function Overview({ data }: { data: ItemDetail }) {
  const item = data.item;
  const uom = text(item.uom);
  return (
    <>
      <div className="drawer-section">
        <dl className="detail-list">
          <dt>SKU</dt>
          <dd>{text(item.sku)}</dd>
          <dt>Type</dt>
          <dd>
            <ItemTypeBadge type={item.type} />
          </dd>
          <dt>Base unit</dt>
          <dd>
            {text(item.unit_name)} ({uom}) · {text(item.unit_decimals)} decimal places
          </dd>
          <dt>Category</dt>
          <dd>{data.categoryPath.map((c) => text(c.name)).join(' / ') || '—'}</dd>
          <dt>Safety stock</dt>
          <dd>
            {fmt(item.reorder_level, 3)} {uom}
          </dd>
          <dt>Reorder quantity</dt>
          <dd>{num(item.reorder_qty) ? `${fmt(item.reorder_qty, 3)} ${uom}` : '—'}</dd>
          <dt>Lead time</dt>
          <dd>{num(item.lead_time_days) ? `${text(item.lead_time_days)} days` : '—'}</dd>
          <dt>Standard cost</dt>
          <dd>
            {money(item.standard_cost)} per {uom}
            <span className="muted"> · estimates and new snapshots only</span>
          </dd>
          {data.variants && (
            <>
              <dt>Variant of</dt>
              <dd>
                {data.variants.name} ({data.variants.code}) · {text(item.variant_label)}
              </dd>
            </>
          )}
          <dt>Description</dt>
          <dd>{text(item.description) || '—'}</dd>
          <dt>Created · updated</dt>
          <dd>
            {date(item.created_at)} · {date(item.updated_at)}
          </dd>
        </dl>
      </div>
      <ItemTaxSection itemId={item.id} />
      <div className="drawer-section">
        <h3>Item history</h3>
        {data.activity.length ? (
          <div className="activity-feed">
            {data.activity.map((e) => (
              <div key={e.id}>
                <span className="activity-dot" />
                <div>
                  <strong>{auditLabel(e.action)}</strong>
                  <p>{humanizeText(e.detail)}</p>
                  <small>
                    {text(e.actor)} · {date(e.created_at)}
                  </small>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <Empty title="No item history yet" body="Edits, conversions and barcodes are recorded here." />
        )}
      </div>
    </>
  );
}

function Units({ data, editable }: { data: ItemDetail; editable: boolean }) {
  const workspace = useWorkspace();
  const { busy, mutate } = workspace;
  const item = data.item;
  const uom = text(item.uom);
  const [check, setCheck] = useState<UnitQuantity>({ quantity: '1', unit: '' });
  const standard = data.units.filter((u) => u.source === 'STANDARD');
  return (
    <>
      <div className="drawer-section">
        <div className="base-unit-card">
          <span className="base-unit-icon">
            <Ruler size={17} />
          </span>
          <div>
            <strong>
              {text(item.unit_name)} ({uom})
            </strong>
            <span>
              Base unit · stock, BOMs and the ledger always use it · {text(item.unit_decimals)} decimal places
            </span>
          </div>
        </div>
        <h3>
          <span>Conversions</span>
          {editable && (
            <button className="button secondary small" onClick={() => void editConversion(workspace, data)}>
              <Plus size={13} />
              Add conversion
            </button>
          )}
        </h3>
        <div className="panel-inline">
          <Table
            compact
            pageSize={500}
            rows={data.conversions}
            columns={[
              {
                label: 'Unit',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.unit_code)}</strong>
                    <span>{text(r.unit_name)}</span>
                  </div>
                ),
              },
              {
                label: 'Equals',
                className: 'factor-cell',
                render: (r) => `1 ${text(r.unit_code)} = ${fmt(r.factor, 12)} ${uom}`,
              },
              {
                label: 'Used for',
                render: (r) => (
                  <span className="family-attributes">
                    {r.purchase && <span className="chip">Purchasing</span>}
                    {r.sales && <span className="chip">Sales</span>}
                    {!r.purchase && !r.sales && <span className="chip">Internal</span>}
                  </span>
                ),
              },
              ...(editable
                ? [
                    {
                      label: '',
                      render: (r: Row) => (
                        <span className="row-actions">
                          <button
                            className="icon-button"
                            aria-label={`Edit ${text(r.unit_code)} conversion`}
                            onClick={() => void editConversion(workspace, data, r)}
                          >
                            <Pencil size={14} />
                          </button>
                          <button
                            className="icon-button"
                            aria-label={`Remove ${text(r.unit_code)} conversion`}
                            disabled={busy}
                            onClick={() => mutate(`/items/${item.id}/conversions/${r.unit_code}/remove`)}
                          >
                            <Trash2 size={14} />
                          </button>
                        </span>
                      ),
                    },
                  ]
                : []),
            ]}
          />
        </div>
      </div>
      {standard.length > 0 && (
        <div className="drawer-section">
          <h3>Standard units of the same kind</h3>
          <div className="family-attributes">
            {standard.map((u) => (
              <span className="chip" key={u.code}>
                <strong>1 {u.code}</strong> = {fmt(u.factor, 12)} {uom}
              </span>
            ))}
          </div>
        </div>
      )}
      <div className="drawer-section conversion-check">
        <UnitQuantityInput
          itemId={item.id}
          label="Check a conversion"
          value={check}
          onChange={setCheck}
          hint="Pick a unit to see its base-unit equivalent."
        />
      </div>
    </>
  );
}

function Barcodes({ data, editable }: { data: ItemDetail; editable: boolean }) {
  const workspace = useWorkspace();
  const { busy, mutate } = workspace;
  const item = data.item;
  return (
    <div className="drawer-section">
      <h3>
        <span>Barcodes</span>
        {editable && (
          <button className="button secondary small" onClick={() => addBarcode(workspace, data)}>
            <Plus size={13} />
            Add barcode
          </button>
        )}
      </h3>
      <div className="panel-inline">
        <Table
          compact
          pageSize={500}
          rows={data.barcodes}
          columns={[
            { label: 'Barcode', render: (r) => <span className="barcode-code">{text(r.barcode)}</span> },
            {
              label: 'One scan',
              render: (r) =>
                r.unit_code && r.unit_code !== item.uom
                  ? r.base_quantity == null
                    ? `1 ${text(r.unit_code)} · conversion missing`
                    : `1 ${text(r.unit_code)} = ${fmt(r.base_quantity, 6)} ${text(item.uom)}`
                  : `1 ${text(item.uom)}`,
            },
            { label: 'Added', render: (r) => date(r.created_at) },
            ...(editable
              ? [
                  {
                    label: '',
                    render: (r: Row) => (
                      <button
                        className="icon-button"
                        aria-label={`Remove barcode ${text(r.barcode)}`}
                        disabled={busy}
                        onClick={() => mutate(`/items/${item.id}/barcodes/${r.id}/remove`)}
                      >
                        <Trash2 size={14} />
                      </button>
                    ),
                  },
                ]
              : []),
          ]}
        />
      </div>
    </div>
  );
}

function Stock({ data }: { data: ItemDetail }) {
  const uom = text(data.item.uom);
  return (
    <>
      <div className="drawer-section">
        <h3>By warehouse</h3>
        <div className="panel-inline">
          <Table
            compact
            pageSize={500}
            rows={data.stock.warehouses}
            columns={[
              { label: 'Warehouse', key: 'warehouse' },
              { label: 'On hand', render: (r) => `${fmt(r.on_hand, 3)} ${uom}` },
              { label: 'Available', render: (r) => fmt(r.available, 3) },
              { label: 'Quarantine', render: (r) => fmt(r.quarantine, 3) },
              { label: 'Value', render: (r) => money(r.stock_value) },
            ]}
          />
        </div>
      </div>
      <div className="drawer-section">
        <h3>Lots</h3>
        <div className="panel-inline">
          <Table
            compact
            pageSize={500}
            rows={data.stock.lots}
            columns={[
              {
                label: 'Lot',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.number)}</strong>
                    <span>
                      {text(r.warehouse)} · {labelState(r.source_type)}
                    </span>
                  </div>
                ),
              },
              { label: 'Status', render: (r) => <Badge value={r.status} /> },
              { label: 'Quantity', render: (r) => `${fmt(r.quantity, 3)} ${uom}` },
              { label: 'Reserved', render: (r) => fmt(r.reserved, 3) },
              { label: 'Unit cost', render: (r) => money(r.unit_cost) },
              { label: 'Received', render: (r) => date(r.created_at) },
            ]}
          />
        </div>
      </div>
    </>
  );
}

function WhereUsed({ data }: { data: ItemDetail }) {
  const uom = text(data.item.uom);
  return (
    <>
      <div className="drawer-section">
        <h3>Used as a component</h3>
        <div className="panel-inline">
          <Table
            compact
            pageSize={500}
            rows={data.whereUsed.map((r) => ({ ...r, id: text(r.bom_id) }))}
            columns={[
              {
                label: 'Product',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.product)}</strong>
                    <span>{text(r.product_sku)}</span>
                  </div>
                ),
              },
              { label: 'Revision', key: 'revision' },
              { label: 'Status', render: (r) => <Badge value={r.status} /> },
              {
                label: 'Quantity',
                render: (r) => `${fmt(r.quantity, 6)} ${uom} per ${fmt(r.output_qty)} ${text(r.product_uom)}`,
              },
            ]}
          />
        </div>
      </div>
      <div className="drawer-section">
        <h3>Produced by</h3>
        <div className="panel-inline">
          <Table
            compact
            pageSize={500}
            rows={data.producedBy.map((r) => ({ ...r, id: text(r.bom_id) }))}
            columns={[
              { label: 'Revision', key: 'revision' },
              { label: 'Status', render: (r) => <Badge value={r.status} /> },
              { label: 'Base output', render: (r) => `${fmt(r.output_qty)} ${uom}` },
              { label: 'Yield', render: (r) => `${fmt(num(r.yield_factor) * 100, 1)}%` },
              { label: 'Components', key: 'components' },
            ]}
          />
        </div>
      </div>
    </>
  );
}

function Movements({ itemId }: { itemId: string }) {
  const [cursors, setCursors] = useState<string[]>(['']);
  const cursor = cursors[cursors.length - 1];
  const page = useItemMovements(itemId, cursor);
  return (
    <div className="drawer-section">
      <h3>Stock ledger</h3>
      {page.isError ? (
        <div className="form-error">{(page.error as Error).message}</div>
      ) : (
        <div className="panel-inline">
          <div className={page.isFetching ? 'is-refreshing' : undefined}>
            <Table
              compact
              pageSize={500}
              rows={page.data?.items || []}
              columns={[
                { label: 'Date', render: (r) => date(r.created_at) },
                {
                  label: 'Movement',
                  render: (r) => (
                    <span className={num(r.delta) > 0 ? 'positive' : 'negative'}>
                      {num(r.delta) > 0 ? '+' : ''}
                      {fmt(r.delta, 3)} {text(r.uom)}
                    </span>
                  ),
                },
                {
                  label: 'Lot',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>{text(r.lot)}</strong>
                      <span>{text(r.warehouse)}</span>
                    </div>
                  ),
                },
                { label: 'Reason', render: (r) => labelState(r.reason) },
                { label: 'Reference', key: 'reference' },
                { label: 'Value', render: (r) => money(r.value_delta) },
                { label: 'Actor', key: 'actor' },
              ]}
            />
          </div>
          <div className="table-footer">
            <span>{page.isPending ? 'Loading…' : `Page ${cursors.length}`}</span>
            <div>
              <button
                className="icon-button"
                aria-label="Newer movements"
                disabled={cursors.length === 1}
                onClick={() => setCursors(cursors.slice(0, -1))}
              >
                <ChevronLeft size={16} />
              </button>
              <button
                className="icon-button"
                aria-label="Older movements"
                disabled={!page.data?.next}
                onClick={() => page.data?.next && setCursors([...cursors, page.data.next])}
              >
                <ChevronRight size={16} />
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
