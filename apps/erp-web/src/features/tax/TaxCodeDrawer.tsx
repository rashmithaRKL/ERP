import { CalendarPlus, Layers, Lock, Pencil, Plus, Trash2 } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import { request } from '../../lib/api';
import { text, today } from '../../lib/format';
import { longDate } from '../admin/api';
import { Tone } from '../admin/shared';
import { APPLIES_LABELS, KIND_LABELS, pct, useInvalidateTax } from './api';
import type { TaxCode, TaxComponent } from './api';

/** One tax code: header, accounts, components and their rate history (add, remove unused versions). ADMIN. */
export function TaxCodeDrawer({ code, onClose }: { code: TaxCode; onClose: () => void }) {
  const { openForm } = useWorkspace();
  const invalidate = useInvalidateTax();
  const standard = code.kind === 'STANDARD';

  function edit() {
    openForm({
      title: `Edit ${text(code.code)}`,
      description: 'The code and its kind are fixed. Deactivated codes stay on recorded tax and in reports.',
      fields: [
        { name: 'name', label: 'Name', defaultValue: text(code.name) },
        {
          name: 'appliesTo',
          label: 'Applies to',
          options: Object.entries(APPLIES_LABELS).map(([value, label]) => ({ value, label })),
          defaultValue: text(code.applies_to),
        },
        {
          name: 'outputAccountCode',
          label: 'Output tax account (sales)',
          defaultValue: text(code.output_account_code),
        },
        {
          name: 'inputAccountCode',
          label: 'Input tax account (purchases)',
          defaultValue: text(code.input_account_code),
        },
        {
          name: 'active',
          label: 'Status',
          options: [
            { value: 'true', label: 'Active — available on documents' },
            { value: 'false', label: 'Inactive — kept for history' },
          ],
          defaultValue: code.active ? 'true' : 'false',
        },
      ],
      submit: 'Save code',
      run: async (v, key) => {
        const result = await request(
          `/tax/codes/${code.id}`,
          {
            name: v.name.trim(),
            appliesTo: v.appliesTo,
            outputAccountCode: v.outputAccountCode.trim().toUpperCase(),
            inputAccountCode: v.inputAccountCode.trim().toUpperCase(),
            active: v.active === 'true',
          },
          key,
        );
        await invalidate();
        return result;
      },
    });
  }

  function addComponent() {
    openForm({
      title: `Add a component to ${text(code.code)}`,
      description:
        'Components are calculated in order. A compound component is charged on the base plus the tax of the earlier components.',
      fields: [
        { name: 'name', label: 'Component name' },
        {
          name: 'compound',
          label: 'Calculated on',
          options: [
            { value: 'false', label: 'The taxable base' },
            { value: 'true', label: 'The base plus earlier components (compound)' },
          ],
          defaultValue: 'false',
        },
        { name: 'rate', label: 'Rate (%)', type: 'number', min: '0', step: '0.0001' },
        { name: 'effectiveFrom', label: 'Effective from', type: 'date' },
      ],
      submit: 'Add component',
      run: async (v, key) => {
        const result = await request(
          `/tax/codes/${code.id}/components`,
          {
            name: v.name.trim(),
            compound: v.compound === 'true',
            effectiveFrom: v.effectiveFrom,
            rate: v.rate,
          },
          key,
        );
        await invalidate();
        return result;
      },
    });
  }

  return (
    <Overlay
      title={`${text(code.code)} · ${text(code.name)}`}
      subtitle="Components, rate history and posting accounts"
      onClose={onClose}
      drawer
      wide
    >
      <div className="drawer-body tax-drawer">
        <div className="detail-product">
          <span className="product-mark blue">
            <Layers size={20} />
          </span>
          <div>
            <h2>{text(code.name)}</h2>
            <p>
              {KIND_LABELS[text(code.kind)]} · {APPLIES_LABELS[text(code.applies_to)]}
              {standard ? ` · ${pct(code.current_rate)} today` : ''}
            </p>
          </div>
          <span className="tax-profile">
            {code.active ? <Tone tone="green">Active</Tone> : <Tone tone="gray">Inactive</Tone>}
            {code.used ? <Tone tone="blue">Used</Tone> : null}
          </span>
        </div>
        {code.template_regime && (
          <div className="adm-note">
            <Lock size={16} />
            <span>
              From the {text(code.template_regime)} country template
              {code.edited_at
                ? `, edited on ${longDate(code.edited_at)}: applying the template again leaves it as it is.`
                : '. Applying the template again may add new rate versions until you edit it.'}
            </span>
          </div>
        )}
        <dl className="detail-list">
          <dt>Output tax account</dt>
          <dd className="adm-mono">{text(code.output_account_code)}</dd>
          <dt>Input tax account</dt>
          <dd className="adm-mono">{text(code.input_account_code)}</dd>
          <dt>Defaults on</dt>
          <dd>
            {text(code.item_count)} items · {text(code.partner_count)} partners
          </dd>
        </dl>
        {standard ? (
          <>
            <h3>
              <span>Components</span>
              {code.components.length < 9 && (
                <button className="button secondary small" onClick={addComponent}>
                  <Plus size={13} />
                  Add component
                </button>
              )}
            </h3>
            {code.components.map((c) => (
              <ComponentRates key={c.id} code={code} component={c} />
            ))}
          </>
        ) : (
          <div className="adm-note" style={{ marginTop: 18 }}>
            <Layers size={16} />
            <span>
              {code.kind === 'ZERO_RATED'
                ? 'Zero-rated: taxable at 0 %. The base is reported as zero-rated supplies in the VAT return.'
                : 'Exempt: outside the tax. The base is reported separately as exempt supplies.'}
            </span>
          </div>
        )}
      </div>
      <div className="dialog-footer detail-footer">
        <span className="grow" />
        <button className="button primary" onClick={edit}>
          <Pencil size={15} />
          Edit code
        </button>
      </div>
    </Overlay>
  );
}

