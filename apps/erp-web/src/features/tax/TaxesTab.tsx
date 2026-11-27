import { useState } from 'react';
import type { FormEvent } from 'react';
import { AnimatePresence } from 'motion/react';
import { Globe, Info, LoaderCircle, Plus, Save, TriangleAlert } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Panel, Table } from '../../components';
import { request } from '../../lib/api';
import { date, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { QueryState, Tone } from '../admin/shared';
import {
  APPLIES_LABELS,
  codeOptions,
  KIND_LABELS,
  pct,
  useInvalidateTax,
  useTaxCodes,
  useTaxSettings,
} from './api';
import type { TaxCode, TaxSettings } from './api';
import { TaxCodeDrawer } from './TaxCodeDrawer';
import { TaxRegisterPanel, TaxReturnPanel } from './TaxReports';
import './tax.css';

const SECTIONS = [
  { value: 'codes', label: 'Tax codes' },
  { value: 'settings', label: 'Settings and templates' },
  { value: 'register', label: 'Tax register' },
  { value: 'return', label: 'VAT return' },
];

/**
 * Administration → Taxes (V25, ADMIN): tax codes with components and rate history, tenant settings and country
 * templates, the tax register and the VAT return summary.
 */
export function TaxesTab() {
  const [section, setSection] = useState('codes');
  return (
    <div className="admin-section">
      <div className="tax-tabs">
        <Tabs compact label="Tax sections" items={SECTIONS} value={section} onChange={setSection} />
      </div>
      {section === 'codes' && <CodesPanel />}
      {section === 'settings' && <SettingsPanels />}
      {section === 'register' && <TaxRegisterPanel />}
      {section === 'return' && <TaxReturnPanel />}
    </div>
  );
}

function CodesPanel() {
  const workspace = useWorkspace();
  const invalidate = useInvalidateTax();
  const codes = useTaxCodes();
  const [open, setOpen] = useState<string | null>(null);
  const selected = codes.data?.items.find((c) => c.id === open);

  function create() {
    workspace.openForm({
      title: 'New tax code',
      description:
        'A standard code starts with one component and its first rate; add compound components and later rates from the code. Zero-rated and exempt codes carry no rate.',
      fields: [
        { name: 'code', label: 'Code', hint: 'Capitals, digits, - or _ (e.g. VAT-STD). Fixed once created.' },
        { name: 'name', label: 'Name' },
        {
          name: 'kind',
          label: 'Kind',
          options: Object.entries(KIND_LABELS).map(([value, label]) => ({ value, label })),
          defaultValue: 'STANDARD',
          hint: 'Zero-rated supplies report their base as zero-rated; exempt supplies report it separately.',
        },
        {
          name: 'appliesTo',
          label: 'Applies to',
          options: Object.entries(APPLIES_LABELS).map(([value, label]) => ({ value, label })),
          defaultValue: 'BOTH',
        },
        { name: 'componentName', label: 'First component name', optional: true, defaultValue: 'VAT' },
        {
          name: 'rate',
          label: 'Rate (%)',
          type: 'number',
          min: '0',
          step: '0.0001',
          optional: true,
          hint: 'Standard codes only; up to four decimals.',
        },
        { name: 'effectiveFrom', label: 'Rate effective from', type: 'date', optional: true },
        {
          name: 'outputAccountCode',
          label: 'Output tax account (sales)',
          defaultValue: 'OUTPUT_TAX',
          hint: 'An account code of the chart of accounts.',
        },
        { name: 'inputAccountCode', label: 'Input tax account (purchases)', defaultValue: 'INPUT_TAX' },
      ],
      submit: 'Create tax code',
      run: async (v, key) => {
        const standard = v.kind === 'STANDARD';
        if (standard && (!v.rate || !v.effectiveFrom))
          throw new Error('A standard code needs a rate and the date it is effective from.');
        const result = await request(
          '/tax/codes',
          {
            code: v.code.trim().toUpperCase(),
            name: v.name.trim(),
            appliesTo: v.appliesTo,
            kind: v.kind,
            outputAccountCode: v.outputAccountCode.trim().toUpperCase() || null,
            inputAccountCode: v.inputAccountCode.trim().toUpperCase() || null,
            components: standard
              ? [
                  {
                    name: v.componentName.trim() || 'Tax',
                    compound: false,
                    effectiveFrom: v.effectiveFrom,
                    rate: v.rate,
                  },
                ]
              : [],
          },
          key,
        );
        await invalidate();
        return result;
      },
    });
  }

  return (
    <Panel
      title="Tax codes"
      subtitle="Each code stacks one or more components; every component keeps its rate history. A rate used by recorded tax is locked."
      action={
        <button className="button secondary small" onClick={create}>
          <Plus size={14} />
          New tax code
        </button>
      }
    >
      <QueryState pending={codes.isPending} error={codes.error} retry={() => codes.refetch()}>
        {codes.data && codes.data.items.length > 0 ? (
          <Table
            rows={codes.data.items}
            compact
            pageSize={Math.max(codes.data.items.length, 1)}
            label="Tax codes"
            onRow={(r) => setOpen(r.id)}
            columns={[
              {
                label: 'Code',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.code)}</strong>
                    <span>{text(r.name)}</span>
                  </div>
                ),
              },
              { label: 'Kind', render: (r) => KIND_LABELS[text(r.kind)] },
              { label: 'Applies to', render: (r) => APPLIES_LABELS[text(r.applies_to)] },
              {
                label: 'Rate today',
                render: (r) => {
                  const c = r as TaxCode;
                  if (c.kind !== 'STANDARD') return <span className="tax-rate">0 %</span>;
                  return (
                    <div className="cell-stack">
                      <strong className="tax-rate">{pct(c.current_rate)}</strong>
                      <span>
                        {c.components.length} {c.components.length === 1 ? 'component' : 'components'}
                        {c.components.some((k) => k.compound) ? ' · compound' : ''}
                      </span>
                    </div>
                  );
                },
              },
              {
                label: 'Accounts',
                render: (r) => (
                  <div className="cell-stack">
                    <strong className="adm-mono">{text(r.output_account_code)}</strong>
                    <span className="adm-mono">{text(r.input_account_code)}</span>
                  </div>
                ),
              },
              {
                label: 'Status',
                render: (r) => (
                  <span className="tax-profile">
                    {r.active ? <Tone tone="green">Active</Tone> : <Tone tone="gray">Inactive</Tone>}
                    {r.used ? <Tone tone="blue">Used</Tone> : null}
                    {r.template_regime && !r.edited_at ? <Tone tone="gray">Template</Tone> : null}
                  </span>
                ),
              },
            ]}
          />
        ) : (
          <div className="adm-note tax-empty">
            <Info size={16} />
            <span>
              <strong>No tax codes yet.</strong> Apply a country template under{' '}
              <em>Settings and templates</em> or create a code with <em>New tax code</em>.
            </span>
          </div>
        )}
      </QueryState>
      <AnimatePresence>
        {selected && <TaxCodeDrawer key="code" code={selected} onClose={() => setOpen(null)} />}
      </AnimatePresence>
    </Panel>
  );
}

