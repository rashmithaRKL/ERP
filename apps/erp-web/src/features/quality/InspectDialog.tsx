import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { LoaderCircle, TriangleAlert } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay } from '../../components';
import type { Row } from '../../lib/api';
import { fmt, labelState, text } from '../../lib/format';
import { QueryState } from '../inventory/shared';
import { inSpec, specText, useQualityCommand, useQualityQuery } from './api';
import type { Characteristic, InspectionPlanContext } from './api';
import { ActionError, QualityBadge } from './shared';

type Entry = { values: string[]; nonconforming: string; note: string };

/**
 * Inspection decision (`POST /quality/{id}/inspect`). When an inspection plan governs the inspection, every
 * characteristic is measured here: readings outside the limits are highlighted, a critical characteristic out of
 * specification forces a failed decision, and a non-critical one can pass only with a note.
 */
export function InspectDialog({ inspection, onClose }: { inspection: Row; onClose: () => void }) {
  const context = useQualityQuery<InspectionPlanContext>(
    'inspection-plan',
    `/quality/inspections/${inspection.id}/plan`,
  );
  const plan = context.data?.plan;
  const characteristics = context.data?.characteristics ?? [];
  const { run, busy } = useQualityCommand();
  const { notify } = useWorkspace();
  const key = useRef(crypto.randomUUID());
  const [entries, setEntries] = useState<Record<string, Entry>>({});
  const [result, setResult] = useState<'PASSED' | 'FAILED' | ''>('');
  const [notes, setNotes] = useState('');
  const [error, setError] = useState('');

  const entry = (c: Characteristic): Entry =>
    entries[c.id] ?? {
      values: Array.from({ length: c.kind === 'VARIABLE' ? Number(c.sample_size) : 0 }, () => ''),
      nonconforming: c.kind === 'ATTRIBUTE' ? '0' : '',
      note: '',
    };
  const update = (c: Characteristic, change: Partial<Entry>) =>
    setEntries((all) => ({ ...all, [c.id]: { ...entry(c), ...change } }));
  const outOfSpec = (c: Characteristic) => {
    const e = entry(c);
    if (c.kind === 'ATTRIBUTE') return Number(e.nonconforming || 0) > 0;
    return e.values.some((v) => v.trim() !== '' && !inSpec(c, Number(v)));
  };
  const complete = (c: Characteristic) => {
    const e = entry(c);
    return c.kind === 'ATTRIBUTE'
      ? e.nonconforming.trim() !== '' && Number(e.nonconforming) <= Number(c.sample_size)
      : e.values.every((v) => v.trim() !== '' && Number.isFinite(Number(v)));
  };
  const criticalFail = characteristics.filter((c) => c.critical && outOfSpec(c));
  const unexplained = characteristics.filter((c) => !c.critical && outOfSpec(c) && !entry(c).note.trim());
  const decision = criticalFail.length ? 'FAILED' : result;
  const missing = characteristics.filter((c) => !complete(c));
  const blocked =
    !decision ||
    !notes.trim() ||
    (plan && missing.length > 0) ||
    (decision === 'PASSED' && unexplained.length > 0);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (blocked) return;
    setError('');
    const measurements = plan
      ? characteristics.map((c) => {
          const x = entry(c);
          return {
            characteristicId: c.id,
            values: c.kind === 'VARIABLE' ? x.values.map(Number) : null,
            nonconforming: c.kind === 'ATTRIBUTE' ? Number(x.nonconforming) : null,
            note: x.note.trim() || null,
          };
        })
      : null;
    try {
      const out = await run<Row>(
        `/quality/${inspection.id}/inspect`,
        { result: decision, notes: notes.trim(), measurements },
        key.current,
      );
      const ncr = out.ncrNumber ? ` Nonconformance ${text(out.ncrNumber)} raised.` : '';
      notify(
        `${text(inspection.lot)} ${out.status === 'PASSED' ? 'released' : 'rejected'} successfully.${ncr}`,
      );
      onClose();
    } catch (err) {
      setError((err as Error).message);
    }
  }
  return (
    <Overlay
      title="Record quality inspection"
      subtitle={`${text(inspection.item)} · Lot ${text(inspection.lot)} · ${labelState(inspection.type)} inspection`}
      onClose={() => !busy && onClose()}
      wide={!!plan}
    >
      <QueryState pending={context.isPending} error={context.error} retry={() => context.refetch()}>
        <form onSubmit={submit}>
          <div className="form-fields qa-stack">
            {plan ? (
              <div className="qa-plan-head">
                <div>
                  <strong>{text(plan.name)}</strong>
                  <span>
                    {text(plan.number)} · version {text(plan.version)} · {characteristics.length}{' '}
                    characteristics
                  </span>
                </div>
                <QualityBadge value="ACTIVE" label="Plan applies" />
              </div>
            ) : (
              <p className="inv-small muted">
                No inspection plan governs this inspection: record the decision and your findings.
              </p>
            )}
            {plan && (
              <div className="qa-measure-list">
                {characteristics.map((c) => {
                  const x = entry(c);
                  const bad = outOfSpec(c);
                  return (
                    <fieldset key={c.id} className={`qa-measure ${bad ? 'out' : ''}`}>
                      <legend>
                        <strong>{c.name}</strong>
                        {c.critical && <QualityBadge value="CRITICAL" label="Critical" />}
                        {bad && <QualityBadge value="FAILED" label="Out of spec" />}
                      </legend>
                      <p className="qa-spec">
                        {specText(c)}
                        {c.method ? ` · ${text(c.method)}` : ''}
                      </p>
                      {c.kind === 'VARIABLE' ? (
                        <div className="qa-readings">
                          {x.values.map((v, i) => (
                            <input
                              key={i}
                              type="number"
                              step="any"
                              inputMode="decimal"
                              required
                              aria-label={`${c.name} reading ${i + 1}`}
                              aria-invalid={v.trim() !== '' && !inSpec(c, Number(v))}
                              className={v.trim() !== '' && !inSpec(c, Number(v)) ? 'qa-out' : ''}
                              value={v}
                              placeholder={`#${i + 1}`}
                              onChange={(e) => {
                                const values = [...x.values];
                                values[i] = e.target.value;
                                update(c, { values });
                              }}
                            />
                          ))}
                        </div>
                      ) : (
                        <label className="qa-inline">
                          <span>Nonconforming units of {fmt(c.sample_size)}</span>
                          <input
                            type="number"
                            min="0"
                            max={String(c.sample_size)}
                            step="1"
                            required
                            className={bad ? 'qa-out' : ''}
                            value={x.nonconforming}
                            onChange={(e) => update(c, { nonconforming: e.target.value })}
                          />
                        </label>
                      )}
                      {bad && !c.critical && (
                        <label className="qa-inline wide">
                          <span>Deviation note (needed to pass)</span>
                          <input
                            maxLength={500}
                            value={x.note}
                            onChange={(e) => update(c, { note: e.target.value })}
                            placeholder="Why this result is acceptable"
                          />
                        </label>
                      )}
                    </fieldset>
                  );
                })}
              </div>
            )}
            {criticalFail.length > 0 && (
              <div className="inv-warning" role="status">
                <TriangleAlert size={16} />
                <div>
                  <strong>The inspection fails</strong>
                  <p>
                    Critical characteristic out of specification: {criticalFail.map((c) => c.name).join(', ')}
                    . A nonconformance report is raised automatically.
                  </p>
                </div>
              </div>
            )}
            <div className="field">
              <span>Inspection decision</span>
              <div className="inv-segmented" role="radiogroup" aria-label="Inspection decision">
                <button
                  type="button"
                  role="radio"
                  aria-checked={decision === 'PASSED'}
                  className={decision === 'PASSED' ? 'active' : ''}
                  disabled={criticalFail.length > 0}
                  onClick={() => setResult('PASSED')}
                >
                  Pass — release lot
                </button>
                <button
                  type="button"
                  role="radio"
                  aria-checked={decision === 'FAILED'}
                  className={decision === 'FAILED' ? 'active' : ''}
                  onClick={() => setResult('FAILED')}
                >
                  Fail — hold lot and raise NCR
                </button>
              </div>
              {decision === 'FAILED' && inspection.source_type === 'PURCHASE' && (
                <em>A failed incoming lot is returned to the supplier in full.</em>
              )}
              {decision === 'PASSED' && unexplained.length > 0 && (
                <em className="negative">
                  Add a deviation note for {unexplained.map((c) => c.name).join(', ')} or fail the inspection.
                </em>
              )}
            </div>
            <label className="field">
              <span>Findings and reason</span>
              <textarea
                rows={3}
                required
                maxLength={2000}
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
              />
            </label>
            {plan && missing.length > 0 && (
              <p className="inv-small muted">Still to measure: {missing.map((c) => c.name).join(', ')}.</p>
            )}
            <ActionError message={error} />
          </div>
          <div className="dialog-footer">
            <button type="button" className="button secondary" onClick={onClose} disabled={busy}>
              Cancel
            </button>
            <button className="button primary" disabled={busy || !!blocked}>
              {busy && <LoaderCircle className="spin" size={16} />}
              Finalize inspection
            </button>
          </div>
        </form>
      </QueryState>
    </Overlay>
  );
}
