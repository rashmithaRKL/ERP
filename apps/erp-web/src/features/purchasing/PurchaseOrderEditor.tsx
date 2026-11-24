import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { CalendarClock, LoaderCircle, Send, ShoppingCart } from 'lucide-react';
import { useStateData, useWorkspace } from '../../app/workspace';
import { Overlay } from '../../components';
import { LineItemsEditor } from '../../components/LineItemsEditor';
import { lineNet, newLine } from '../../components/lineItems';
import type { LineItem } from '../../components/lineItems';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { fmt, money, num, text, today } from '../../lib/format';
import { invalidatePurchasing, purchaseUnits, useItemUnits, usePurchaseSources } from './api';
import type { ItemUnits, PurchaseOrderDetail } from './api';
import '../sales/sales.css';
import './purchasing.css';

const plain = (x: unknown) => (x === null || x === undefined || x === '' ? '' : String(num(x)));

/** Base units in one `unit` of the item (1 for the base unit). */
const factorOf = (units: ItemUnits | undefined, unit: string) =>
  num(units?.units.find((u) => u.code === unit)?.factor ?? 1) || 1;

/**
 * Create a draft purchase order or edit a draft: supplier (optional on a draft), supplier reference, expected date,
 * notes and lines in purchase units (the base unit, conversions flagged for purchasing or same-kind standard units),
 * each with its own expected date. "Save & submit" saves, then sends the order for independent approval.
 */