function ComponentRates({ code, component }: { code: TaxCode; component: TaxComponent }) {
  const { openForm } = useWorkspace();
  const invalidate = useInvalidateTax();

  function addRate() {
    openForm({
      title: `New rate for ${text(component.name)}`,
      description: `${text(code.code)}: the rate applies from its date until the next version starts. It cannot start on or before a date that already carries recorded tax.`,
      fields: [
        { name: 'effectiveFrom', label: 'Effective from', type: 'date' },
        { name: 'rate', label: 'Rate (%)', type: 'number', min: '0', step: '0.0001' },
        { name: 'note', label: 'Note', optional: true, hint: 'For example the gazette or budget reference.' },
      ],
      submit: 'Add rate',
      run: async (v, key) => {
        const result = await request(
          `/tax/components/${component.id}/rates`,
          { effectiveFrom: v.effectiveFrom, rate: v.rate, note: v.note?.trim() || null },
          key,
        );
        await invalidate();
        return result;
      },
    });
  }

  function editComponent() {
    openForm({
      title: `Edit ${text(component.name)}`,
      description: component.used
        ? 'This component carries recorded tax: only its name can change.'
        : 'Rename the component or change how it is calculated.',
      fields: [
        { name: 'name', label: 'Component name', defaultValue: text(component.name) },
        {
          name: 'compound',
          label: 'Calculated on',
          options: [
            { value: 'false', label: 'The taxable base' },
            ...(Number(component.seq) > 1
              ? [{ value: 'true', label: 'The base plus earlier components (compound)' }]
              : []),
          ],
          defaultValue: component.compound ? 'true' : 'false',
        },
      ],
      submit: 'Save component',
      run: async (v, key) => {
        const result = await request(
          `/tax/components/${component.id}`,
          { name: v.name.trim(), compound: v.compound === 'true' },
          key,
        );
        await invalidate();
        return result;
      },
    });
  }

  function remove(rate: TaxComponent['rates'][number]) {
    openForm({
      title: `Remove the ${pct(rate.rate)} rate from ${longDate(rate.effective_from)}?`,
      description:
        'The previous version then applies until the next one. Rate versions used by recorded tax cannot be removed.',
      fields: [],
      submit: 'Remove rate',
      run: async (_v, key) => {
        const result = await request(`/tax/rates/${rate.id}/remove`, {}, key);
        await invalidate();
        return result;
      },
    });
  }

  return (
    <div className="tax-component">
      <div className="tax-component-head">
        <div>
          <strong>
            {text(component.seq)}. {text(component.name)}
          </strong>{' '}
          <span>
            {component.compound ? 'compound: on base + earlier components' : 'on the taxable base'} · today{' '}
            {pct(component.current_rate)}
          </span>
        </div>
        <div className="adm-buttons">
          <button className="button secondary small" onClick={editComponent}>
            <Pencil size={13} />
            Edit
          </button>
          <button className="button secondary small" onClick={addRate}>
            <CalendarPlus size={13} />
            Add rate
          </button>
        </div>
      </div>
      <Table
        rows={component.rates}
        compact
        pageSize={Math.max(component.rates.length, 1)}
        label={`Rate history of ${text(component.name)}`}
        columns={[
          {
            label: 'Effective',
            render: (r) => (
              <div className="cell-stack">
                <strong>{longDate(r.effective_from)}</strong>
                <span>{r.effective_to ? `to ${longDate(r.effective_to)}` : 'open-ended'}</span>
              </div>
            ),
          },
          { label: 'Rate', render: (r) => <span className="tax-rate">{pct(r.rate)}</span> },
          { label: 'Note', render: (r) => <span className="notes-cell">{text(r.note) || '—'}</span> },
          {
            label: 'State',
            render: (r) => (
              <span className="tax-profile">
                {r.current ? (
                  <Tone tone="green">Current</Tone>
                ) : text(r.effective_from) > today() ? (
                  <Tone tone="blue">Scheduled</Tone>
                ) : (
                  <Tone tone="gray">Past</Tone>
                )}
                {r.used ? <Tone tone="amber">Used · locked</Tone> : null}
              </span>
            ),
          },
          {
            label: '',
            render: (r) =>
              !r.used && component.rates.length > 1 ? (
                <button
                  className="button small danger-ghost"
                  aria-label={`Remove the rate from ${longDate(r.effective_from)}`}
                  onClick={() => remove(r)}
                >
                  <Trash2 size={13} />
                  Remove
                </button>
              ) : null,
          },
        ]}
      />
    </div>
  );
}
