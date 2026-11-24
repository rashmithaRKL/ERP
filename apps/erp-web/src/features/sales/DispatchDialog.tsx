import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { LoaderCircle, Truck, Wand2 } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Overlay } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, fmt, money, num, text } from '../../lib/format';
import { invalidateSales } from './api';
import './sales.css';

/**
 * Dispatch one sales-order line from one or more released lots under a single delivery-note reference. Each lot
 * becomes one delivery with its own revenue/receivable and cost-of-goods-sold postings. Quality-held lots are not
 * offered (and are rejected by the server).
 */
export function DispatchDialog({
  order,
  line,
  lots,
  deliveries,
  onClose,
}: {
  order: Row;
  line: Row;
  /** Released lots with unreserved stock (any product; filtered to the line's product here), FIFO order. */
  lots: Row[];
  /** Number of deliveries already posted for the order, for the suggested reference. */
  deliveries: number;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const { refresh, notify } = useWorkspace();
  const candidates = lots.filter((l) => l.item_id === line.product_id);
  // Dispatch is in the base unit; the line may have been entered in a sales unit (V9).
  const outstanding = num(line.base_quantity ?? line.quantity) - num(line.delivered);
  const whole = line.uom === 'pcs';
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [reference, setReference] = useState(
    `DN-${text(order.number).replace(/^SO-/, '')}-${String(deliveries + 1).padStart(2, '0')}`,
  );
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const key = useRef(crypto.randomUUID());
  const selected = candidates
    .map((l) => ({ lotId: text(l.id), quantity: quantities[text(l.id)] || '' }))
    .filter((a) => num(a.quantity) > 0);
  const total = selected.reduce((sum, a) => sum + num(a.quantity), 0);
  const over = total > outstanding + 1e-9;

  function change(lotId: string, value: string) {
    setQuantities((q) => ({ ...q, [lotId]: value }));
    key.current = crypto.randomUUID();
  }
  function fifo() {
    let left = outstanding;
    const next: Record<string, string> = {};
    for (const l of candidates) {
      if (left <= 0) break;
      const take = Math.min(left, num(l.available));
      if (take > 0) next[text(l.id)] = String(whole ? Math.floor(take) : take);
      left -= take;
    }
    setQuantities(next);
    key.current = crypto.randomUUID();
  }
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!selected.length) return setError('Enter a quantity for at least one lot.');
    if (over) return setError('The dispatch exceeds the outstanding quantity of this line.');
    setPending(true);
    setError('');
    try {
      await request(
        `/sales/orders/${order.id}/lines/${line.id}/dispatch`,
        { reference: reference.trim(), lots: selected },
        key.current,
      );
      await Promise.all([invalidateSales(qc), refresh()]);
      notify('Transaction posted successfully.');
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }
  return (
    <Overlay
      title={`Dispatch line ${text(line.line_no)} · ${text(line.product)}`}
      subtitle={`${text(order.number)} · ${text(order.customer)} · ${fmt(outstanding, 3)} ${text(line.uom)} outstanding. Revenue and cost of goods sold are posted per lot.`}
      onClose={() => {
        if (!pending) onClose();
      }}
      wide
    >
      <form onSubmit={submit}>
        <div className="form-fields">
          <label className="field">
            <span>Delivery note reference</span>
            <input
              required
              maxLength={120}
              value={reference}
              onChange={(e) => {
                setReference(e.target.value);
                key.current = crypto.randomUUID();
              }}
            />
          </label>
          <div className="field">
            <span>Net unit price</span>
            <div className="dispatch-price">
              {money(num(line.base_unit_price ?? line.unit_price) * (1 - num(line.discount_pct) / 100))}
              {line.unit && line.unit !== line.uom && <small> per {text(line.uom)}</small>}
              {num(line.discount_pct) > 0 && <small> after {fmt(line.discount_pct, 2)}% discount</small>}
            </div>
          </div>
        </div>
        <div className="dispatch-lots">
          <div className="dispatch-lots-head">
            <h3>
              <Truck size={15} />
              Released lots
            </h3>
            {candidates.length > 0 && (
              <button type="button" className="text-button" onClick={fifo} disabled={pending}>
                <Wand2 size={13} />
                Fill oldest first
              </button>
            )}
          </div>
          {candidates.length ? (
            <div className="dispatch-lot-list">
              {candidates.map((l) => (
                <label key={l.id} className="dispatch-lot">
                  <span className="cell-stack">
                    <strong>{text(l.number)}</strong>
                    <span>
                      {text(l.warehouse)} · received {date(l.created_at)}
                    </span>
                  </span>
                  <span className="dispatch-lot-available">
                    {fmt(l.available, 3)} {text(line.uom)}
                    <small>available</small>
                  </span>
                  <input
                    type="number"
                    inputMode="decimal"
                    min="0"
                    max={String(num(l.available))}
                    step={whole ? '1' : '0.000001'}
                    placeholder="0"
                    aria-label={`Quantity from lot ${text(l.number)}`}
                    value={quantities[text(l.id)] || ''}
                    disabled={pending}
                    onChange={(e) => change(text(l.id), e.target.value)}
                  />
                </label>
              ))}
            </div>
          ) : (
            <Empty
              title="No released stock of this product"
              body="Quality-held and reserved stock is excluded. Plan production or release inspected lots first."
            />
          )}
          <div className={`dispatch-total ${over ? 'overdue' : ''}`}>
            Dispatching <strong>{fmt(total, 3)}</strong> of {fmt(outstanding, 3)} {text(line.uom)}
          </div>
          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <button type="button" className="button secondary" onClick={onClose} disabled={pending}>
            Cancel
          </button>
          <button className="button primary" disabled={pending || !candidates.length}>
            {pending ? <LoaderCircle className="spin" size={15} /> : <Truck size={15} />}
            Post dispatch & invoice
          </button>
        </div>
      </form>
    </Overlay>
  );
}