export function PurchaseOrderEditor({
  detail,
  material,
  onClose,
  onSaved,
}: {
  /** The draft being edited; omit to create a new order. */
  detail?: PurchaseOrderDetail;
  /** An MRP shortage row (Planning "Create purchase"): prefills one line and the item's preferred supplier. */
  material?: Row;
  onClose: () => void;
  onSaved: (id: string) => void;
}) {
  const data = useStateData();
  const { refresh, notify } = useWorkspace();
  const qc = useQueryClient();
  const order = detail?.order;
  const materialItem = material ? data.items.find((i) => i.id === material.id) : undefined;
  const [orderId, setOrderId] = useState(order ? text(order.id) : '');
  const [supplierId, setSupplierId] = useState(text(order?.supplier_id));
  const [reference, setReference] = useState(text(order?.supplier_reference));
  const [dueDate, setDueDate] = useState(order ? text(order.due_date).substring(0, 10) : today());
  const [notes, setNotes] = useState(text(order?.notes));
  const [lines, setLines] = useState<LineItem[]>(() =>
    detail?.lines.length
      ? detail.lines.map((l) =>
          newLine({
            id: text(l.id),
            itemId: text(l.item_id),
            quantity: plain(l.quantity),
            unitPrice: plain(l.unit_cost),
            unit: text(l.unit),
          }),
        )
      : material
        ? [
            newLine({
              itemId: text(material.id),
              quantity: String(
                material.uom === 'pcs'
                  ? Math.ceil(num(material.shortage))
                  : Math.ceil(num(material.shortage) * 1000) / 1000,
              ),
              unitPrice: materialItem ? plain(materialItem.standard_cost) : '',
            }),
          ]
        : [newLine()],
  );
  const [expected, setExpected] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      (detail?.lines || [])
        .map((l, i) => [lines[i]?.key, l.expected_date ? text(l.expected_date).substring(0, 10) : ''])
        .filter(([, value]) => value),
    ),
  );
  const [pending, setPending] = useState('');
  const [error, setError] = useState('');
  // One idempotency key per action and form content: a retry after an error reuses it, an edit starts a new one.
  const keys = useRef<Record<string, string>>({});
  const key = (action: string) => (keys.current[action] ??= crypto.randomUUID());
  const touched = () => {
    keys.current = {};
  };

  // Planning shortages: suggest the supplier the material was last bought from.
  const sources = usePurchaseSources(material && !order ? [text(material.id)] : []);
  const suggested = sources.data?.items[0];
  useEffect(() => {
    if (suggested?.supplier_id && !supplierId) setSupplierId(text(suggested.supplier_id));
    if (suggested?.base_unit_cost)
      setLines((current) =>
        current.map((l, i) => (i === 0 && !l.id ? { ...l, unitPrice: plain(suggested.base_unit_cost) } : l)),
      );
  }, [suggested?.supplier_id, suggested?.base_unit_cost]); // once, when the suggestion arrives

  const units = useItemUnits(lines.map((l) => l.itemId));
  const suppliers = data.partners.filter(
    (p) => p.type === 'SUPPLIER' && (p.active !== false || p.id === order?.supplier_id),
  );
  const supplier = data.partners.find((p) => p.id === supplierId);
  const items = data.items
    .filter((i) => i.type !== 'FINISHED_GOOD' && (i.active !== false || lines.some((l) => l.itemId === i.id)))
    .map((i) => ({
      value: i.id,
      label: `${text(i.name)} · ${text(i.sku)}${i.active === false ? ' (inactive)' : ''}`,
      unit: text(i.uom),
      price: plain(i.standard_cost),
    }));
  const total = lines.reduce((sum, l) => sum + lineNet(l), 0);

  /** Keeps the price per entered unit consistent when a line switches unit (Rs 480/kg → Rs 12,000/bag). */
  function change(next: LineItem[]) {
    setLines(
      next.map((l) => {
        const before = lines.find((p) => p.key === l.key);
        if (!before || before.itemId !== l.itemId || (before.unit || '') === (l.unit || '') || !l.unitPrice)
          return l;
        const itemUnits = units.get(l.itemId);
        const from = factorOf(itemUnits, before.unit || itemUnits?.baseUnit || '');
        const to = factorOf(itemUnits, l.unit || itemUnits?.baseUnit || '');
        return { ...l, unitPrice: String(Math.round((num(l.unitPrice) / from) * to * 1e6) / 1e6) };
      }),
    );
    touched();
  }

  async function save(e: FormEvent<HTMLFormElement>, submit: boolean) {
    e.preventDefault();
    if (pending) return;
    setError('');
    if (submit && !supplierId) return setError('Choose a supplier before submitting the order for approval.');
    setPending(submit ? 'submit' : 'save');
    const body = {
      supplierId: supplierId || null,
      supplierReference: reference.trim() || null,
      dueDate,
      notes: notes.trim(),
      lines: lines.map((l) => ({
        id: l.id ?? null,
        itemId: l.itemId,
        quantity: l.quantity,
        unit: l.unit || null,
        unitCost: l.unitPrice || '0',
        expectedDate: expected[l.key] || null,
      })),
    };
    try {
      let id = orderId;
      if (id) await request(`/purchases/orders/${id}`, body, key(`update:${id}`));
      else {
        const created = await request<Row>('/purchases/orders', body, key('create'));
        id = text(created.id);
        setOrderId(id);
      }
      const submitted = submit
        ? await request<Row>(`/purchases/orders/${id}/submit`, {}, key(`submit:${id}`))
        : undefined;
      await Promise.all([invalidatePurchasing(qc), refresh()]);
      notify(
        !submitted
          ? 'Draft saved successfully.'
          : submitted.status === 'APPROVED'
            ? 'Purchase order submitted successfully and auto-approved by policy; goods can be received.'
            : 'Purchase order submitted successfully; it now waits for an independent approver.',
      );
      onSaved(id);
      onClose();
    } catch (err) {
      // Keys are kept: retrying an unchanged form replays a command whose response was lost. If the draft was
      // saved but submission failed, `orderId` is set and the next attempt updates that draft.
      setError((err as Error).message);
    } finally {
      setPending('');
    }
  }

  const submitter = useRef<'save' | 'submit'>('save');
  const scheduled = lines.filter((l) => l.itemId);
  return (
    <Overlay
      title={order ? `Edit draft ${text(order.number)}` : 'New purchase order'}
      subtitle="Order materials line by line in the units you buy them in. Drafts stay editable until they are submitted."
      onClose={() => {
        if (!pending) onClose();
      }}
      wide
    >
      <form
        className="sales-editor purchase-editor"
        onSubmit={(e) => save(e, submitter.current === 'submit')}
      >
        <div className="form-fields">
          <label className="field">
            <span>
              Supplier<small>Required to submit</small>
            </span>
            <select
              value={supplierId}
              onChange={(e) => {
                setSupplierId(e.target.value);
                touched();
              }}
            >
              <option value="">Choose later</option>
              {suppliers.map((p) => (
                <option key={p.id} value={p.id}>
                  {text(p.name)}
                  {p.active === false ? ' (inactive)' : ''}
                </option>
              ))}
            </select>
            {supplier ? (
              <em>
                Net {text(supplier.payment_days)} days{supplier.email ? ` · ${text(supplier.email)}` : ''}
                {material && suggested?.supplier_id === supplierId ? ' · last supplier of this material' : ''}
              </em>
            ) : (
              <em>Only active suppliers are listed.</em>
            )}
          </label>
          <label className="field">
            <span>
              Supplier quotation / reference<small>Optional</small>
            </span>
            <input
              maxLength={80}
              value={reference}
              placeholder="e.g. Q-2611-04"
              onChange={(e) => {
                setReference(e.target.value);
                touched();
              }}
            />
          </label>
          <label className="field">
            <span>Expected delivery</span>
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
            <ShoppingCart size={15} />
            Order lines
          </h3>
          <LineItemsEditor
            lines={lines}
            onChange={change}
            options={items}
            itemLabel="Material"
            priceLabel="Unit cost"
            discount={false}
            emptyOption="Select a material"
            disabled={!!pending}
            units={(line) => {
              const choices = purchaseUnits(units.get(line.itemId));
              return choices.length
                ? choices.map((u) => ({ code: u.code, decimals: u.decimals }))
                : undefined;
            }}
          />
          {scheduled.length > 0 && (
            <div className="purchase-schedule">
              <h4>
                <CalendarClock size={14} />
                Base quantities and delivery dates
              </h4>
              {scheduled.map((l) => {
                const itemUnits = units.get(l.itemId);
                const base = itemUnits?.baseUnit || items.find((i) => i.value === l.itemId)?.unit || '';
                const unit = l.unit || base;
                const factor = factorOf(itemUnits, unit);
                const n = lines.indexOf(l) + 1;
                return (
                  <div key={l.key} className="purchase-schedule-row">
                    <span className="cell-stack">
                      <strong>
                        {n}. {items.find((i) => i.value === l.itemId)?.label.split(' · ')[0] || 'Material'}
                      </strong>
                      <span>
                        {unit !== base && num(l.quantity) > 0
                          ? `${fmt(l.quantity, 6)} ${unit} = ${fmt(num(l.quantity) * factor, 6)} ${base} · `
                          : ''}
                        {num(l.unitPrice) > 0
                          ? `${money(num(l.unitPrice) / factor)} per ${base}`
                          : 'No cost yet'}
                      </span>
                    </span>
                    <label>
                      <input
                        type="date"
                        value={expected[l.key] || ''}
                        placeholder={dueDate}
                        aria-label={`Expected date of line ${n}`}
                        onChange={(e) => {
                          setExpected({ ...expected, [l.key]: e.target.value });
                          touched();
                        }}
                      />
                      <small>{expected[l.key] ? 'Own date' : 'Order date'}</small>
                    </label>
                  </div>
                );
              })}
            </div>
          )}
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
              submitter.current = 'submit';
            }}
          >
            {pending === 'submit' ? <LoaderCircle className="spin" size={15} /> : <Send size={15} />}
            Save & submit
          </button>
        </div>
      </form>
    </Overlay>
  );
}
