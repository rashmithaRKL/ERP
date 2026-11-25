import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { LoaderCircle, Plus, Trash2 } from 'lucide-react';
import { useStateData, useWorkspace } from '../../app/workspace';
import { Overlay } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { labelState, text } from '../../lib/format';
import { QueryState } from '../inventory/shared';
import { DECIDERS, trim, useDefectCodes, useQualityCommand, useQualityQuery } from './api';
import type { PlanDetail } from './api';
import { ActionError } from './shared';

type Line = {
  key: string;
  name: string;
  kind: 'VARIABLE' | 'ATTRIBUTE';
  unit: string;
  nominal: string;
  lowerLimit: string;
  upperLimit: string;
  sampleSize: string;
  critical: boolean;
  defectCode: string;
  method: string;
};

const blank = (): Line => ({
  key: crypto.randomUUID(),
  name: '',
  kind: 'VARIABLE',
  unit: '',
  nominal: '',
  lowerLimit: '',
  upperLimit: '',
  sampleSize: '5',
  critical: false,
  defectCode: '',
  method: '',
});

/**
 * Create (`POST /quality/plans`) or edit (`POST /quality/plans/{id}`, new version) an inspection plan: the item or
 * item category it applies to, the inspection type and its characteristics. Read-only for non-quality roles.
 */
export function PlanEditor({ id, onClose }: { id?: string; onClose: () => void }) {
  const detail = useQualityQuery<PlanDetail>('plan', id ? `/quality/plans/${id}` : undefined);
  return (
    <Overlay
      title={id ? text(detail.data?.plan.name) || 'Inspection plan' : 'New inspection plan'}
      subtitle="Characteristics measured when this item (or category) is inspected"
      onClose={onClose}
      wide
    >
      {id ? (
        <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
          {detail.data && <PlanForm detail={detail.data} onClose={onClose} />}
        </QueryState>
      ) : (
        <PlanForm onClose={onClose} />
      )}
    </Overlay>
  );
}

