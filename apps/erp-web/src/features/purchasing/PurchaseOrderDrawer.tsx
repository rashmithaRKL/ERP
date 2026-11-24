import {
  ArrowRight,
  Ban,
  Check,
  CheckCheck,
  FileCheck2,
  LoaderCircle,
  Lock,
  PackageCheck,
  Pencil,
  RotateCcw,
  Send,
  ShoppingCart,
} from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { useWorkspace } from '../../app/workspace';
import { Badge, Empty, Overlay, Progress, Table } from '../../components';
import type { Row } from '../../lib/api';
import { date, fmt, money, num, text, today } from '../../lib/format';
import { auditLabel } from '../audit/auditLabel';
import { statusLabel, usePurchaseCommand, usePurchaseOrder } from './api';
import type { PurchaseOrderDetail } from './api';
import { endPurchaseOrder, returnToSupplier } from './forms';
import { PurchaseStatus } from './PurchaseStatus';
import '../sales/sales.css';
import './purchasing.css';

const STEPS = ['DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL', 'RECEIVED'];

/** Supplier invoice match result in the badge style: matched (green) or with variances (amber). */
export function MatchBadge({ status }: { status: unknown }) {
  return status === 'VARIANCE' ? (
    <span className="badge amber">
      <i />
      variance
    </span>
  ) : (
    <span className="badge green">
      <i />
      matched
    </span>
  );
}

/** Quality decision of a receipt lot in words. */
const qcText = (r: Row) =>
  r.status === 'QUARANTINE'
    ? 'In quarantine, awaiting incoming QC'
    : r.status === 'RELEASED'
      ? 'Released to stock'
      : 'Rejected, returned to supplier';

/**
 * Purchase order side drawer: header, lifecycle, lines with received/returned progress, goods receipts with their QC
 * decision, supplier invoices with variances, supplier returns, notes and document activity, plus the actions the
 * signed-in role may take.
 */
export function PurchaseOrderDrawer({
  orderId,
  onClose,
  onEdit,
  onReceive,
  onInvoice,
}: {
  orderId: string;
  onClose: () => void;
  onEdit: (detail: PurchaseOrderDetail) => void;
  onReceive: (detail: PurchaseOrderDetail) => void;
  onInvoice: (detail: PurchaseOrderDetail) => void;
}) {
  const query = usePurchaseOrder(orderId);
  return (
    <Overlay
      title={text(query.data?.order.number) || 'Purchase order'}
      subtitle="Order lines, goods receipts, supplier invoices, returns and document history"
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
        <Detail data={query.data} onEdit={onEdit} onReceive={onReceive} onInvoice={onInvoice} />
      )}
    </Overlay>
  );
}

