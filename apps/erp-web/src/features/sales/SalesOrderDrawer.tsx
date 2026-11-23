import {
  ArrowRight,
  Ban,
  Check,
  CheckCheck,
  Factory,
  LoaderCircle,
  Lock,
  Pencil,
  RotateCcw,
  Truck,
} from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { useWorkspace } from '../../app/workspace';
import { Badge, Empty, Overlay, Progress, Table } from '../../components';
import type { Row } from '../../lib/api';
import { date, fmt, labelState, money, num, text } from '../../lib/format';
import { auditLabel } from '../audit/auditLabel';
import { createOrder } from '../manufacturing/forms';
import { invalidateSales, useSalesCommand, useSalesOrder } from './api';
import type { SalesOrderDetail } from './api';
import { endSalesOrder, returnDelivery } from './forms';
import './sales.css';

const STEPS = ['DRAFT', 'CONFIRMED', 'PARTIAL', 'DELIVERED'];

/** Sales order side drawer: header, lines with delivery/return progress, deliveries, returns, production, activity. */
export function SalesOrderDrawer({
  orderId,
  onClose,
  onEdit,
  onDispatch,
}: {
  orderId: string;
  onClose: () => void;
  onEdit: (detail: SalesOrderDetail) => void;
  onDispatch: (detail: SalesOrderDetail, line: Row) => void;
}) {
  const query = useSalesOrder(orderId);
  return (
    <Overlay
      title={text(query.data?.order.number) || 'Sales order'}
      subtitle="Order lines, deliveries, returns and document history"
      onClose={onClose}
      drawer
      wide
    >
      {query.isPending ? (
        <div className="drawer-body sales-drawer-loading">
          <LoaderCircle className="spin" />
        </div>
      ) : query.isError ? (
        <div className="drawer-body">
          <div className="form-error" role="alert">
            {(query.error as Error).message}
          </div>
          <button className="button secondary small sales-spaced" onClick={() => query.refetch()}>
            Retry
          </button>
        </div>
      ) : (
        <Detail data={query.data} onEdit={onEdit} onDispatch={onDispatch} />
      )}
    </Overlay>
  );
}