function PlanForm({ detail, onClose }: { detail?: PlanDetail; onClose: () => void }) {
  const data = useStateData();
  const { can } = useWorkspace();
  const editable = can(...DECIDERS);
  const defects = useDefectCodes();
  const categories = useQuery({
    queryKey: ['item-categories'],
    queryFn: () => request<{ items: Row[] }>('/item-categories'),
    enabled: !detail,
  });
  const { run, busy } = useQualityCommand();
  const key = useRef(crypto.randomUUID());
  const plan = detail?.plan;
  const [scope, setScope] = useState<'item' | 'category'>(plan?.category_id ? 'category' : 'item');
  const [active, setActive] = useState(plan ? plan.active !== false : true);
  const [lines, setLines] = useState<Line[]>(
    detail
      ? detail.characteristics.map((c) => ({
          key: c.id,
          name: text(c.name),
          kind: c.kind,
          unit: text(c.unit),
          nominal: trim(c.nominal),
          lowerLimit: trim(c.lower_limit),
          upperLimit: trim(c.upper_limit),
          sampleSize: text(c.sample_size),
          critical: !!c.critical,
          defectCode: text(c.defect_code),
          method: text(c.method),
        }))
      : [blank()],
  );
  const [error, setError] = useState('');
  const set = (k: string, change: Partial<Line>) =>
    setLines(lines.map((l) => (l.key === k ? { ...l, ...change } : l)));
  const num = (v: string) => (v.trim() === '' ? null : v.trim());

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const v = Object.fromEntries(new FormData(e.currentTarget).entries()) as Record<string, string>;
    const characteristics = lines.map((l) => ({
      name: l.name.trim(),
      kind: l.kind,
      unit: l.kind === 'VARIABLE' ? l.unit.trim() || null : null,
      nominal: l.kind === 'VARIABLE' ? num(l.nominal) : null,
      lowerLimit: l.kind === 'VARIABLE' ? num(l.lowerLimit) : null,
      upperLimit: l.kind === 'VARIABLE' ? num(l.upperLimit) : null,
      sampleSize: Number(l.sampleSize),
      critical: l.critical,
      defectCode: l.defectCode || null,
      method: l.method.trim() || null,
    }));
    setError('');
    try {
      if (plan)
        await run(
          `/quality/plans/${plan.id}`,
          { name: v.name, notes: v.notes || null, active, characteristics },
          key.current,
          `${text(plan.number)} saved as a new version successfully.`,
        );
      else
        await run(
          '/quality/plans',
          {
            name: v.name,
            itemId: scope === 'item' ? v.itemId : null,
            categoryId: scope === 'category' ? v.categoryId : null,
            inspectionType: v.inspectionType,
            notes: v.notes || null,
            characteristics,
          },
          key.current,
          'Inspection plan created successfully.',
        );
      onClose();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <form onSubmit={submit}>
      <fieldset className="form-fields qa-form-grid" disabled={!editable || busy}>
        <label className="field qa-span">
          <span>Plan name</span>
          <input name="name" required maxLength={120} defaultValue={text(plan?.name)} />
        </label>
        {plan ? (
          <>
            <div className="field">
              <span>Applies to</span>
              <strong className="qa-static">
                {plan.item_id ? `${text(plan.sku)} · ${text(plan.item)}` : `Category ${text(plan.category)}`}
              </strong>
            </div>
            <div className="field">
              <span>Inspection type</span>
              <strong className="qa-static capitalize">{labelState(plan.inspection_type)}</strong>
            </div>
          </>
        ) : (
          <>
            <div className="field">
              <span>Applies to</span>
              <div className="inv-segmented" role="radiogroup" aria-label="Plan applies to">
                {(['item', 'category'] as const).map((s) => (
                  <button
                    key={s}
                    type="button"
                    role="radio"
                    aria-checked={scope === s}
                    className={scope === s ? 'active' : ''}
                    onClick={() => setScope(s)}
                  >
                    {s === 'item' ? 'One item' : 'Item category'}
                  </button>
                ))}
              </div>
            </div>
            {scope === 'item' ? (
              <label className="field">
                <span>Item</span>
                <select name="itemId" required defaultValue="">
                  <option value="" disabled>
                    Select item
                  </option>
                  {data.items.map((i) => (
                    <option key={i.id} value={i.id}>
                      {text(i.sku)} · {text(i.name)}
                    </option>
                  ))}
                </select>
              </label>
            ) : (
              <label className="field">
                <span>Category</span>
                <select name="categoryId" required defaultValue="">
                  <option value="" disabled>
                    Select category
                  </option>
                  {(categories.data?.items ?? []).map((c) => (
                    <option key={c.id} value={c.id}>
                      {text(c.name)}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <label className="field">
              <span>Inspection type</span>
              <select name="inspectionType" required defaultValue="FINAL">
                {['INCOMING', 'FINAL', 'RETURN', 'REWORK'].map((t) => (
                  <option key={t} value={t}>
                    {labelState(t)}
                  </option>
                ))}
              </select>
              <em>A final plan also governs returned and reworked lots that have no plan of their own.</em>
            </label>
          </>
        )}
        <label className="field qa-span">
          <span>
            Notes <small>Optional</small>
          </span>
          <textarea name="notes" rows={2} maxLength={2000} defaultValue={text(plan?.notes)} />
        </label>
        {plan && (
          <label className="inv-check qa-span">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
            Active — governs new inspection decisions
          </label>
        )}
        <div className="qa-span qa-characteristics">
          <div className="qa-materials-head">
            <strong>Characteristics</strong>
            {editable && (
              <button
                type="button"
                className="button small secondary"
                onClick={() => setLines([...lines, blank()])}
              >
                <Plus size={13} /> Add characteristic
              </button>
            )}
          </div>
          {lines.map((l, i) => (
            <div className="inv-line qa-characteristic" key={l.key}>
              <div className="inv-line-head">
                <strong>{i + 1}.</strong>
                <input
                  aria-label={`Characteristic ${i + 1} name`}
                  className="qa-name"
                  required
                  maxLength={120}
                  placeholder="Characteristic, e.g. Wall thickness"
                  value={l.name}
                  onChange={(e) => set(l.key, { name: e.target.value })}
                />
                <div className="inv-segmented" role="radiogroup" aria-label={`Characteristic ${i + 1} kind`}>
                  {(['VARIABLE', 'ATTRIBUTE'] as const).map((k) => (
                    <button
                      key={k}
                      type="button"
                      role="radio"
                      aria-checked={l.kind === k}
                      className={l.kind === k ? 'active' : ''}
                      onClick={() => set(l.key, { kind: k, sampleSize: k === 'VARIABLE' ? '5' : '20' })}
                    >
                      {k === 'VARIABLE' ? 'Measured' : 'Pass/fail'}
                    </button>
                  ))}
                </div>
                <label className="inv-check">
                  <input
                    type="checkbox"
                    checked={l.critical}
                    onChange={(e) => set(l.key, { critical: e.target.checked })}
                  />
                  Critical
                </label>
                {lines.length > 1 && editable && (
                  <button
                    type="button"
                    className="icon-button"
                    aria-label={`Remove characteristic ${i + 1}`}
                    onClick={() => setLines(lines.filter((x) => x.key !== l.key))}
                  >
                    <Trash2 size={15} />
                  </button>
                )}
              </div>
              <div className="qa-spec-grid">
                {l.kind === 'VARIABLE' && (
                  <>
                    <label className="field">
                      <span>Unit</span>
                      <input
                        maxLength={20}
                        value={l.unit}
                        placeholder="mm"
                        onChange={(e) => set(l.key, { unit: e.target.value })}
                      />
                    </label>
                    <label className="field">
                      <span>Lower limit</span>
                      <input
                        type="number"
                        step="any"
                        value={l.lowerLimit}
                        onChange={(e) => set(l.key, { lowerLimit: e.target.value })}
                      />
                    </label>
                    <label className="field">
                      <span>Nominal</span>
                      <input
                        type="number"
                        step="any"
                        value={l.nominal}
                        onChange={(e) => set(l.key, { nominal: e.target.value })}
                      />
                    </label>
                    <label className="field">
                      <span>Upper limit</span>
                      <input
                        type="number"
                        step="any"
                        value={l.upperLimit}
                        onChange={(e) => set(l.key, { upperLimit: e.target.value })}
                      />
                    </label>
                  </>
                )}
                <label className="field">
                  <span>{l.kind === 'VARIABLE' ? 'Readings (1–20)' : 'Units checked'}</span>
                  <input
                    type="number"
                    min="1"
                    max={l.kind === 'VARIABLE' ? '20' : '100000'}
                    step="1"
                    required
                    value={l.sampleSize}
                    onChange={(e) => set(l.key, { sampleSize: e.target.value })}
                  />
                </label>
                <label className="field">
                  <span>Defect code</span>
                  <select value={l.defectCode} onChange={(e) => set(l.key, { defectCode: e.target.value })}>
                    <option value="">None</option>
                    {(defects.data?.items ?? [])
                      .filter((d) => d.active || d.code === l.defectCode)
                      .map((d) => (
                        <option key={text(d.code)} value={text(d.code)}>
                          {text(d.code)} · {text(d.name)}
                        </option>
                      ))}
                  </select>
                </label>
                <label className="field qa-wide">
                  <span>Method</span>
                  <input
                    maxLength={200}
                    value={l.method}
                    placeholder="Gauge, test or check"
                    onChange={(e) => set(l.key, { method: e.target.value })}
                  />
                </label>
              </div>
            </div>
          ))}
        </div>
        <div className="qa-span">
          <ActionError message={error} />
        </div>
      </fieldset>
      <div className="dialog-footer">
        <button type="button" className="button secondary" onClick={onClose} disabled={busy}>
          {editable ? 'Cancel' : 'Close'}
        </button>
        {editable && (
          <button className="button primary" disabled={busy}>
            {busy && <LoaderCircle className="spin" size={16} />}
            {plan ? 'Save new version' : 'Create plan'}
          </button>
        )}
      </div>
    </form>
  );
}
