import { useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { LoaderCircle } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { useWorkspace } from '../../app/workspace';
import { Overlay } from '../../components';
import { request } from '../../lib/api';
import { text } from '../../lib/format';
import type { Attribute, FamilyDetail } from './itemMaster';

type Combination = { key: string; values: Record<string, string>; label: string; existing: boolean };
type Draft = { selected: boolean; sku: string; name: string; cost: string };

const MAX_COMBINATIONS = 200;

function combinations(attributes: Attribute[], existing: Set<string>): Combination[] {
  let result: Record<string, string>[] = [{}];
  for (const attribute of attributes) {
    const next: Record<string, string>[] = [];
    for (const partial of result)
      for (const value of attribute.values) {
        next.push({ ...partial, [attribute.name]: value });
        if (next.length > MAX_COMBINATIONS) break;
      }
    result = next.slice(0, MAX_COMBINATIONS);
  }
  return result.map((values) => {
    const key = attributes.map((a) => `${a.name}=${values[a.name]}`).join('|');
    return {
      key,
      values,
      label: attributes.map((a) => values[a.name]).join(' · '),
      existing: existing.has(key),
    };
  });
}

const slug = (value: string) =>
  value
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '')
    .slice(0, 8);

/** Generates variant items for selected attribute combinations of a family (each becomes a normal item). */
export function VariantGenerator({ family, onClose }: { family: FamilyDetail; onClose: () => void }) {
  const { notify, refresh } = useWorkspace();
  const qc = useQueryClient();
  const t = family.template;
  const existing = useMemo(
    () =>
      new Set(
        family.variants.map((v) =>
          t.attributes.map((a) => `${a.name}=${v.values?.[a.name] ?? ''}`).join('|'),
        ),
      ),
    [family, t.attributes],
  );
  const all = useMemo(() => combinations(t.attributes, existing), [t.attributes, existing]);
  const fresh = all.filter((c) => !c.existing);
  const [defaultCost, setDefaultCost] = useState('');
  const [drafts, setDrafts] = useState<Record<string, Draft>>(() =>
    Object.fromEntries(
      fresh.map((c) => [
        c.key,
        {
          selected: true,
          sku: [text(t.code).toUpperCase(), ...Object.values(c.values).map(slug)].join('-').slice(0, 50),
          name: `${text(t.name)} · ${c.label}`,
          cost: '',
        },
      ]),
    ),
  );
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const key = useRef(crypto.randomUUID());
  const chosen = fresh.filter((c) => drafts[c.key]?.selected);

  function change(comboKey: string, patch: Partial<Draft>) {
    setDrafts((prev) => ({ ...prev, [comboKey]: { ...prev[comboKey], ...patch } }));
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setPending(true);
    setError('');
    try {
      await request(
        `/item-templates/${t.id}/variants`,
        {
          variants: chosen.map((c) => ({
            values: c.values,
            sku: drafts[c.key].sku.trim(),
            name: drafts[c.key].name.trim() || null,
            standardCost: drafts[c.key].cost || defaultCost || '0',
          })),
        },
        key.current,
      );
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['item-templates'] }),
        qc.invalidateQueries({ queryKey: ['items'] }),
        refresh(),
      ]);
      notify(`${chosen.length} variant${chosen.length === 1 ? '' : 's'} created successfully.`);
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }

  return (
    <Overlay
      title={`Generate ${text(t.code)} variants`}
      subtitle="Each selected combination becomes an item with its own SKU, cost and stock. The family itself is never stocked."
      onClose={() => {
        if (!pending) onClose();
      }}
      wide
    >
      <form onSubmit={submit}>
        <div className="variant-defaults">
          <label className="field">
            <span>Default standard cost (LKR)</span>
            <input
              type="number"
              min="0"
              step="0.000001"
              value={defaultCost}
              placeholder="0"
              onChange={(e) => setDefaultCost(e.target.value)}
            />
            <em>Used where a row has no cost of its own.</em>
          </label>
          <div className="field">
            <span>Family</span>
            <em>
              {t.attributes.map((a) => `${a.name}: ${a.values.join(', ')}`).join(' · ')} · base unit{' '}
              {text(t.uom)}
            </em>
          </div>
        </div>
        <div className="variant-matrix">
          <div className="variant-matrix-head">
            <p>
              {chosen.length} of {fresh.length} new combinations selected
              {all.length - fresh.length > 0 ? ` · ${all.length - fresh.length} already exist` : ''}
              {all.length >= MAX_COMBINATIONS ? ` · showing the first ${MAX_COMBINATIONS}` : ''}
            </p>
            {fresh.length > 0 && (
              <button
                type="button"
                className="text-button"
                onClick={() => {
                  const select = chosen.length !== fresh.length;
                  setDrafts((prev) =>
                    Object.fromEntries(Object.entries(prev).map(([k, d]) => [k, { ...d, selected: select }])),
                  );
                }}
              >
                {chosen.length === fresh.length ? 'Clear all' : 'Select all'}
              </button>
            )}
          </div>
          {all.map((c) =>
            c.existing ? (
              <div className="variant-row is-existing" key={c.key}>
                <input type="checkbox" checked disabled aria-label={`${c.label} exists`} />
                <span>{c.label}</span>
                <span className="variant-name">Already in this family</span>
                <span />
              </div>
            ) : (
              <div className="variant-row" key={c.key}>
                <input
                  type="checkbox"
                  aria-label={`Create ${c.label}`}
                  checked={drafts[c.key].selected}
                  onChange={(e) => change(c.key, { selected: e.target.checked })}
                />
                <div className="cell-stack">
                  <strong>{c.label}</strong>
                  <input
                    type="text"
                    aria-label={`SKU for ${c.label}`}
                    required={drafts[c.key].selected}
                    disabled={!drafts[c.key].selected}
                    maxLength={50}
                    value={drafts[c.key].sku}
                    onChange={(e) => change(c.key, { sku: e.target.value })}
                  />
                </div>
                <input
                  className="variant-name"
                  type="text"
                  aria-label={`Name for ${c.label}`}
                  disabled={!drafts[c.key].selected}
                  maxLength={160}
                  value={drafts[c.key].name}
                  onChange={(e) => change(c.key, { name: e.target.value })}
                />
                <input
                  type="number"
                  aria-label={`Standard cost for ${c.label}`}
                  min="0"
                  step="0.000001"
                  placeholder={defaultCost || '0'}
                  disabled={!drafts[c.key].selected}
                  value={drafts[c.key].cost}
                  onChange={(e) => change(c.key, { cost: e.target.value })}
                />
              </div>
            ),
          )}
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
          <button className="button primary" disabled={pending || chosen.length === 0 || chosen.length > 100}>
            {pending && <LoaderCircle className="spin" size={15} />}
            Create {chosen.length} variant{chosen.length === 1 ? '' : 's'}
          </button>
        </div>
      </form>
    </Overlay>
  );
}
