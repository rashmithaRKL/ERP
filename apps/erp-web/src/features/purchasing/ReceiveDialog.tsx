import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { LoaderCircle, PackageCheck, Wand2 } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Overlay } from '../../components';
import { UnitQuantityInput } from '../../components/UnitQuantityInput';
import type { UnitQuantity } from '../../components/UnitQuantityInput';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, fmt, num, text } from '../../lib/format';
import { invalidatePurchasing } from './api';
import type { PurchaseOrderDetail } from './api';
import '../products/products.css';
import '../sales/sales.css';
import './purchasing.css';

/** The open quantity of a line in its purchase unit when that is exact, else in the base unit. */
function outstandingEntry(line: Row): UnitQuantity {
  const open = num(line.outstanding);
  const inUnit = open / num(line.factor);
  const decimals = num(line.unit_decimals);
  const scaled = inUnit * 10 ** decimals;
  const base = String(Math.round(open * 1e6) / 1e6);
  if (line.unit !== line.uom && Math.abs(scaled - Math.round(scaled)) < 1e-6)
    return {
      quantity: String(Math.round(scaled) / 10 ** decimals),
      unit: text(line.unit),
      baseQuantity: base,
    };
  return { quantity: base, unit: text(line.uom), baseQuantity: base };
}

/**
 * Goods receipt: one or more open lines of an approved order delivered under one delivery note into an active
 * raw-material warehouse. Each line becomes its own quarantined lot with an incoming inspection.
 */
export function ReceiveDialog({ detail, onClose }: { detail: PurchaseOrderDetail; onClose: () => void }) {
  const qc = useQueryClient();
  const { refresh, notify } = useWorkspace();
  const order = detail.order;
  const open = detail.lines.filter((l) => num(l.outstanding) > 0);
  const [warehouseId, setWarehouseId] = useState(text(detail.warehouses[0]?.id));
  const [deliveryNote, setDeliveryNote] = useState('');
  const [entries, setEntries] = useState<Record<string, UnitQuantity>>({});
  const [lots, setLots] = useState<Record<string, string>>({});
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const key = useRef(crypto.randomUUID());
  const touched = () => {
    key.current = crypto.randomUUID();
  };
  const entry = (l: Row) => entries[text(l.id)] || { quantity: '', unit: text(l.unit) };
  const selected = open.filter((l) => num(entry(l).quantity) > 0);
  const over = selected.filter((l) => {
    const e = entry(l);
    const base = e.baseQuantity !== undefined ? num(e.baseQuantity) : num(e.quantity);
    return base > num(l.outstanding) + 1e-9;
  });

  function fill() {
    setEntries(Object.fromEntries(open.map((l) => [text(l.id), outstandingEntry(l)])));
    touched();
  }
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!selected.length) return setError('Enter a received quantity for at least one line.');
    if (over.length)
      return setError(
        `Line ${over.map((l) => text(l.line_no)).join(', ')} exceeds the outstanding quantity.`,
      );
    setPending(true);
    setError('');
    try {
      await request(
        `/purchases/orders/${order.id}/receipts`,
        {
          warehouseId,
          deliveryNote: deliveryNote.trim(),
          lines: selected.map((l) => ({
            lineId: l.id,
            quantity: entry(l).quantity,
            unit: entry(l).unit || null,
            supplierLot: lots[text(l.id)]?.trim() || null,
          })),
        },
        key.current,
      );
      await Promise.all([invalidatePurchasing(qc), refresh()]);
      notify('Goods received successfully into quality quarantine.');
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }
  return (
    <Overlay
      title={`Receive ${text(order.number)}`}
      subtitle={`${text(order.supplier)} · expected ${date(order.due_date)}. Each received line becomes a quarantined lot waiting for incoming quality control.`}
      onClose={() => {
        if (!pending) onClose();
      }}
      wide
    >
      <form onSubmit={submit} className="purchase-receive">
        <div className="form-fields">
          <label className="field">
            <span>Raw-material warehouse</span>
            <select
              required
              value={warehouseId}
              onChange={(e) => {
                setWarehouseId(e.target.value);
                touched();
              }}
            >
              {!detail.warehouses.length && <option value="">No active raw-material warehouse</option>}
              {detail.warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {text(w.name)} ({text(w.code)})
                </option>
              ))}
            </select>
            <em>Only active raw-material warehouses accept purchased goods.</em>
          </label>
          <label className="field">
            <span>Delivery note reference</span>
            <input
              required
              maxLength={120}
              value={deliveryNote}
              placeholder="Supplier's delivery note number"
              onChange={(e) => {
                setDeliveryNote(e.target.value);
                touched();
              }}
            />
          </label>
        </div>
        <div className="dispatch-lots">
          <div className="dispatch-lots-head">
            <h3>
              <PackageCheck size={15} />
              Open lines
            </h3>
            {open.length > 0 && (
              <button type="button" className="text-button" onClick={fill} disabled={pending}>
                <Wand2 size={13} />
                Receive everything outstanding
              </button>
            )}
          </div>
          {open.length ? (
            <div className="purchase-receive-lines">
              {open.map((l) => (
                <div key={l.id} className="purchase-receive-line">
                  <div className="cell-stack">
                    <strong>
                      {text(l.line_no)}. {text(l.item)}
                    </strong>
                    <span>
                      {fmt(l.outstanding, 3)} {text(l.uom)} outstanding
                      {l.unit !== l.uom ? ` · ordered in ${text(l.unit)}` : ''}
                    </span>
                  </div>
                  <UnitQuantityInput
                    itemId={text(l.item_id)}
                    label="Received"
                    usage="purchase"
                    value={entry(l)}
                    disabled={pending}
                    onChange={(value) => {
                      setEntries({ ...entries, [text(l.id)]: value });
                      touched();
                    }}
                  />
                  <label className="field">
                    <span>
                      Supplier lot<small>Optional</small>
                    </span>
                    <input
                      maxLength={80}
                      value={lots[text(l.id)] || ''}
                      placeholder="Generated if empty"
                      disabled={pending}
                      onChange={(e) => {
                        setLots({ ...lots, [text(l.id)]: e.target.value });
                        touched();
                      }}
                    />
                  </label>
                </div>
              ))}
            </div>
          ) : (
            <Empty title="Nothing left to receive" body="Every line of this order is fully received." />
          )}
          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <span className="muted sales-editor-total">
            {selected.length} of {open.length} lines
          </span>
          <span className="grow" />
          <button type="button" className="button secondary" onClick={onClose} disabled={pending}>
            Cancel
          </button>
          <button className="button primary" disabled={pending || !open.length || !detail.warehouses.length}>
            {pending ? <LoaderCircle className="spin" size={15} /> : <PackageCheck size={15} />}
            Receive into quarantine
          </button>
        </div>
      </form>
    </Overlay>
  );
}
