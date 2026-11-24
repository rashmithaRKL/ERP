import { ArrowRight, Check, LoaderCircle, Plus } from 'lucide-react';
import { Badge, Table } from '../../components';
import type { Row } from '../../lib/api';
import { date, fmt, labelState, money, num, text } from '../../lib/format';
import { auditLabel } from '../audit/auditLabel';
import { ProductMark } from '../products/ProductMark';
import { OrderStructure } from './OrderStructure';

/** Response of `GET /api/manufacturing/{id}`. */
export type OrderDetailData = { order: Row; materials: Row[]; actuals: Row[]; events: Row[] };

export function OrderDetail({
  data,
  error,
  can,
  busy,
  onAction,
  onOutput,
}: {
  error: string;
  data: OrderDetailData;
  can: (...roles: string[]) => boolean;
  busy: boolean;
  onAction: (a: string) => void;
  onOutput: () => void;
}) {
  const o = data.order;
  const steps = [
    'DRAFT',
    'APPROVED',
    'MATERIALS_RESERVED',
    'RELEASED',
    'IN_PROGRESS',
    'QUALITY_REVIEW',
    'COMPLETED',
    'CLOSED',
  ];
  const index = o.status === 'PARTIALLY_COMPLETED' ? 4 : steps.indexOf(text(o.status));
  const next: Record<string, { action: string; label: string; roles: string[] }> = {
    DRAFT: { action: 'approve', label: 'Approve order', roles: ['PLANNER'] },
    APPROVED: { action: 'reserve', label: 'Reserve materials', roles: ['PLANNER', 'WAREHOUSE'] },
    MATERIALS_RESERVED: { action: 'issue', label: 'Issue materials & release', roles: ['WAREHOUSE'] },
    RELEASED: { action: 'start', label: 'Start production', roles: ['OPERATOR', 'PLANNER'] },
    ON_HOLD: { action: 'resume', label: 'Resume production', roles: ['OPERATOR', 'PLANNER'] },
    COMPLETED: { action: 'close', label: 'Close reconciled order', roles: ['PLANNER'] },
  };
  const action = next[text(o.status)];
  return (
    <>
      <div className="drawer-body">
        <div className="detail-product">
          <ProductMark kind={o.product} />
          <div>
            <h2>{text(o.product)}</h2>
            <p>
              BOM {text(o.bom_revision)} · Due {date(o.due_date)}
              {o.parent_order_id ? ' · child order' : ''}
            </p>
          </div>
          <Badge value={o.status} />
        </div>
        {error && (
          <div className="form-error" role="alert">
            {error}
          </div>
        )}
        <div className="lifecycle">
          {steps.map((s, i) => (
            <div key={s} className={i <= index ? 'done' : ''}>
              <span>{i < index ? <Check size={12} /> : i + 1}</span>
              <small>{labelState(s)}</small>
            </div>
          ))}
        </div>
        <div className="detail-stats">
          <div>
            <span>Planned</span>
            <strong>
              {fmt(o.quantity)} <small>pcs</small>
            </strong>
          </div>
          <div>
            <span>Good output</span>
            <strong>{fmt(o.good_qty)}</strong>
          </div>
          <div>
            <span>Scrap</span>
            <strong>{fmt(o.scrap_qty)}</strong>
          </div>
          <div>
            <span>Remaining WIP</span>
            <strong>{fmt(num(o.quantity) - num(o.good_qty) - num(o.scrap_qty))}</strong>
          </div>
        </div>
        <h3>Material snapshot</h3>
        <Table
          rows={data.materials}
          columns={[
            { label: 'Material', key: 'name' },
            { label: 'Required', render: (r) => `${fmt(r.required_qty, 3)} ${r.uom}` },
            { label: 'Issued', render: (r) => `${fmt(r.issued_qty, 3)} ${r.uom}` },
          ]}
          compact
        />
        <OrderStructure order={o} />
        <h3>Cost reconciliation</h3>
        <div className="reconciliation">
          {[
            'estimated_cost',
            'material_cost',
            'conversion_cost',
            'wip_cost',
            'finished_cost',
            'scrap_cost',
          ].map((k) => (
            <div key={k}>
              <span>{labelState(k)}</span>
              <strong>{money(o[k])}</strong>
            </div>
          ))}
        </div>
        <h3>Document activity</h3>
        <div className="activity-feed">
          {data.events.map((e) => (
            <div key={e.id}>
              <span className="activity-dot" />
              <div>
                <strong>{auditLabel(e.action)}</strong>
                <p>{text(e.detail)}</p>
                <small>
                  {text(e.actor)} · {date(e.created_at)}
                </small>
              </div>
            </div>
          ))}
        </div>
      </div>
      <div className="dialog-footer detail-footer">
        {['DRAFT', 'APPROVED', 'MATERIALS_RESERVED'].includes(text(o.status)) && can('PLANNER') && (
          <button className="button danger-ghost" disabled={busy} onClick={() => onAction('cancel')}>
            Cancel order
          </button>
        )}
        <span className="grow" />
        {action && can(...action.roles) && (
          <button className="button primary" disabled={busy} onClick={() => onAction(action.action)}>
            {busy && <LoaderCircle size={15} className="spin" />}
            {action.label}
            <ArrowRight size={15} />
          </button>
        )}
        {['IN_PROGRESS', 'PARTIALLY_COMPLETED'].includes(text(o.status)) && can('OPERATOR') && (
          <button className="button primary" onClick={onOutput}>
            <Plus size={16} />
            Record output
          </button>
        )}
        {o.status === 'QUALITY_REVIEW' && (
          <span className="muted">Complete pending inspections in Quality control.</span>
        )}
      </div>
    </>
  );
}