function SettingsPanels() {
  const settings = useTaxSettings();
  const codes = useTaxCodes();
  return (
    <QueryState
      pending={settings.isPending || codes.isPending}
      error={settings.error || codes.error}
      retry={() => {
        settings.refetch();
        codes.refetch();
      }}
    >
      {settings.data && codes.data && (
        <>
          <SettingsForm
            key={text(settings.data.settings.updated_at)}
            data={settings.data}
            codes={codes.data.items}
          />
          {settings.data.templates.map((t) => (
            <TemplatePanel key={t.code} template={t} />
          ))}
        </>
      )}
    </QueryState>
  );
}

function SettingsForm({ data, codes }: { data: TaxSettings; codes: TaxCode[] }) {
  const { notify } = useWorkspace();
  const invalidate = useInvalidateTax();
  const s = data.settings;
  const [inclusive, setInclusive] = useState(s.prices_include_tax ? 'true' : 'false');
  const [rounding, setRounding] = useState(text(s.rounding) || 'PER_LINE');
  const [sales, setSales] = useState(text(s.default_sales_code_id));
  const [purchase, setPurchase] = useState(text(s.default_purchase_code_id));
  const [exempt, setExempt] = useState(text(s.exempt_code_id));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [key, setKey] = useState(() => crypto.randomUUID());
  const exemptCodes = codes.filter((c) => c.kind === 'EXEMPT' && (c.active || c.id === exempt));

  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await request(
        '/tax/settings',
        {
          pricesIncludeTax: inclusive === 'true',
          rounding,
          defaultSalesCodeId: sales || null,
          defaultPurchaseCodeId: purchase || null,
          exemptCodeId: exempt || null,
        },
        key,
      );
      setKey(crypto.randomUUID());
      notify('Tax settings saved successfully.');
      await invalidate();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel
      title="Tax settings"
      subtitle={`How document prices are read and tax is rounded (${data.currency}, ${data.scale} decimals, half-up). Defaults apply when neither the line, the partner’s exemption, the item nor the partner names a code.`}
    >
      <form className="tax-settings-form" onSubmit={save}>
        <div className="adm-form-grid">
          <label className="field">
            <span>Document prices</span>
            <select value={inclusive} onChange={(e) => setInclusive(e.target.value)}>
              <option value="false">Exclude tax (tax is added)</option>
              <option value="true">Include tax (tax is contained)</option>
            </select>
          </label>
          <label className="field">
            <span>Rounding</span>
            <select value={rounding} onChange={(e) => setRounding(e.target.value)}>
              <option value="PER_LINE">Per line (each line’s tax is rounded)</option>
              <option value="PER_DOCUMENT">Per document (each code is rounded once)</option>
            </select>
          </label>
          <label className="field">
            <span>Exemption code</span>
            <select value={exempt} onChange={(e) => setExempt(e.target.value)}>
              <option value="">First active exempt code</option>
              {exemptCodes.map((c) => (
                <option key={c.id} value={c.id}>
                  {text(c.code)} · {text(c.name)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Default sales tax code</span>
            <select value={sales} onChange={(e) => setSales(e.target.value)}>
              <option value="">None</option>
              {codeOptions(codes, 'SALES', sales).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Default purchase tax code</span>
            <select value={purchase} onChange={(e) => setPurchase(e.target.value)}>
              <option value="">None</option>
              {codeOptions(codes, 'PURCHASES', purchase).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        </div>
        {error && (
          <div className="form-error" role="alert">
            {error}
          </div>
        )}
        <div className="tax-settings-foot">
          <span className="adm-muted">
            {s.regime ? `Template ${text(s.regime)} applied` : 'No template applied'}
            {s.updated_at ? ` · last saved ${date(s.updated_at)}` : ''}
          </span>
          <button className="button primary" disabled={busy}>
            {busy ? <LoaderCircle className="spin" size={15} /> : <Save size={15} />}
            Save settings
          </button>
        </div>
      </form>
    </Panel>
  );
}

function TemplatePanel({ template }: { template: TaxSettings['templates'][number] }) {
  const { notify } = useWorkspace();
  const invalidate = useInvalidateTax();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function apply() {
    setBusy(true);
    setError('');
    try {
      const result = await request<Record<string, string[]>>(`/tax/templates/${template.code}/apply`, {});
      const list = (k: string) => (result[k]?.length ? result[k].join(', ') : 'none');
      notify(
        `${template.name} template applied successfully: created ${list('created')}, updated ${list('updated')}, kept ${list('kept')}.`,
      );
      await invalidate();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Panel
      title={`Country template · ${template.name}`}
      subtitle={`${template.country}: default codes, rate history and rounding (${template.rounding === 'PER_DOCUMENT' ? 'per document' : 'per line'}, prices ${template.prices_include_tax ? 'including' : 'excluding'} tax).`}
      action={template.applied ? <Tone tone="green">Applied</Tone> : undefined}
    >
      <div className="tax-template">
        <div className="adm-note warning">
          <TriangleAlert size={16} />
          <span>
            <strong>{template.disclaimer}</strong> Check every code, rate and date before relying on it.
          </span>
        </div>
        <div className="tax-template-codes">
          {template.codes.map((c) => (
            <span className="adm-chip" key={c.code}>
              <strong>{c.code}</strong>
              {KIND_LABELS[c.kind]}
              {c.rates.length ? ` · ${c.rates.length} rate versions` : ''}
            </span>
          ))}
        </div>
        <ul>
          {template.notes
            .filter((n) => n !== template.disclaimer)
            .map((n) => (
              <li key={n}>{n}</li>
            ))}
        </ul>
        {error && (
          <div className="form-error" role="alert">
            {error}
          </div>
        )}
        <div className="tax-settings-foot">
          <span className="adm-muted">
            Applying again adds missing codes and rates only. Codes you created or edited are never
            overwritten.
          </span>
          <button className="button secondary" onClick={apply} disabled={busy}>
            {busy ? <LoaderCircle className="spin" size={15} /> : <Globe size={15} />}
            {template.applied ? 'Apply again' : 'Apply template'}
          </button>
        </div>
      </div>
    </Panel>
  );
}
