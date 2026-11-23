import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { LoaderCircle, Plus, Trash2 } from 'lucide-react';
import { useStateData, useWorkspace } from '../../app/workspace';
import { Overlay } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { labelState, money, num, text } from '../../lib/format';
import { KINDS_BY_REASON, REASONS, useInventoryCommand, useSettings } from './api';
import type { LineKind } from './api';
import { LotPicker } from './LotPicker';

type Line = {
  key: string;
  kind: LineKind;
  lot: Row | null;
  itemId: string;
  warehouseId: string;
  quantity: string;
  unitCost: string;
  lotNumber: string;
};

const KIND_LABELS: Record<LineKind, string> = {
  DECREASE: 'Decrease',
  INCREASE: 'Increase',
  FOUND: 'Found stock',
};
const REASON_HINTS: Record<string, string> = {
  DAMAGE: 'Write off damaged stock.',
  EXPIRY: 'Write off expired stock.',
  SAMPLE: 'Stock taken for samples or testing.',
  FOUND: 'Unrecorded stock that was found: add to a lot or create a new one.',
  COUNT_VARIANCE: 'Correct a counted difference.',
  OTHER: 'Any other documented correction.',
};

const blank = (kind: LineKind): Line => ({
  key: crypto.randomUUID(),
  kind,
  lot: null,
  itemId: '',
  warehouseId: '',
  quantity: '',
  unitCost: '',
  lotNumber: '',
});