function Detail({
  data,
  onEdit,
  onReceive,
  onInvoice,
}: {
  data: PurchaseOrderDetail;
  onEdit: (detail: PurchaseOrderDetail) => void;
  onReceive: (detail: PurchaseOrderDetail) => void;
  onInvoice: (detail: PurchaseOrderDetail) => void;
}) {
  const workspace = useWorkspace();
  const { can, user } = workspace;
  const qc = useQueryClient();
  const { run, pending, error } = usePurchaseCommand();
  const o = data.order;
  const status = text(o.status);
  const ended = ['CANCELLED', 'CLOSED'].includes(status);
  const open = ['APPROVED', 'PARTIAL'].includes(status);
  const reached = data.receipts.length
    ? 'PARTIAL'
    : o.approved_at
      ? 'APPROVED'
      : o.submitted_at
        ? 'SUBMITTED'
        : 'DRAFT';
  const index = ended ? STEPS.indexOf(reached) : STEPS.indexOf(status);
  const steps = ended ? [...STEPS.slice(0, index + 1), status] : STEPS;
  const received = data.lines.reduce((s, l) => s + num(l.received), 0);
  const linesDone = data.lines.filter((l) => num(l.received) >= num(l.base_quantity)).length;
  const billed = data.invoices.reduce((s, i) => s + num(i.amount), 0);
  const variance = data.invoices.reduce((s, i) => s + num(i.price_variance), 0);
  const returnedValue = data.returns.reduce((s, r) => s + num(r.value_amount), 0);
  const invoiceable = data.receipts.some((r) => num(r.invoiceable) > 0);
  const self = user?.id === o.created_by || user?.id === o.submitted_by;
  const buyer = can('PLANNER', 'WAREHOUSE');
  const cancellable = ['DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL'].includes(status) && received === 0;
  const linesById = new Map(data.lines.map((l) => [text(l.id), l]));
  return (
    <>
      <div className="drawer-body sales-drawer purchase-drawer">
        <div className="detail-product">
          <span className="product-mark blue">
            <ShoppingCart size={20} />
          </span>
          <div>
            <h2>{text(o.supplier) || 'Supplier to be chosen'}</h2>
            <p>
              {o.supplier_reference ? `Ref ${text(o.supplier_reference)} · ` : ''}Expected {date(o.due_date)}{' '}
              · {data.lines.length} {data.lines.length === 1 ? 'line' : 'lines'}
            </p>
          </div>
          <PurchaseStatus status={o.status} />
        </div>
        {error && (
          <div className="form-error" role="alert">
            {error}
          </div>
        )}
        {o.supplier_id && o.supplier_active === false && !ended && (
          <div className="sales-notice warn">
            {text(o.supplier)} is inactive. Receipts and invoices of this order continue, but the order cannot
            be submitted or approved until the supplier is reactivated.
          </div>
        )}
        {status === 'DRAFT' && !o.supplier_id && (
          <div className="sales-notice warn">
            Created from material planning without a known supplier. Edit the draft and choose a supplier
            before submitting it.
          </div>
        )}
        {status === 'SUBMITTED' && (
          <div className="sales-notice">
            Submitted by {text(o.submitted_by_name)} on {date(o.submitted_at)}.{' '}
            {self
              ? 'You created or submitted this order, so another planner or administrator must approve it.'
              : 'Approval commits the order and adds it to material planning supply.'}
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
                ) : i < index || (i === index && (status === 'RECEIVED' || ended)) ? (
                  <Check size={12} />
                ) : (
                  i + 1
                )}
              </span>
              <small>{s === 'CLOSED' ? 'closed short' : statusLabel(s)}</small>
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
            <span>Lines received</span>
            <strong>
              {linesDone} <small>/ {data.lines.length}</small>
            </strong>
          </div>
          <div>
            <span>Invoiced</span>
            <strong>
              {fmt(billed, 0)} <small>LKR</small>
            </strong>
          </div>
          <div>
            <span>{data.returns.length ? 'Returned' : 'Price variance'}</span>
            <strong className={!data.returns.length && Math.abs(variance) > 0.004 ? 'overdue' : ''}>
              {fmt(data.returns.length ? returnedValue : variance, 0)} <small>LKR</small>
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
              label: 'Material',
              render: (l) => (
                <div className="cell-stack">
                  <strong>
                    {text(l.line_no)}. {text(l.item)}
                  </strong>
                  <span>
                    {text(l.sku)} · {money(l.unit_cost)} per {text(l.unit)}
                    {l.unit !== l.uom ? ` (${money(l.base_unit_cost)}/${text(l.uom)})` : ''}
                    {l.item_active === false ? ' · inactive' : ''}
                  </span>
                </div>
              ),
            },
            {
              label: 'Ordered',
              render: (l) => (
                <div className="cell-stack">
                  <strong>
                    {fmt(l.quantity, 3)} {text(l.unit)}
                  </strong>
                  {l.unit !== l.uom && (
                    <span>
                      = {fmt(l.base_quantity, 3)} {text(l.uom)}
                    </span>
                  )}
                </div>
              ),
            },
            {
              label: 'Received',
              render: (l) => (
                <div className="table-progress">
                  <span>
                    {fmt(l.received, 3)} <em>/ {fmt(l.base_quantity, 3)}</em> {text(l.uom)}
                    {num(l.awaiting_qc) > 0 && <em> · {fmt(l.awaiting_qc, 3)} in QC</em>}
                    {num(l.returned) > 0 && <em> · {fmt(l.returned, 3)} returned</em>}
                  </span>
                  <Progress value={(num(l.received) / num(l.base_quantity)) * 100} />
                </div>
              ),
            },
            {
              label: 'Expected',
              render: (l) => (
                <span
                  className={
                    open && num(l.outstanding) > 0 && text(l.expected).substring(0, 10) < today()
                      ? 'overdue'
                      : ''
                  }
                >
                  {date(l.expected)}
                </span>
              ),
            },
            { label: 'Line total', render: (l) => money(l.line_total) },
          ]}
        />

        <h3>Goods receipts</h3>
        {data.receipts.length ? (
          <Table
            rows={data.receipts}
            compact
            pageSize={100}
            columns={[
              {
                label: 'Receipt',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.number)}</strong>
                    <span>
                      {r.delivery_note ? `${text(r.delivery_note)} · ` : ''}line {text(r.line_no)} ·{' '}
                      {date(r.created_at)}
                    </span>
                  </div>
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
              {
                label: 'Quantity',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>
                      {fmt(r.quantity, 3)} {text(r.uom)}
                    </strong>
                    <span>
                      {r.entered_unit !== r.uom
                        ? `${fmt(r.entered_quantity, 3)} ${text(r.entered_unit)} · `
                        : ''}
                      {money(r.value_amount)}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Quality',
                render: (r) => (
                  <div className="cell-stack">
                    <Badge value={r.inspection_status || r.status} />
                    <span>{qcText(r)}</span>
                  </div>
                ),
              },
              {
                label: 'Invoiced / returned',
                render: (r) => (
                  <span className="muted">
                    {fmt(r.invoiced, 3)} / {fmt(r.returned, 3)} {text(r.uom)}
                  </span>
                ),
              },
              {
                label: '',
                render: (r) =>
                  num(r.returnable) > 0 &&
                  can('WAREHOUSE') && (
                    <button
                      className="button small secondary"
                      onClick={() => returnToSupplier(workspace, qc, r)}
                    >
                      <RotateCcw size={13} />
                      Return
                    </button>
                  ),
              },
            ]}
          />
        ) : (
          <p className="sales-empty-line">
            {open ? 'Nothing received yet.' : 'Goods can be received once the order is approved.'}
          </p>
        )}

        <h3>Supplier invoices</h3>
        {data.invoices.length ? (
          <div className="purchase-invoices">
            {data.invoices.map((inv) => {
              const lines = data.invoiceLines.filter((l) => l.invoice_id === inv.id);
              return (
                <div key={inv.id} className="purchase-invoice">
                  <div className="purchase-invoice-head">
                    <div className="cell-stack">
                      <strong>
                        {text(inv.supplier_reference)} <span className="muted">· {text(inv.number)}</span>
                      </strong>
                      <span>
                        Invoice date {date(inv.invoice_date)}
                        {inv.created_by_name ? ` · matched by ${text(inv.created_by_name)}` : ''}
                      </span>
                    </div>
                    <div className="purchase-invoice-amounts">
                      <strong>{money(inv.amount)}</strong>
                      <span>
                        at order price {money(inv.matched_amount)}
                        {Math.abs(num(inv.price_variance)) > 0.004 && (
                          <em className={num(inv.price_variance) > 0 ? 'negative' : 'positive'}>
                            {' '}
                            · variance {num(inv.price_variance) > 0 ? '+' : ''}
                            {money(inv.price_variance)}
                          </em>
                        )}
                      </span>
                    </div>
                    <MatchBadge status={inv.status} />
                  </div>
                  <ul className="purchase-invoice-lines">
                    {lines.map((l) => {
                      const priceOff = num(l.unit_price) !== num(l.po_unit_cost);
                      const short = num(l.quantity_variance) !== 0;
                      return (
                        <li key={l.id}>
                          <span>
                            {text(l.item)} · lot {text(l.lot)} (line {text(l.order_line_no)})
                          </span>
                          <span>
                            {fmt(l.quantity, 3)} {text(l.uom)} × {money(l.unit_price)}
                          </span>
                          <span className={priceOff || short ? 'negative' : 'muted'}>
                            {priceOff
                              ? `price ${money(l.unit_price)} vs ${money(l.po_unit_cost)}`
                              : short
                                ? ''
                                : 'matched'}
                            {priceOff && short ? ' · ' : ''}
                            {short ? `${fmt(-num(l.quantity_variance), 3)} ${text(l.uom)} not billed` : ''}
                          </span>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              );
            })}
          </div>
        ) : (
          <p className="sales-empty-line">
            {invoiceable
              ? 'Released receipts are waiting for the supplier invoice.'
              : 'No supplier invoices yet.'}
          </p>
        )}

        {data.returns.length > 0 && (
          <>
            <h3>Supplier returns</h3>
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
                        Debit note {text(r.debit_note)} · {date(r.created_at)}
                      </span>
                    </div>
                  ),
                },
                {
                  label: 'Quantity',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>
                        {fmt(r.quantity, 3)} {text(r.uom)}
                      </strong>
                      <span>
                        lot {text(r.lot)} · line{' '}
                        {text(linesById.get(text(r.purchase_order_line_id))?.line_no)}
                      </span>
                    </div>
                  ),
                },
                {
                  label: 'Value',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>{money(r.value_amount)}</strong>
                      <span>
                        {num(r.payable_amount) > 0
                          ? `${money(r.payable_amount)} against payables`
                          : 'clearing reversed'}
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
        {cancellable && (status === 'DRAFT' ? buyer : can('PLANNER')) && (
          <button
            className="button danger-ghost"
            disabled={!!pending}
            onClick={() => endPurchaseOrder(workspace, qc, o, 'cancel')}
          >
            {status === 'DRAFT' ? 'Discard draft' : 'Cancel order'}
          </button>
        )}
        {status === 'PARTIAL' && received > 0 && can('PLANNER') && (
          <button
            className="button danger-ghost"
            disabled={!!pending}
            onClick={() => endPurchaseOrder(workspace, qc, o, 'close')}
          >
            Close short
          </button>
        )}
        <span className="grow" />
        {status === 'DRAFT' && buyer && (
          <>
            <button className="button secondary" disabled={!!pending} onClick={() => onEdit(data)}>
              <Pencil size={15} />
              Edit draft
            </button>
            <button
              className="button primary"
              disabled={!!pending || !data.lines.length || !o.supplier_id}
              title={o.supplier_id ? undefined : 'Choose a supplier first'}
              onClick={() => run('submit', `/purchases/orders/${o.id}/submit`)}
            >
              {pending === 'submit' ? <LoaderCircle size={15} className="spin" /> : <Send size={15} />}
              Submit for approval
            </button>
          </>
        )}
        {status === 'SUBMITTED' && can('PLANNER') && !self && (
          <button
            className="button primary"
            disabled={!!pending}
            onClick={() => run('approve', `/purchases/orders/${o.id}/approve`)}
          >
            {pending === 'approve' ? <LoaderCircle size={15} className="spin" /> : <CheckCheck size={15} />}
            Approve order
            <ArrowRight size={15} />
          </button>
        )}
        {invoiceable && can() && (
          <button className="button secondary" disabled={!!pending} onClick={() => onInvoice(data)}>
            <FileCheck2 size={15} />
            Match invoice
          </button>
        )}
        {open && can('WAREHOUSE') && (
          <button className="button primary" disabled={!!pending} onClick={() => onReceive(data)}>
            <PackageCheck size={15} />
            Receive goods
          </button>
        )}
        {open && !can('WAREHOUSE') && (
          <span className="muted">The warehouse receives deliveries into quarantine.</span>
        )}
      </div>
    </>
  );
}
