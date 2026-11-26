import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { LoaderCircle, X } from 'lucide-react';
import { useStateData } from '../../app/workspace';
import { Overlay } from '../../components';
import type { Row } from '../../lib/api';
import { labelState, text, today } from '../../lib/format';
import { LotPicker } from '../inventory/LotPicker';
import { SEVERITIES, useDefectCodes } from '../quality/api';
import { ActionError } from '../quality/shared';
import { useRecallCommand } from './api';

export type SuspectLot = { id: string; number: string };

/**
 * Open a recall (`POST /api/recalls`, QUALITY/ADMIN) from suspect lots or from a supplier and receipt dates. The
 * affected set is computed at once (shown in the recall drawer); nothing is held until the recall is contained.
 */
export function OpenRecallDialog({
  lots: initialLots,
  onClose,
  onOpened,
}: {
  lots: SuspectLot[];
  onClose: () => void;
  onOpened: (id: string) => void;
}) {
  const data = useStateData();
  const defects = useDefectCodes();
  const { run, busy } = useRecallCommand();
  const key = useRef(crypto.randomUUID());
  const [basis, setBasis] = useState<'LOTS' | 'SUPPLIER'>('LOTS');
  const [lots, setLots] = useState<SuspectLot[]>(initialLots);
  const [error, setError] = useState('');
  const suppliers = data.partners.filter((p) => p.type === 'SUPPLIER');
  const purchased = data.items.filter((i) => i.type === 'RAW_MATERIAL' || i.type === 'PACKAGING');
  const activeDefects = (defects.data?.items ?? []).filter((d) => d.active);

  function add(lot: Row | null) {
    if (!lot || lots.some((l) => l.id === lot.id)) return;
    setLots([...lots, { id: lot.id, number: text(lot.number) }]);
  }
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const v = Object.fromEntries(new FormData(e.currentTarget).entries()) as Record<string, string>;
    setError('');
    if (basis === 'LOTS' && !lots.length) {
      setError('Choose at least one suspect lot, or recall by supplier.');
      return;
    }
    try {
      const out = await run<Row>(
        '/recalls',
        {
          title: v.title,
          reason: v.reason,
          severity: v.severity,
          scope: v.scope,
          basis,
          lotIds: basis === 'LOTS' ? lots.map((l) => l.id) : null,
          supplierId: basis === 'SUPPLIER' ? v.supplierId : null,
          itemId: basis === 'SUPPLIER' ? v.itemId || null : null,
          receivedFrom: basis === 'SUPPLIER' ? v.receivedFrom : null,
          receivedTo: basis === 'SUPPLIER' ? v.receivedTo : null,
          defectCode: v.defectCode,
          depth: null,
        },
        key.current,
        'Recall opened successfully. Review the affected set, then contain it.',
      );
      onOpened(text(out.id));
    } catch (err) {
      setError((err as Error).message);
    }
  }
  return (
    <Overlay
      title="Open recall"
      subtitle="Everything made from the suspect material is found through its genealogy; contain it in the next step."
      onClose={() => !busy && onClose()}
      wide
    >
      <form onSubmit={submit}>
        <div className="form-fields qa-form-grid">
          <fieldset className="trace-choice qa-span">
            <legend>Suspect material</legend>
            <label>
              <input type="radio" name="basis" checked={basis === 'LOTS'} onChange={() => setBasis('LOTS')} />
              <span>
                <strong>Chosen lots</strong>
                <small>A raw-material, semi-finished or finished lot you suspect.</small>
              </span>
            </label>
            <label>
              <input
                type="radio"
                name="basis"
                checked={basis === 'SUPPLIER'}
                onChange={() => setBasis('SUPPLIER')}
              />
              <span>
                <strong>Supplier deliveries</strong>
                <small>Every lot received from a supplier between two dates.</small>
              </span>
            </label>
          </fieldset>
          {basis === 'LOTS' ? (
            <div className="qa-span trace-suspects">
              {lots.length > 0 && (
                <ul aria-label="Suspect lots">
                  {lots.map((l) => (
                    <li key={l.id} className="inv-chip">
                      <strong>{l.number}</strong>
                      <button
                        type="button"
                        className="icon-button"
                        aria-label={`Remove lot ${l.number}`}
                        onClick={() => setLots(lots.filter((x) => x.id !== l.id))}
                      >
                        <X size={12} />
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              <LotPicker
                label="Add a suspect lot"
                value={null}
                onChange={add}
                exclude={lots.map((l) => l.id)}
              />
            </div>
          ) : (
            <>
              <label className="field">
                <span>Supplier</span>
                <select name="supplierId" required defaultValue="">
                  <option value="" disabled>
                    Select supplier
                  </option>
                  {suppliers.map((p) => (
                    <option key={p.id} value={p.id}>
                      {text(p.name)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>
                  Item <small>Optional</small>
                </span>
                <select name="itemId" defaultValue="">
                  <option value="">Every item from this supplier</option>
                  {purchased.map((i) => (
                    <option key={i.id} value={i.id}>
                      {text(i.sku)} · {text(i.name)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>Received from</span>
                <input name="receivedFrom" type="date" required max={today()} />
              </label>
              <label className="field">
                <span>Received to</span>
                <input name="receivedTo" type="date" required defaultValue={today()} max={today()} />
              </label>
            </>
          )}
          <fieldset className="trace-choice qa-span">
            <legend>Scope</legend>
            <label>
              <input type="radio" name="scope" value="FORWARD" defaultChecked />
              <span>
                <strong>Forward</strong>
                <small>The suspect lots and everything made from them, wherever it went.</small>
              </span>
            </label>
            <label>
              <input type="radio" name="scope" value="FORWARD_AND_SIBLINGS" />
              <span>
                <strong>Forward and siblings</strong>
                <small>
                  Also the material the suspects were made from and every other lot made from that same
                  purchased, opening or found stock.
                </small>
              </span>
            </label>
          </fieldset>
          <label className="field">
            <span>Severity</span>
            <select name="severity" required defaultValue="CRITICAL">
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
            <em>Classifies the nonconformance reports that hold the stock.</em>
          </label>
          <label className="field qa-span">
            <span>Title</span>
            <input name="title" required maxLength={160} placeholder="What is suspect, in one line" />
          </label>
          <label className="field qa-span">
            <span>Reason</span>
            <textarea
              name="reason"
              rows={3}
              required
              maxLength={4000}
              placeholder="Supplier notice, complaint or test result that triggers the recall"
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
            Open recall
          </button>
        </div>
      </form>
    </Overlay>
  );
}