/** Multi-line stock adjustment editor: save as a draft, or save and post (approval applies above the threshold). */
export function AdjustmentDialog({ onClose, onDone }: { onClose: () => void; onDone: (id: string) => void }) {
  const data = useStateData();
  const { notify } = useWorkspace();
  const { invalidate } = useInventoryCommand();
  const settings = useSettings();
  const [reason, setReason] = useState<string>('DAMAGE');
  const [notes, setNotes] = useState('');
  const [lines, setLines] = useState<Line[]>([blank('DECREASE')]);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const createKey = useRef(crypto.randomUUID());
  const postKey = useRef(crypto.randomUUID());
  const formRef = useRef<HTMLFormElement>(null);
  const allowed = KINDS_BY_REASON[reason];
  const items = data.items.filter((i) => i.active !== false);
  const warehouses = data.warehouses.filter((w) => w.active !== false);
  const threshold = num(settings.data?.approvalThreshold ?? 50000);

  const update = (index: number, patch: Partial<Line>) =>
    setLines((prev) => prev.map((l, i) => (i === index ? { ...l, ...patch } : l)));
  function changeReason(next: string) {
    setReason(next);
    const kinds = KINDS_BY_REASON[next];
    setLines((prev) => prev.map((l) => (kinds.includes(l.kind) ? l : { ...blank(kinds[0]), key: l.key })));
  }
  const lineValue = (l: Line) =>
    num(l.quantity) * (l.kind === 'FOUND' ? num(l.unitCost) : num(l.lot?.unit_cost));
  const gross = lines.reduce((sum, l) => sum + lineValue(l), 0);

  async function save(post: boolean) {
    setError('');
    const missing = lines.findIndex((l) => (l.kind === 'FOUND' ? !l.itemId || !l.warehouseId : !l.lot));
    if (missing >= 0) {
      setError(
        `Line ${missing + 1}: ${lines[missing].kind === 'FOUND' ? 'choose the item and warehouse' : 'choose a lot'}.`,
      );
      return;
    }
    setPending(true);
    try {
      const body = {
        reason,
        notes,
        lines: lines.map((l) =>
          l.kind === 'FOUND'
            ? {
                kind: l.kind,
                itemId: l.itemId,
                warehouseId: l.warehouseId,
                quantity: l.quantity,
                unitCost: l.unitCost,
                lotNumber: l.lotNumber || null,
              }
            : { kind: l.kind, lotId: l.lot!.id, quantity: l.quantity },
        ),
      };
      const created = await request<Row>('/inventory/adjustments', body, createKey.current);
      if (!post) notify(`Draft ${text(created.number)} saved successfully.`);
      else {
        try {
          const posted = await request<Row>(`/inventory/adjustments/${created.id}/post`, {}, postKey.current);
          notify(
            posted.status === 'APPROVAL_REQUIRED'
              ? `${text(created.number)} submitted successfully; a second person must approve it.`
              : `${text(created.number)} posted successfully.`,
          );
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
      title="New stock adjustment"
      subtitle="Quantities are in each item’s base unit. Posting values decreases at the lot’s current cost."
      onClose={() => {
        if (!pending) onClose();
      }}
      wide
    >
      <form onSubmit={submit} ref={formRef}>
        <div className="form-fields">
          <label className="field">
            <span>Reason</span>
            <select value={reason} onChange={(e) => changeReason(e.target.value)}>
              {REASONS.map((r) => (
                <option key={r} value={r}>
                  {labelState(r).replace(/^./, (c) => c.toUpperCase())}
                </option>
              ))}
            </select>
            <em>{REASON_HINTS[reason]}</em>
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
                <div className="inv-segmented" role="group" aria-label={`Line ${index + 1} type`}>
                  {(['DECREASE', 'INCREASE', 'FOUND'] as LineKind[]).map((kind) => (
                    <button
                      type="button"
                      key={kind}
                      className={line.kind === kind ? 'active' : ''}
                      aria-pressed={line.kind === kind}
                      disabled={!allowed.includes(kind)}
                      onClick={() => update(index, { ...blank(kind), key: line.key })}
                    >
                      {KIND_LABELS[kind]}
                    </button>
                  ))}
                </div>
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
              {line.kind === 'FOUND' ? (
                <div className="inv-line-grid">
                  <label className="field">
                    <span>Item</span>
                    <select
                      required
                      value={line.itemId}
                      onChange={(e) => {
                        const item = items.find((i) => i.id === e.target.value);
                        update(index, { itemId: e.target.value, unitCost: text(item?.standard_cost ?? '') });
                      }}
                    >
                      <option value="" disabled>
                        Select item
                      </option>
                      {items.map((i) => (
                        <option key={i.id} value={i.id}>
                          {text(i.name)} ({text(i.uom)})
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="field">
                    <span>Found in warehouse</span>
                    <select
                      required
                      value={line.warehouseId}
                      onChange={(e) => update(index, { warehouseId: e.target.value })}
                    >
                      <option value="" disabled>
                        Select warehouse
                      </option>
                      {warehouses.map((w) => (
                        <option key={w.id} value={w.id}>
                          {text(w.name)}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="field">
                    <span>Unit cost (LKR)</span>
                    <input
                      required
                      type="number"
                      min="0"
                      step="0.000001"
                      value={line.unitCost}
                      onChange={(e) => update(index, { unitCost: e.target.value })}
                    />
                  </label>
                  <label className="field">
                    <span>
                      New lot number<small>Optional</small>
                    </span>
                    <input
                      maxLength={80}
                      placeholder="Generated when blank"
                      value={line.lotNumber}
                      onChange={(e) => update(index, { lotNumber: e.target.value })}
                    />
                  </label>
                </div>
              ) : (
                <LotPicker
                  label="Lot"
                  value={line.lot}
                  movable={line.kind === 'DECREASE'}
                  exclude={lines.filter((_, i) => i !== index).map((l) => l.lot?.id || '')}
                  onChange={(lot) => update(index, { lot })}
                />
              )}
              <div className="inv-line-grid">
                <label className="field">
                  <span>
                    Quantity
                    <small>{text(unitOf(line, items)) || 'base unit'}</small>
                  </span>
                  <input
                    required
                    type="number"
                    min="0.000001"
                    max={line.kind === 'DECREASE' && line.lot ? text(line.lot.available) : undefined}
                    step={unitOf(line, items) === 'pcs' ? '1' : '0.000001'}
                    value={line.quantity}
                    onChange={(e) => update(index, { quantity: e.target.value })}
                  />
                  {line.kind === 'DECREASE' && line.lot && <em>Reserved stock cannot be adjusted down.</em>}
                </label>
                <div className="inv-line-value">
                  <span>Estimated value</span>
                  <strong className={line.kind === 'DECREASE' ? 'negative' : 'positive'}>
                    {line.kind === 'DECREASE' ? '−' : '+'}
                    {money(lineValue(line))}
                  </strong>
                </div>
              </div>
            </div>
          ))}
          <button
            className="button secondary small"
            type="button"
            onClick={() => setLines([...lines, blank(allowed[0])])}
          >
            <Plus size={14} />
            Add line
          </button>
          <p className={`inv-threshold ${gross > threshold ? 'over' : ''}`}>
            Gross value {money(gross)} ·{' '}
            {gross > threshold
              ? `above the ${money(threshold)} approval threshold: posting sends it to a second authorized person.`
              : `posts immediately (approval needed above ${money(threshold)}).`}
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

function unitOf(line: Line, items: Row[]) {
  if (line.kind === 'FOUND') return items.find((i) => i.id === line.itemId)?.uom;
  return line.lot?.uom;
}
