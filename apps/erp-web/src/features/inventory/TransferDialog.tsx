import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { ArrowRight, LoaderCircle, Plus, Trash2 } from 'lucide-react';
import { useStateData, useWorkspace } from '../../app/workspace';
import { Overlay } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { money, num, text } from '../../lib/format';
import { useInventoryCommand } from './api';
import { LotPicker } from './LotPicker';

type Line = { key: string; lot: Row | null; quantity: string };
const blank = (): Line => ({ key: crypto.randomUUID(), lot: null, quantity: '' });

/**
 * Warehouse transfer editor. Each line moves part of a lot; posting creates a destination lot with the same
 * item, quality status and unit cost. Reserved stock is not offered.
 */
export function TransferDialog({ onClose, onDone }: { onClose: () => void; onDone: (id: string) => void }) {
  const data = useStateData();
  const { notify } = useWorkspace();
  const { invalidate } = useInventoryCommand();
  const warehouses = data.warehouses.filter((w) => w.active !== false);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [notes, setNotes] = useState('');
  const [lines, setLines] = useState<Line[]>([blank()]);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const createKey = useRef(crypto.randomUUID());
  const postKey = useRef(crypto.randomUUID());
  const formRef = useRef<HTMLFormElement>(null);
  const update = (index: number, patch: Partial<Line>) =>
    setLines((prev) => prev.map((l, i) => (i === index ? { ...l, ...patch } : l)));
  const value = lines.reduce((sum, l) => sum + num(l.quantity) * num(l.lot?.unit_cost), 0);

  async function save(post: boolean) {
    setError('');
    const missing = lines.findIndex((l) => !l.lot);
    if (missing >= 0) {
      setError(`Line ${missing + 1}: choose a lot.`);
      return;
    }
    setPending(true);
    try {
      const created = await request<Row>(
        '/inventory/transfers',
        {
          fromWarehouseId: from,
          toWarehouseId: to,
          notes,
          lines: lines.map((l) => ({ lotId: l.lot!.id, quantity: l.quantity })),
        },
        createKey.current,
      );
      if (!post) notify(`Draft ${text(created.number)} saved successfully.`);
      else {
        try {
          await request(`/inventory/transfers/${created.id}/post`, {}, postKey.current);
          notify(`${text(created.number)} posted successfully.`);
        } catch (e) {
          notify(`${text(created.number)} was saved as a draft, but posting failed: ${(e as Error).message}`);
        }
      }
      await invalidate();
      onDone(created.id);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(false);
    }
  }

  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    void save(true);
  }

  return (
    <Overlay
      title="New warehouse transfer"
      subtitle="Moves stock between warehouses at its current value; quality status and cost travel with it."
      onClose={() => {
        if (!pending) onClose();
      }}
      wide
    >
      <form onSubmit={submit} ref={formRef}>
        <div className="form-fields">
          <label className="field">
            <span>From warehouse</span>
            <select
              required
              value={from}
              onChange={(e) => {
                setFrom(e.target.value);
                setLines([blank()]);
              }}
            >
              <option value="" disabled>
                Select source
              </option>
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {text(w.name)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>To warehouse</span>
            <select required value={to} onChange={(e) => setTo(e.target.value)}>
              <option value="" disabled>
                Select destination
              </option>
              {warehouses
                .filter((w) => w.id !== from)
                .map((w) => (
                  <option key={w.id} value={w.id}>
                    {text(w.name)}
                  </option>
                ))}
            </select>
          </label>
          <label className="field">
            <span>
              Notes<small>Optional</small>
            </span>
            <textarea rows={2} maxLength={1000} value={notes} onChange={(e) => setNotes(e.target.value)} />
          </label>
        </div>
        <div className="inv-lines">
          {lines.map((line, index) => (
            <div className="inv-line" key={line.key}>
              <div className="inv-line-head">
                <strong>Line {index + 1}</strong>
                <span className="grow" />
                <button
                  type="button"
                  className="icon-button"
                  aria-label={`Remove line ${index + 1}`}
                  disabled={lines.length === 1}
                  onClick={() => setLines(lines.filter((_, i) => i !== index))}
                >
                  <Trash2 size={16} />
                </button>
              </div>
              <LotPicker
                label="Lot"
                value={line.lot}
                warehouseId={from}
                movable
                disabled={!from}
                exclude={lines.filter((_, i) => i !== index).map((l) => l.lot?.id || '')}
                onChange={(lot) => update(index, { lot })}
              />
              <div className="inv-line-grid">
                <label className="field">
                  <span>
                    Quantity to move<small>{text(line.lot?.uom) || 'base unit'}</small>
                  </span>
                  <input
                    required
                    type="number"
                    min="0.000001"
                    max={line.lot ? text(line.lot.available) : undefined}
                    step={line.lot?.uom === 'pcs' ? '1' : '0.000001'}
                    value={line.quantity}
                    onChange={(e) => update(index, { quantity: e.target.value })}
                  />
                  {line.lot && <em>Up to the unreserved quantity.</em>}
                </label>
                <div className="inv-line-value">
                  <span>Approximate value</span>
                  <strong>{money(num(line.quantity) * num(line.lot?.unit_cost))}</strong>
                </div>
              </div>
            </div>
          ))}
          <button
            className="button secondary small"
            type="button"
            disabled={!from}
            onClick={() => setLines([...lines, blank()])}
          >
            <Plus size={14} />
            Add lot
          </button>
          <p className="inv-threshold">
            {text(warehouses.find((w) => w.id === from)?.name) || 'Source'} <ArrowRight size={12} />{' '}
            {text(warehouses.find((w) => w.id === to)?.name) || 'destination'} · about {money(value)} moves
            value-neutrally (no journal entry: inventory accounts follow the item type, not the warehouse).
          </p>
          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <button type="button" className="button secondary" disabled={pending} onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="button secondary"
            disabled={pending}
            onClick={() => {
              if (formRef.current?.reportValidity()) void save(false);
            }}
          >
            Save draft
          </button>
          <button className="button primary" disabled={pending}>
            {pending && <LoaderCircle className="spin" size={15} />}
            Save & post
          </button>
        </div>
      </form>
    </Overlay>
  );
}