function Detail({
  data,
  onEdit,
  onDispatch,
}: {
  data: SalesOrderDetail;
  onEdit: (detail: SalesOrderDetail) => void;
  onDispatch: (detail: SalesOrderDetail, line: Row) => void;
}) {
  const workspace = useWorkspace();
  const { can, openOrder } = workspace;
  const qc = useQueryClient();
  const { run, pending, error } = useSalesCommand();
  const o = data.order;
  const status = text(o.status);
  const open = ['CONFIRMED', 'PARTIAL'].includes(status);
  const ended = ['CANCELLED', 'CLOSED'].includes(status);
  const index = ended
    ? STEPS.indexOf(data.deliveries.length ? 'PARTIAL' : o.confirmed_at ? 'CONFIRMED' : 'DRAFT')
    : STEPS.indexOf(status);
  const steps = ended ? [...STEPS.slice(0, index + 1), status] : STEPS;
  const ordered = data.lines.reduce((s, l) => s + num(l.quantity), 0);
  const delivered = data.lines.reduce((s, l) => s + num(l.delivered), 0);
  const returned = data.lines.reduce((s, l) => s + num(l.returned), 0);
  const units = new Set(data.lines.map((l) => text(l.uom)));
  const unit = units.size === 1 ? [...units][0] : '';
  const openProduction = data.manufacturing.filter((m) =>
    ['DRAFT', 'APPROVED', 'MATERIALS_RESERVED'].includes(text(m.status)),
  );
  const sell = can('SALES', 'PLANNER');
  return (
    <>
      <div className="drawer-body sales-drawer">
        <div className="detail-product">
          <span className="product-mark blue">
            <Truck size={20} />
          </span>
          <div>
            <h2>{text(o.customer)}</h2>
            <p>
              {o.customer_reference ? `Ref ${text(o.customer_reference)} · ` : ''}Required by{' '}
              {date(o.due_date)} · {data.lines.length} {data.lines.length === 1 ? 'line' : 'lines'}
            </p>
          </div>
          <Badge value={o.status} />
        </div>
        {error && (
          <div className="form-error" role="alert">
            {error}
          </div>
        )}
        {o.customer_active === false && !ended && (
          <div className="sales-notice warn">
            This customer is inactive. Existing documents continue, but the draft cannot be confirmed.
          </div>
        )}
        {status === 'DRAFT' && data.credit.exceeded && (
          <div className="sales-notice warn">
            Credit limit {money(data.credit.limit)}: open receivables {money(data.credit.receivables)} plus
            this order ({money(o.total)}) exceed it. Confirmation will record a warning.
          </div>
        )}
        {ended && (
          <div className={`sales-notice ${status === 'CANCELLED' ? 'danger' : ''}`}>
            <strong>{status === 'CANCELLED' ? 'Cancelled' : 'Closed short'}</strong>
            {o.closed_by_name ? ` by ${text(o.closed_by_name)}` : ''}
            {o.closed_at ? ` on ${date(o.closed_at)}` : ''}: {text(o.status_reason)}
          </div>
        )}
        <div className="lifecycle">
          {steps.map((s, i) => (
            <div key={s} className={i <= index || (ended && i === steps.length - 1) ? 'done' : ''}>
              <span>
                {ended && i === steps.length - 1 ? (
                  status === 'CANCELLED' ? (
                    <Ban size={12} />
                  ) : (
                    <Lock size={12} />
                  )
                ) : i < index || (i === index && (status === 'DELIVERED' || ended)) ? (
                  <Check size={12} />
                ) : (
                  i + 1
                )}
              </span>
              <small>{s === 'PARTIAL' ? 'partially delivered' : labelState(s)}</small>
            </div>
          ))}
        </div>
        <div className="detail-stats">
          <div>
            <span>Order total</span>
            <strong>
              {fmt(o.total, 0)} <small>LKR</small>
            </strong>
          </div>
          <div>
            <span>Delivered</span>
            <strong>
              {unit
                ? fmt(delivered)
                : `${data.lines.filter((l) => num(l.delivered) >= num(l.quantity)).length}`}{' '}
              <small>{unit ? `/ ${fmt(ordered)} ${unit}` : `/ ${data.lines.length} lines`}</small>
            </strong>
          </div>
          <div>
            <span>Returned</span>
            <strong>
              {fmt(returned)} <small>{unit}</small>
            </strong>
          </div>
          <div>
            <span>Discount</span>
            <strong>
              {fmt(o.discount_total, 0)} <small>LKR</small>
            </strong>
          </div>
        </div>

        <h3>Order lines</h3>
        <Table
          rows={data.lines}
          compact
          pageSize={100}
          columns={[
            {
              label: 'Product',
              render: (l) => (
                <div className="cell-stack">
                  <strong>
                    {text(l.line_no)}. {text(l.product)}
                  </strong>
                  <span>
                    {text(l.sku)} · {money(l.unit_price)}
                    {num(l.discount_pct) > 0 ? ` less ${fmt(l.discount_pct, 2)}%` : ''}
                  </span>
                </div>
              ),
            },
            {
              label: 'Delivered',
              render: (l) => (
                <div className="table-progress">
                  <span>
                    {fmt(l.delivered)} <em>/ {fmt(l.quantity)}</em> {text(l.uom)}
                    {num(l.returned) > 0 && <em> · {fmt(l.returned)} returned</em>}
                  </span>
                  <Progress value={(num(l.delivered) / num(l.quantity)) * 100} />
                </div>
              ),
            },
            {
              label: 'Planned',
              render: (l) => (
                <span className="muted">
                  {fmt(l.planned)} {text(l.uom)}
                </span>
              ),
            },
            { label: 'Line total', render: (l) => money(l.line_total) },
            {
              label: '',
              render: (l) => (
                <div className="row-actions">
                  {open && num(l.outstanding) > 0 && can('WAREHOUSE') && (
                    <button className="button small secondary" onClick={() => onDispatch(data, l)}>
                      <Truck size={13} />
                      Dispatch
                    </button>
                  )}
                  {open && num(l.planned) < num(l.quantity) && can('PLANNER') && (
                    <button
                      className="button small secondary"
                      onClick={() =>
                        createOrder(
                          workspace,
                          { ...l, number: o.number, customer: o.customer, due_date: o.due_date },
                          () => invalidateSales(qc),
                        )
                      }
                    >
                      <Factory size={13} />
                      Plan
                    </button>
                  )}
                </div>
              ),
            },
          ]}
        />

        <h3>Deliveries</h3>
        {data.deliveries.length ? (
          <Table
            rows={data.deliveries}
            compact
            pageSize={100}
            columns={[
              {
                label: 'Delivery',
                render: (d) => (
                  <div className="cell-stack">
                    <strong>{text(d.reference)}</strong>
                    <span>
                      Line {text(d.line_no)} · lot {text(d.lot)} · {date(d.created_at)}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Quantity',
                render: (d) => (
                  <span>
                    {fmt(d.quantity)} {text(d.uom)}
                    {num(d.returned) > 0 && <span className="muted"> · {fmt(d.returned)} returned</span>}
                  </span>
                ),
              },
              { label: 'Invoiced', render: (d) => money(d.revenue_amount) },
              {
                label: '',
                render: (d) =>
                  num(d.returnable) > 0 &&
                  can('SALES', 'PLANNER', 'WAREHOUSE') && (
                    <button
                      className="button small secondary"
                      onClick={() => returnDelivery(workspace, qc, d)}
                    >
                      <RotateCcw size={13} />
                      Return
                    </button>
                  ),
              },
            ]}
          />
        ) : (
          <p className="sales-empty-line">No deliveries yet.</p>
        )}

        {data.returns.length > 0 && (
          <>
            <h3>Customer returns</h3>
            <Table
              rows={data.returns}
              compact
              pageSize={100}
              columns={[
                {
                  label: 'Return',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>{text(r.number)}</strong>
                      <span>
                        {text(r.delivery_reference)} · line {text(r.line_no)} · {date(r.created_at)}
                      </span>
                    </div>
                  ),
                },
                {
                  label: 'Quantity',
                  render: (r) => `${fmt(r.quantity)} ${text(r.uom)}`,
                },
                { label: 'Credited', render: (r) => money(r.credit_amount) },
                {
                  label: 'Quality decision',
                  render: (r) => (
                    <div className="cell-stack">
                      <Badge value={r.inspection_status} />
                      <span>
                        {r.status === 'AWAITING_QC'
                          ? 'In quarantine'
                          : r.status === 'RESTOCKED'
                            ? 'Released to stock'
                            : 'Rejected, held'}
                      </span>
                    </div>
                  ),
                },
                {
                  label: 'Reason',
                  render: (r) => (
                    <span className="notes-cell" title={text(r.reason)}>
                      {text(r.reason)}
                    </span>
                  ),
                },
              ]}
            />
          </>
        )}

        <h3>Production</h3>
        {data.manufacturing.length ? (
          <>
            <Table
              rows={data.manufacturing}
              compact
              pageSize={100}
              onRow={(m) => openOrder(m.id)}
              columns={[
                {
                  label: 'Manufacturing order',
                  render: (m) => (
                    <div className="cell-stack">
                      <strong>{text(m.number)}</strong>
                      <span>
                        {m.line_no ? `Line ${text(m.line_no)} · ` : ''}
                        {text(m.product)}
                      </span>
                    </div>
                  ),
                },
                {
                  label: 'Output',
                  render: (m) => `${fmt(m.good_qty)} / ${fmt(m.quantity)} ${text(m.uom)}`,
                },
                { label: 'Status', render: (m) => <Badge value={m.status} /> },
                { label: 'Due', render: (m) => date(m.due_date) },
              ]}
            />
            {status === 'CANCELLED' && openProduction.length > 0 && (
              <div className="sales-notice warn sales-spaced">
                {openProduction.map((m) => text(m.number)).join(', ')}{' '}
                {openProduction.length === 1 ? 'is' : 'are'} still open. Cancel in Manufacturing if the stock
                is not needed.
              </div>
            )}
          </>
        ) : (
          <p className="sales-empty-line">No manufacturing orders are linked to this order.</p>
        )}

        {text(o.notes) && (
          <>
            <h3>Notes</h3>
            <p className="sales-notes">{text(o.notes)}</p>
          </>
        )}

        <h3>Document activity</h3>
        {data.activity.length ? (
          <div className="activity-feed">
            {data.activity.map((e) => (
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
        ) : (
          <Empty title="No activity yet" />
        )}
      </div>
      <div className="dialog-footer detail-footer">
        {sell && ['DRAFT', 'CONFIRMED'].includes(status) && !data.deliveries.length && (
          <button
            className="button danger-ghost"
            disabled={!!pending}
            onClick={() => endSalesOrder(workspace, qc, o, 'cancel')}
          >
            Cancel order
          </button>
        )}
        {sell && status === 'PARTIAL' && (
          <button
            className="button danger-ghost"
            disabled={!!pending}
            onClick={() => endSalesOrder(workspace, qc, o, 'close')}
          >
            Close short
          </button>
        )}
        <span className="grow" />
        {sell && status === 'DRAFT' && (
          <>
            <button className="button secondary" disabled={!!pending} onClick={() => onEdit(data)}>
              <Pencil size={15} />
              Edit draft
            </button>
            <button
              className="button primary"
              disabled={!!pending || !data.lines.length}
              onClick={() => run('confirm', `/sales/orders/${o.id}/confirm`)}
            >
              {pending === 'confirm' ? <LoaderCircle size={15} className="spin" /> : <CheckCheck size={15} />}
              Confirm order
              <ArrowRight size={15} />
            </button>
          </>
        )}
        {open && !can('WAREHOUSE') && (
          <span className="muted">Warehouse dispatches each line from released lots.</span>
        )}
      </div>
    </>
  );
}
