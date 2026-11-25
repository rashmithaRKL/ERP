import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { LoaderCircle } from 'lucide-react';
import { useStateData } from '../../app/workspace';
import { Overlay } from '../../components';
import type { Row } from '../../lib/api';
import { labelState, num, text } from '../../lib/format';
import { LotPicker } from '../inventory/LotPicker';
import { RAISE_SOURCES, SEVERITIES, useDefectCodes, useQualityCommand } from './api';
import { ActionError } from './shared';

/**
 * Raise a nonconformance (`POST /quality/ncrs`). Against a lot, the affected quantity is held: a released lot goes
 * into quarantine until the NCR is dispositioned. Without a lot (audit or process finding) it documents the issue.
 */
export function RaiseNcrDialog({
  onClose,
  onRaised,
  lot: initialLot,
}: {
  onClose: () => void;
  onRaised: (id: string) => void;
  lot?: Row;
}) {
  const data = useStateData();
  const defects = useDefectCodes();
  const { run, busy } = useQualityCommand();
  const key = useRef(crypto.randomUUID());
  const [lot, setLot] = useState<Row | null>(initialLot ?? null);
  const [error, setError] = useState('');
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const v = Object.fromEntries(new FormData(e.currentTarget).entries()) as Record<string, string>;
    setError('');
    try {
      const out = await run<Row>(
        '/quality/ncrs',
        {
          source: v.source,
          severity: v.severity,
          defectCode: v.defectCode,
          title: v.title,
          description: v.description,
          containment: v.containment || null,
          itemId: lot ? null : v.itemId || null,
          lotId: lot?.id ?? null,
          quantity: v.quantity || '0',
          manufacturingOrderId: null,
          supplierId: null,
        },
        key.current,
        'Nonconformance raised successfully.',
      );
      onRaised(text(out.id));
    } catch (err) {
      setError((err as Error).message);
    }
  }
  const activeDefects = (defects.data?.items ?? []).filter((d) => d.active);
  return (
    <Overlay
      title="Raise nonconformance"
      subtitle="A released lot is put on quality hold until the NCR is dispositioned."
      onClose={() => !busy && onClose()}
      wide
    >
      <form onSubmit={submit}>
        <div className="form-fields qa-form-grid">
          <div className="qa-span">
            <LotPicker label="Affected lot (optional)" value={lot} onChange={setLot} />
          </div>
          {!lot && (
            <label className="field">
              <span>
                Item <small>Optional</small>
              </span>
              <select name="itemId" defaultValue="">
                <option value="">No item</option>
                {data.items.map((i) => (
                  <option key={i.id} value={i.id}>
                    {text(i.sku)} · {text(i.name)}
                  </option>
                ))}
              </select>
            </label>
          )}
          <label className="field">
            <span>Affected quantity{lot ? ` (${text(lot.uom)})` : ''}</span>
            <input
              name="quantity"
              type="number"
              min={lot ? '0.000001' : '0'}
              max={lot ? String(num(lot.quantity)) : undefined}
              step={lot?.uom === 'pcs' ? '1' : '0.000001'}
              required={!!lot}
              defaultValue={lot ? '' : '0'}
            />
            {lot && <em>Up to {num(lot.quantity).toLocaleString('en-LK')} in this lot.</em>}
          </label>
          <label className="field">
            <span>Source</span>
            <select name="source" required defaultValue="INTERNAL">
              {RAISE_SOURCES.map((s) => (
                <option key={s} value={s}>
                  {labelState(s)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Severity</span>
            <select name="severity" required defaultValue="MAJOR">
              {SEVERITIES.map((s) => (
                <option key={s} value={s}>
                  {labelState(s)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Defect code</span>
            <select name="defectCode" required defaultValue="">
              <option value="" disabled>
                Select defect code
              </option>
              {activeDefects.map((d) => (
                <option key={text(d.code)} value={text(d.code)}>
                  {text(d.code)} · {text(d.name)}
                </option>
              ))}
            </select>
          </label>
          <label className="field qa-span">
            <span>Title</span>
            <input name="title" required maxLength={160} placeholder="What is wrong, in one line" />
          </label>
          <label className="field qa-span">
            <span>Description</span>
            <textarea name="description" rows={3} required maxLength={4000} />
          </label>
          <label className="field qa-span">
            <span>
              Containment <small>Optional</small>
            </span>
            <textarea
              name="containment"
              rows={2}
              maxLength={2000}
              placeholder="Segregation, customer notice, sorting…"
            />
          </label>
          <div className="qa-span">
            <ActionError message={error} />
          </div>
        </div>
        <div className="dialog-footer">
          <button type="button" className="button secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button className="button primary" disabled={busy}>
            {busy && <LoaderCircle className="spin" size={16} />}
            Raise NCR
          </button>
        </div>
      </form>
    </Overlay>
  );
}
