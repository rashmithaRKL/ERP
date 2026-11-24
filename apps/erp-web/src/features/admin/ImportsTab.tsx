import { useRef, useState } from 'react';
import type { DragEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  CircleCheck,
  Download,
  FileSpreadsheet,
  FileUp,
  LoaderCircle,
  ShieldAlert,
  TriangleAlert,
  Upload,
} from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Panel, Table } from '../../components';
import type { Column } from '../../components';
import type { Row, Value } from '../../lib/api';
import { request } from '../../lib/api';
import { fmt, money, text } from '../../lib/format';
import { dateTime, download, useAdminPaged, useInvalidateAdmin } from './api';
import { LoadMore, QueryState, Tone } from './shared';

type Kind = 'items' | 'partners' | 'opening-stock';
/** Column names mirror the API (`ImportKind.required()/optional()`); the header match ignores case and spaces. */
const KINDS: { value: Kind; label: string; description: string; required: string[]; optional: string[] }[] = [
  {
    value: 'items',
    label: 'Items',
    description:
      'Creates item master records through the same rules as New item (unique SKU, active unit, category by name). Types: RAW_MATERIAL, PACKAGING, SEMI_FINISHED, FINISHED_GOOD.',
    required: ['sku', 'name', 'type', 'uom', 'standard_cost'],
    optional: ['reorder_level', 'reorder_quantity', 'lead_time_days', 'category', 'description'],
  },
  {
    value: 'partners',
    label: 'Customers & suppliers',
    description:
      'Creates customers and suppliers (type CUSTOMER or SUPPLIER). A name that already exists for the same type is rejected, never duplicated.',
    required: ['type', 'name'],
    optional: [
      'email',
      'phone',
      'payment_days',
      'credit_limit',
      'tax_id',
      'billing_address',
      'shipping_address',
    ],
  },
  {
    value: 'opening-stock',
    label: 'Opening stock',
    description:
      'Posts opening lots in the item’s base unit at the given unit cost: one stock receipt per row and one balanced journal per inventory account against Opening equity. quality_status is RELEASED (default) or QUARANTINE; quarantined lots wait for a quality decision.',
    required: ['item_sku', 'warehouse_code', 'quantity', 'unit_cost'],
    optional: ['lot_number', 'quality_status'],
  },
];
const LABELS: Record<string, string> = {
  ITEMS: 'Items',
  PARTNERS: 'Customers & suppliers',
  OPENING_STOCK: 'Opening stock',
};
const MAX_CHARS = 2_000_000;

type Preview = {
  kind: string;
  fileName: string;
  contentHash: string;
  rows: number;
  validRows: number;
  valid: boolean;
  errors: { row: number | null; column: string | null; message: string }[];
  preview: Row[];
  summary: Record<string, Value | Record<string, Value>>;
  alreadyImported: Row | null;
  columns: { required: string[]; optional: string[] };
};

const PREVIEW_COLUMNS: Record<Kind, Column[]> = {
  items: [
    { label: 'Row', key: 'row' },
    { label: 'SKU', key: 'sku' },
    { label: 'Name', key: 'name' },
    {
      label: 'Type',
      render: (r) => <span className="capitalize">{text(r.type).toLowerCase().replaceAll('_', ' ')}</span>,
    },
    { label: 'Unit', key: 'uom' },
    { label: 'Standard cost', render: (r) => money(r.standard_cost) },
    { label: 'Category', render: (r) => text(r.category) || '—' },
  ],
  partners: [
    { label: 'Row', key: 'row' },
    { label: 'Type', render: (r) => <span className="capitalize">{text(r.type).toLowerCase()}</span> },
    { label: 'Name', key: 'name' },
    { label: 'Email', render: (r) => text(r.email) || '—' },
    { label: 'Terms', render: (r) => `${text(r.payment_days)} days` },
    { label: 'Credit limit', render: (r) => (r.credit_limit === null ? '—' : money(r.credit_limit)) },
  ],
  'opening-stock': [
    { label: 'Row', key: 'row' },
    {
      label: 'Item',
      render: (r) => (
        <div className="cell-stack">
          <strong>{text(r.item_sku)}</strong>
          <span>{text(r.item)}</span>
        </div>
      ),
    },
    { label: 'Warehouse', render: (r) => `${text(r.warehouse_code)} · ${text(r.warehouse)}` },
    {
      label: 'Lot',
      render: (r) =>
        r.lot_generated ? <span className="muted">Generated on import</span> : text(r.lot_number),
    },
    { label: 'Quantity', render: (r) => `${fmt(r.quantity, 6)} ${text(r.uom)}` },
    { label: 'Unit cost', render: (r) => money(r.unit_cost) },
    { label: 'Value', render: (r) => money(r.value) },
    {
      label: 'Status',
      render: (r) =>
        r.quality_status === 'QUARANTINE' ? (
          <Tone tone="amber">Quarantine</Tone>
        ) : (
          <Tone tone="green">Released</Tone>
        ),
    },
  ],
};

