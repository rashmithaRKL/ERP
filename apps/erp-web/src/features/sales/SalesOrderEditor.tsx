import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { CheckCheck, LoaderCircle, ShoppingBag } from 'lucide-react';
import { useStateData, useWorkspace } from '../../app/workspace';
import { Overlay } from '../../components';
import { LineItemsEditor } from '../../components/LineItemsEditor';
import { lineNet, newLine } from '../../components/lineItems';
import type { LineItem } from '../../components/lineItems';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { fmt, money, num, text, today } from '../../lib/format';
import { invalidateSales } from './api';
import type { SalesOrderDetail } from './api';
import './sales.css';

const plain = (x: unknown) => (x === null || x === undefined || x === '' ? '' : String(num(x)));

/**
 * Create a draft sales order or edit a draft (header and lines). "Save & confirm" saves, then confirms; a credit-limit
 * warning from the server is shown as a toast and does not block the order.
 */
export function SalesOrderEditor({
  detail,
  onClose,
  onSaved,
}: {
  /** The draft being edited; omit to create a new order. */
  detail?: SalesOrderDetail;
  onClose: () => void;
  /** Called with the order id after a successful save (and confirmation). */
  onSaved: (id: string) => void;
}) {
  const data = useStateData();
  const { refresh, notify } = useWorkspace();
  const qc = useQueryClient();
  const order = detail?.order;
  const [orderId, setOrderId] = useState(order ? text(order.id) : '');
  const [customerId, setCustomerId] = useState(text(order?.customer_id));
  const [reference, setReference] = useState(text(order?.customer_reference));
  const [dueDate, setDueDate] = useState(order ? text(order.due_date).substring(0, 10) : today());
  const [notes, setNotes] = useState(text(order?.notes));
  const [lines, setLines] = useState<LineItem[]>(() =>
    detail?.lines.length
      ? detail.lines.map((l) =>
          newLine({
            id: text(l.id),
            itemId: text(l.product_id),
            quantity: plain(l.quantity),
            unitPrice: plain(l.unit_price),
            discountPct: num(l.discount_pct) ? plain(l.discount_pct) : '',
          }),
        )
      : [newLine()],
  );
  const [pending, setPending] = useState('');
  const [error, setError] = useState('');
  // One idempotency key per action and form content: a retry after an error reuses it, an edit starts a new one.
  const keys = useRef<Record<string, string>>({});
  const key = (action: string) => (keys.current[action] ??= crypto.randomUUID());
  const touched = () => {
    keys.current = {};
  };

  const customers = data.partners.filter(
    (p) => p.type === 'CUSTOMER' && (p.active !== false || p.id === order?.customer_id),
  );
  const customer = data.partners.find((p) => p.id === customerId);
  const products = data.items
    .filter((i) => i.type === 'FINISHED_GOOD' && (i.active !== false || lines.some((l) => l.itemId === i.id)))
    .map((i) => ({ value: i.id, label: `${text(i.name)} · ${text(i.sku)}`, unit: text(i.uom) }));
  const total = lines.reduce((sum, l) => sum + lineNet(l), 0);

  async function save(e: FormEvent<HTMLFormElement>, confirm: boolean) {
    e.preventDefault();
    if (pending) return;
    setError('');
    setPending(confirm ? 'confirm' : 'save');
    const body = {
      customerId,
      customerReference: reference.trim() || null,
      dueDate,
      notes: notes.trim(),
      lines: lines.map((l) => ({
        id: l.id ?? null,
        productId: l.itemId,
        quantity: l.quantity,
        unitPrice: l.unitPrice || '0',
        discountPct: l.discountPct || '0',
      })),
    };
    try {
      let id = orderId;
      if (id) await request(`/sales/orders/${id}`, body, key(`update:${id}`));
      else {
        const created = await request<Row>('/sales/orders', body, key('create'));
        id = text(created.id);
        setOrderId(id);
      }
      let warnings: string[] = [];
      if (confirm) {
        const result = await request<{ warnings?: string[] }>(
          `/sales/orders/${id}/confirm`,
          {},
          key(`confirm:${id}`),
        );
        warnings = result.warnings || [];
      }
      await Promise.all([invalidateSales(qc), refresh()]);
      notify(warnings.length ? warnings.join(' ') : 'Transaction posted successfully.');
      onSaved(id);
      onClose();
    } catch (err) {
      // Keys are kept: retrying an unchanged form replays a command whose response was lost. If the draft was
      // saved but confirmation failed, `orderId` is set and the next attempt updates that draft.
      setError((err as Error).message);
    } finally {
      setPending('');
    }
  }

  const submitter = useRef<'save' | 'confirm'>('save');
  return (
    <Overlay
      title={order ? `Edit draft ${text(order.number)}` : 'New sales order'}
      subtitle="Build the customer order line by line. Drafts stay editable until they are confirmed."
      onClose={() => {
        if (!pending) onClose();
      }}
      wide
    >
      <form className="sales-editor" onSubmit={(e) => save(e, submitter.current === 'confirm')}>
        <div className="form-fields">
          <label className="field">
            <span>Customer</span>
            <select
              required
              value={customerId}
              onChange={(e) => {
                setCustomerId(e.target.value);
                touched();
              }}
            >
              <option value="" disabled>
                Select customer
              </option>
              {customers.map((p) => (
                <option key={p.id} value={p.id}>
                  {text(p.name)}
                  {p.active === false ? ' (inactive)' : ''}
                </option>
              ))}
            </select>
            {customer && (
              <em>
                Net {text(customer.payment_days)} days
                {customer.credit_limit !== null && customer.credit_limit !== undefined
                  ? ` · credit limit ${money(customer.credit_limit)}`
                  : ' · no credit limit'}
              </em>
            )}
          </label>
          <label className="field">
            <span>
              Customer PO / reference<small>Optional</small>
            </span>
            <input
              maxLength={80}
              value={reference}
              placeholder="e.g. PO-4471"
              onChange={(e) => {
                setReference(e.target.value);
                touched();
              }}
            />
          </label>
          <label className="field">
            <span>Required by</span>
            <input
              required
              type="date"
              value={dueDate}
              onChange={(e) => {
                setDueDate(e.target.value);
                touched();
              }}
            />
          </label>
          <label className="field">
            <span>
              Notes<small>Optional</small>
            </span>
            <textarea
              rows={2}
              maxLength={2000}
              value={notes}
              onChange={(e) => {
                setNotes(e.target.value);
                touched();
              }}
            />
          </label>
        </div>
        <div className="sales-editor-lines">
          <h3>
            <ShoppingBag size={15} />
            Order lines
          </h3>
          <LineItemsEditor
            lines={lines}
            onChange={(next) => {
              setLines(next);
              touched();
            }}
            options={products}
            itemLabel="Product"
            emptyOption="Select a finished product"
            disabled={!!pending}
          />
          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <span className="muted sales-editor-total">
            {lines.length} {lines.length === 1 ? 'line' : 'lines'} · Rs. {fmt(total, 2)}
          </span>
          <span className="grow" />
          <button type="button" className="button secondary" onClick={onClose} disabled={!!pending}>
            Cancel
          </button>
          <button
            className="button secondary"
            disabled={!!pending}
            onClick={() => {
              submitter.current = 'save';
            }}
          >
            {pending === 'save' && <LoaderCircle className="spin" size={15} />}
            Save draft
          </button>
          <button
            className="button primary"
            disabled={!!pending || !lines.length}
            onClick={() => {
              submitter.current = 'confirm';
            }}
          >
            {pending === 'confirm' ? <LoaderCircle className="spin" size={15} /> : <CheckCheck size={15} />}
            Save & confirm
          </button>
        </div>
      </form>
    </Overlay>
  );
}
