import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { FileCheck2, LoaderCircle } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Overlay } from '../../components';
import { request } from '../../lib/api';
import { fmt, money, num, text, today } from '../../lib/format';
import { invalidatePurchasing } from './api';
import type { PurchaseOrderDetail } from './api';
import '../sales/sales.css';
import './purchasing.css';

type Entry = { include: boolean; quantity: string; price: string };

/** Rounds like the server (6 decimal places, half up) for the preview. */
const round6 = (x: number) => Math.round(x * 1e6) / 1e6;

/**
 * Match a supplier invoice to quality-released receipts of the order. Each line bills a base quantity at a price per
 * base unit; the preview flags the quantity not billed (it stays open for a later invoice) and the price difference
 * against the order, which posts as a purchase price variance. Billing more than was received is refused.
 */
export function InvoiceDialog({ detail, onClose }: { detail: PurchaseOrderDetail; onClose: () => void }) {
  const qc = useQueryClient();
  const { refresh, notify } = useWorkspace();
  const order = detail.order;
  const candidates = detail.receipts.filter((r) => num(r.invoiceable) > 0);
  const [reference, setReference] = useState('');
  const [invoiceDate, setInvoiceDate] = useState(today());
  const [notes, setNotes] = useState('');
  const [entries, setEntries] = useState<Record<string, Entry>>(() =>
    Object.fromEntries(
      candidates.map((r) => [
        text(r.id),
        { include: true, quantity: String(num(r.invoiceable)), price: String(num(r.unit_cost)) },
      ]),
    ),
  );
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const key = useRef(crypto.randomUUID());
  const touched = () => {
    key.current = crypto.randomUUID();
  };
  const set = (id: string, patch: Partial<Entry>) => {
    setEntries({ ...entries, [id]: { ...entries[id], ...patch } });
    touched();
  };
  const rows = candidates.map((r) => {
    const e = entries[text(r.id)];
    const quantity = num(e.quantity);
    const price = num(e.price);
    const billed = round6(quantity * price);
    const atOrder = round6(quantity * num(r.unit_cost));
    return {
      r,
      e,
      quantity,
      billed,
      atOrder,
      short: num(r.invoiceable) - quantity,
      priceOff: price !== num(r.unit_cost),
    };
  });
  const chosen = rows.filter((x) => x.e.include && x.quantity > 0);
  const billed = chosen.reduce((s, x) => s + x.billed, 0);
  const atOrder = chosen.reduce((s, x) => s + x.atOrder, 0);
  const over = chosen.filter((x) => x.short < -1e-9);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!chosen.length) return setError('Include at least one receipt with a quantity.');
    if (over.length) return setError('An invoice line bills more than was received and not yet invoiced.');
    setPending(true);
    setError('');
    try {
      const result = await request<{ status: string; priceVariance: number }>(
        `/purchases/orders/${order.id}/invoices`,
        {
          supplierReference: reference.trim(),
          invoiceDate,
          notes: notes.trim() || null,
          lines: chosen.map((x) => ({ receiptId: x.r.id, quantity: x.e.quantity, unitPrice: x.e.price })),
        },
        key.current,
      );
      await Promise.all([invalidatePurchasing(qc), refresh()]);
      notify(
        result.status === 'VARIANCE'
          ? `Invoice matched successfully with variances (price variance ${money(result.priceVariance)}).`
          : 'Invoice matched successfully without variance.',
      );
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }
  return (
    <Overlay
      title={`Match supplier invoice · ${text(order.number)}`}
      subtitle={`${text(order.supplier)}. Goods-received clearing is released at the order price; accounts payable carries what the supplier billed.`}
      onClose={() => {
        if (!pending) onClose();
      }}
      wide
    >
      <form onSubmit={submit} className="purchase-invoice-form">
        <div className="form-fields">
          <label className="field">
            <span>Supplier invoice number</span>
            <input
              required
              maxLength={80}
              value={reference}
              placeholder="As printed on the invoice"
              onChange={(e) => {
                setReference(e.target.value);
                touched();
              }}
            />
          </label>
          <label className="field">
            <span>Invoice date</span>
            <input
              required
              type="date"
              value={invoiceDate}
              onChange={(e) => {
                setInvoiceDate(e.target.value);
                touched();
              }}
            />
          </label>
          <label className="field purchase-wide-field">
            <span>
              Notes<small>Optional</small>
            </span>
            <input
              maxLength={2000}
              value={notes}
              onChange={(e) => {
                setNotes(e.target.value);
                touched();
              }}
            />
          </label>
        </div>
        <div className="dispatch-lots">
          <div className="dispatch-lots-head">
            <h3>
              <FileCheck2 size={15} />
              Released receipts to match
            </h3>
          </div>
          {candidates.length ? (
            <div className="purchase-match-list">
              {rows.map(({ r, e, short, priceOff, billed: amount, atOrder: expected }) => (
                <div key={r.id} className={`purchase-match ${e.include ? '' : 'excluded'}`}>
                  <label className="purchase-match-pick">
                    <input
                      type="checkbox"
                      checked={e.include}
                      disabled={pending}
                      onChange={(ev) => set(text(r.id), { include: ev.target.checked })}
                    />
                    <span className="cell-stack">
                      <strong>
                        {text(r.item)} · lot {text(r.lot)}
                      </strong>
                      <span>
                        {text(r.number)} · {fmt(r.invoiceable, 3)} {text(r.uom)} to invoice at{' '}
                        {money(r.unit_cost)}/{text(r.uom)}
                      </span>
                    </span>
                  </label>
                  <label className="field">
                    <span>Billed ({text(r.uom)})</span>
                    <input
                      type="number"
                      inputMode="decimal"
                      min="0"
                      step={r.uom === 'pcs' ? '1' : '0.000001'}
                      value={e.quantity}
                      disabled={pending || !e.include}
                      aria-invalid={short < -1e-9 || undefined}
                      onChange={(ev) => set(text(r.id), { quantity: ev.target.value })}
                    />
                  </label>
                  <label className="field">
                    <span>Price per {text(r.uom)}</span>
                    <input
                      type="number"
                      inputMode="decimal"
                      min="0"
                      step="0.000001"
                      value={e.price}
                      disabled={pending || !e.include}
                      onChange={(ev) => set(text(r.id), { price: ev.target.value })}
                    />
                  </label>
                  <div className="purchase-match-result">
                    <strong>{money(amount)}</strong>
                    {e.include && (short > 1e-9 || priceOff || short < -1e-9) ? (
                      <span className="negative">
                        {short < -1e-9
                          ? 'more than received'
                          : [
                              short > 1e-9 ? `${fmt(short, 3)} ${text(r.uom)} not billed` : '',
                              priceOff
                                ? `${amount - expected > 0 ? '+' : ''}${money(amount - expected)}`
                                : '',
                            ]
                              .filter(Boolean)
                              .join(' · ')}
                      </span>
                    ) : (
                      <span className="positive">{e.include ? 'matches order' : 'not on this invoice'}</span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <Empty
              title="Nothing to invoice"
              body="Receipts can be matched once incoming quality control releases them."
            />
          )}
          <div className="purchase-match-total">
            <span>
              Billed <strong>{money(billed)}</strong>
            </span>
            <span>
              At order price <strong>{money(atOrder)}</strong>
            </span>
            <span className={Math.abs(billed - atOrder) > 0.004 ? 'negative' : ''}>
              Price variance{' '}
              <strong>
                {billed - atOrder > 0 ? '+' : ''}
                {money(billed - atOrder)}
              </strong>
            </span>
          </div>
          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <span className="grow" />
          <button type="button" className="button secondary" onClick={onClose} disabled={pending}>
            Cancel
          </button>
          <button className="button primary" disabled={pending || !candidates.length}>
            {pending ? <LoaderCircle className="spin" size={15} /> : <FileCheck2 size={15} />}
            Match & post invoice
          </button>
        </div>
      </form>
    </Overlay>
  );
}