/** Two-step CSV import (validate → import) with templates and the import history. ADMIN only. */
export function ImportsTab() {
  const { notify, refresh } = useWorkspace();
  const qc = useQueryClient();
  const invalidate = useInvalidateAdmin();
  const [kind, setKind] = useState<Kind>('items');
  const [file, setFile] = useState<{ name: string; content: string }>();
  const [result, setResult] = useState<Preview>();
  const [busy, setBusy] = useState<'validate' | 'commit'>();
  const [error, setError] = useState('');
  const [over, setOver] = useState(false);
  const [done, setDone] = useState<Row>();
  const commitKey = useRef(crypto.randomUUID());
  const input = useRef<HTMLInputElement>(null);
  const history = useAdminPaged('imports', '/admin/imports', { limit: 20 });
  const meta = KINDS.find((k) => k.value === kind)!;

  function reset(next?: Kind) {
    if (next) setKind(next);
    setFile(undefined);
    setResult(undefined);
    setError('');
    setDone(undefined);
    commitKey.current = crypto.randomUUID();
    if (input.current) input.current.value = '';
  }

  async function choose(selected: File | undefined) {
    if (!selected) return;
    setError('');
    setResult(undefined);
    setDone(undefined);
    commitKey.current = crypto.randomUUID();
    if (!/\.csv$/i.test(selected.name) && selected.type !== 'text/csv') {
      setError('Choose a .csv file. In Excel use File → Save As → CSV UTF-8.');
      return;
    }
    const content = await selected.text();
    if (content.length > MAX_CHARS) {
      setError('The file is larger than 2 MB; split it into smaller files.');
      return;
    }
    setFile({ name: selected.name, content });
    await validate({ name: selected.name, content });
  }

  async function validate(f = file) {
    if (!f) return;
    setBusy('validate');
    setError('');
    try {
      setResult(
        await request<Preview>(`/admin/imports/${kind}/validate`, { fileName: f.name, content: f.content }),
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(undefined);
    }
  }

  async function commit() {
    if (!file) return;
    setBusy('commit');
    setError('');
    try {
      const batch = await request<Row>(
        `/admin/imports/${kind}/commit`,
        { fileName: file.name, content: file.content },
        commitKey.current,
      );
      setDone(batch);
      setResult(undefined);
      setFile(undefined);
      if (input.current) input.current.value = '';
      commitKey.current = crypto.randomUUID();
      notify(
        batch.duplicate
          ? `This file was already imported as ${text(batch.number)}; nothing was created.`
          : `Import ${text(batch.number)} completed successfully.`,
      );
      await Promise.all([invalidate(), refresh(), qc.invalidateQueries({ queryKey: ['inventory'] })]);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(undefined);
    }
  }

  async function template() {
    try {
      await download(`/admin/imports/${kind}/template`, `${kind}-template.csv`);
      notify('CSV template downloaded.');
    } catch (e) {
      notify((e as Error).message);
    }
  }

  function drop(e: DragEvent<HTMLLabelElement>) {
    e.preventDefault();
    setOver(false);
    void choose(e.dataTransfer.files?.[0]);
  }

  const summary = result?.summary;
  const accounts = (summary?.accounts ?? {}) as Record<string, Value>;

  return (
    <div className="admin-section">
      <Panel
        title="Import from CSV"
        subtitle="Validate first: the whole file is checked and simulated, nothing is saved until you import."
        action={
          <button className="button secondary small" onClick={template}>
            <Download size={14} />
            Download template
          </button>
        }
      >
        <div className="adm-import-body">
          <div className="adm-kinds" role="group" aria-label="Import type">
            {KINDS.map((k) => (
              <button
                key={k.value}
                type="button"
                className={kind === k.value ? 'active' : ''}
                aria-pressed={kind === k.value}
                disabled={!!busy}
                onClick={() => reset(k.value)}
              >
                {k.label}
              </button>
            ))}
          </div>
          <p className="adm-muted">{meta.description}</p>
          <div className="adm-columns">
            Columns (bold = required):
            {meta.required.map((c) => (
              <code key={c} className="required" title="Required">
                {c}
              </code>
            ))}
            {meta.optional.map((c) => (
              <code key={c}>{c}</code>
            ))}
          </div>
          <label
            className={`adm-drop ${over ? 'over' : ''}`}
            onDragOver={(e) => {
              e.preventDefault();
              setOver(true);
            }}
            onDragLeave={() => setOver(false)}
            onDrop={drop}
          >
            <input
              ref={input}
              type="file"
              accept=".csv,text/csv"
              disabled={!!busy}
              onChange={(e) => void choose(e.target.files?.[0])}
            />
            {busy === 'validate' ? <LoaderCircle className="spin" size={22} /> : <FileUp size={22} />}
            <strong>{file ? file.name : 'Choose a CSV file or drop it here'}</strong>
            <span>
              UTF-8, comma or semicolon separated, header in the first row · up to 2,000 rows and 2 MB
            </span>
          </label>

          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}

          {done && (
            <div className="adm-note success" role="status">
              <CircleCheck size={16} />
              <span>
                <strong>{text(done.number)}</strong>{' '}
                {done.duplicate
                  ? 'already contained this exact file; nothing new was created.'
                  : `imported ${fmt(done.row_count)} rows from ${text(done.file_name)}.`}
              </span>
            </div>
          )}

          {result && (
            <>
              <div className="adm-result-head">
                <div className="adm-figures">
                  <div>
                    <span>Rows</span>
                    <strong>{fmt(result.rows)}</strong>
                  </div>
                  <div>
                    <span>Ready</span>
                    <strong>{fmt(result.validRows)}</strong>
                  </div>
                  <div>
                    <span>Errors</span>
                    <strong className={result.errors.length ? 'negative' : undefined}>
                      {fmt(result.errors.length)}
                    </strong>
                  </div>
                  {summary?.totalValue !== undefined && (
                    <div>
                      <span>Opening value</span>
                      <strong>{money(summary.totalValue)}</strong>
                    </div>
                  )}
                </div>
                <div className="adm-actions">
                  <button className="button secondary small" disabled={!!busy} onClick={() => reset()}>
                    Clear
                  </button>
                  <button
                    className="button primary small"
                    disabled={!!busy || !result.valid || !!result.alreadyImported}
                    onClick={commit}
                  >
                    {busy === 'commit' ? <LoaderCircle className="spin" size={13} /> : <Upload size={13} />}
                    Import {fmt(result.rows)} {result.rows === 1 ? 'row' : 'rows'}
                  </button>
                </div>
              </div>

              {result.alreadyImported ? (
                <div className="adm-note warning">
                  <ShieldAlert size={16} />
                  <span>
                    This exact file was already imported as{' '}
                    <strong>{text(result.alreadyImported.number)}</strong> on{' '}
                    {dateTime(result.alreadyImported.created_at)} by {text(result.alreadyImported.created_by)}
                    . Importing it again would create nothing.
                  </span>
                </div>
              ) : result.valid ? (
                <div className="adm-note success">
                  <CircleCheck size={16} />
                  <span>
                    Every row passed validation.
                    {summary?.totalValue !== undefined && (
                      <>
                        {' '}
                        The import posts{' '}
                        {Object.entries(accounts)
                          .map(
                            ([account, value]) =>
                              `${money(value)} to ${account.replaceAll('_', ' ').toLowerCase()}`,
                          )
                          .join(' and ')}
                        , credited to opening equity.
                      </>
                    )}
                  </span>
                </div>
              ) : (
                <div className="adm-note danger">
                  <TriangleAlert size={16} />
                  <span>
                    Fix the rows below in your spreadsheet and choose the file again. Nothing is imported
                    while any row has an error.
                  </span>
                </div>
              )}

              {result.errors.length > 0 && (
                <div className="adm-subtable">
                  <h3>
                    Errors <span>{fmt(result.errors.length)}</span>
                  </h3>
                  <Table
                    compact
                    pageSize={10}
                    rows={result.errors.map((e, i) => ({
                      id: String(i),
                      row: e.row,
                      column: e.column,
                      message: e.message,
                    }))}
                    columns={[
                      { label: 'Row', render: (r) => (r.row === null ? 'File' : text(r.row)) },
                      {
                        label: 'Column',
                        render: (r) => (r.column ? <code className="adm-mono">{text(r.column)}</code> : '—'),
                      },
                      {
                        label: 'Problem',
                        className: 'adm-error-cell',
                        render: (r) => text(r.message),
                      },
                    ]}
                  />
                </div>
              )}

              {result.preview.length > 0 && (
                <div className="adm-subtable">
                  <h3>
                    {result.valid ? 'Will be created' : 'Rows without errors'}
                    <span>
                      {result.preview.length < result.validRows
                        ? `first ${fmt(result.preview.length)} of ${fmt(result.validRows)}`
                        : fmt(result.preview.length)}
                    </span>
                  </h3>
                  <Table compact pageSize={10} rows={result.preview} columns={PREVIEW_COLUMNS[kind]} />
                </div>
              )}
            </>
          )}
        </div>
      </Panel>

      <Panel title="Import history" subtitle="Committed files. Each file’s content can be imported once.">
        <QueryState
          pending={history.query.isPending}
          error={history.query.error}
          retry={() => history.query.refetch()}
        >
          <Table
            rows={history.rows}
            compact
            pageSize={Math.max(history.rows.length, 1)}
            columns={[
              {
                label: 'Import',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.number)}</strong>
                    <span>
                      <FileSpreadsheet size={11} /> {text(r.file_name)}
                    </span>
                  </div>
                ),
              },
              { label: 'Type', render: (r) => LABELS[text(r.kind)] ?? text(r.kind) },
              { label: 'Rows', render: (r) => fmt(r.row_count) },
              { label: 'Value', render: (r) => (r.kind === 'OPENING_STOCK' ? money(r.total_value) : '—') },
              { label: 'By', key: 'created_by' },
              { label: 'Imported', render: (r) => dateTime(r.created_at) },
            ]}
          />
          {history.rows.length > 0 && <LoadMore query={history.query} count={history.rows.length} />}
        </QueryState>
      </Panel>
    </div>
  );
}
