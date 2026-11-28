import { useState } from 'react';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { ArrowDownToLine, Info, LoaderCircle, X } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Panel, Table } from '../../components';
import { request } from '../../lib/api';
import type { Row, Value } from '../../lib/api';
import { money, text, today } from '../../lib/format';
import { download, longDate, params, useDebounced } from '../admin/api';
import { LoadMore, QueryState, SearchBox, Tone } from '../admin/shared';
import { KIND_LABELS, pct, previousMonth, TAX, useTaxCodes } from './api';

type RegisterPage = { items: Row[]; next: string; summary: Record<string, Value> };
type TaxReturn = {
  from: string;
  to: string;
  codes: Row[];
  components: Row[];
  totals: Record<string, number>;
  empty: boolean;
  currency: string;
};

/** Shown while nothing has been recorded: V25 calculates tax but no document posts it yet. */
function NothingRecorded({ filtered }: { filtered: boolean }) {
  return (
    <div className="adm-note tax-empty">
      <Info size={16} />
      <span>
        {filtered ? (
          <>
            <strong>No tax lines match these filters.</strong> Widen the dates or clear the filters.
          </>
        ) : (
          <>
            <strong>No tax has been recorded yet.</strong> Tax codes, rates and estimates are ready, but no
            document posts tax yet: the register fills when customer invoices and supplier invoices with tax
            are posted (receivables and payables, the next finance features).
          </>
        )}
      </span>
    </div>
  );
}

/** The tax register (`tax_lines`): server-paged with period, direction, code and text filters, and a CSV export. */
export function TaxRegisterPanel() {
  const { notify } = useWorkspace();
  const codes = useTaxCodes();
  const [search, setSearch] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [direction, setDirection] = useState('');
  const [code, setCode] = useState('');
  const [exporting, setExporting] = useState(false);
  const q = useDebounced(search);
  const filters = { q, from, to, direction, code };
  const query = useInfiniteQuery({
    queryKey: [TAX, 'register', filters],
    queryFn: ({ pageParam }) =>
      request<RegisterPage>(`/tax/register${params({ ...filters, limit: 50, before: pageParam })}`),
    initialPageParam: '',
    getNextPageParam: (last: RegisterPage) => last.next || undefined,
  });
  const rows = query.data?.pages.flatMap((p) => p.items) ?? [];
  const summary = query.data?.pages[0]?.summary;
  const filtered = !!(search || from || to || direction || code);

  async function exportCsv() {
    setExporting(true);
    try {
      await download(`/tax/register/export${params(filters)}`, 'tax-register.csv');
      notify('Tax register downloaded.');
    } catch (e) {
      notify((e as Error).message);
    } finally {
      setExporting(false);
    }
  }

  return (
    <Panel
      title="Tax register"
      subtitle="Every recorded tax line: one row per document line and tax component, by posting date. Posted history is immutable."
      action={
        <button className="button secondary small" onClick={exportCsv} disabled={exporting || !rows.length}>
          {exporting ? <LoaderCircle className="spin" size={14} /> : <ArrowDownToLine size={14} />}
          Export CSV
        </button>
      }
    >
      <div className="adm-toolbar">
        <div className="adm-filters">
          <SearchBox value={search} onChange={setSearch} placeholder="Search document or partner…" />
          <select aria-label="Direction" value={direction} onChange={(e) => setDirection(e.target.value)}>
            <option value="">Output and input</option>
            <option value="OUTPUT">Output tax (sales)</option>
            <option value="INPUT">Input tax (purchases)</option>
          </select>
          <select aria-label="Tax code" value={code} onChange={(e) => setCode(e.target.value)}>
            <option value="">Any tax code</option>
            {codes.data?.items.map((c) => (
              <option key={c.id} value={c.id}>
                {text(c.code)}
              </option>
            ))}
          </select>
          <label className="adm-date">
            From
            <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="adm-date">
            To
            <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
          </label>
          {filtered && (
            <button
              className="text-button"
              onClick={() => {
                setSearch('');
                setFrom('');
                setTo('');
                setDirection('');
                setCode('');
              }}
            >
              <X size={13} />
              Clear filters
            </button>
          )}
        </div>
        {summary && Number(summary.lines) > 0 && (
          <div className="adm-actions">
            <span className="adm-chip">
              Output tax <strong>{money(summary.output_tax)}</strong>
            </span>
            <span className="adm-chip">
              Input tax <strong>{money(summary.input_tax)}</strong>
            </span>
          </div>
        )}
      </div>
      <QueryState pending={query.isPending} error={query.error} retry={() => query.refetch()}>
        {rows.length ? (
          <>
            <Table
              rows={rows}
              compact
              label="Tax register"
              pageSize={Math.max(rows.length, 1)}
              columns={[
                {
                  label: 'Document',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>{text(r.source_number) || text(r.source_id)}</strong>
                      <span>
                        {text(r.source_type).replaceAll('_', ' ').toLowerCase()} · line {text(r.line_no)}
                      </span>
                    </div>
                  ),
                },
                {
                  label: 'Posting date',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>{longDate(r.posting_date)}</strong>
                      <span>document {longDate(r.document_date)}</span>
                    </div>
                  ),
                },
                {
                  label: 'Partner',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>{text(r.partner) || '—'}</strong>
                      <span>{text(r.partner_tax_id) || 'No tax ID'}</span>
                    </div>
                  ),
                },
                {
                  label: 'Tax code',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>{text(r.code)}</strong>
                      <span>
                        {r.component ? `${text(r.component)} ${pct(r.rate)}` : KIND_LABELS[text(r.kind)]}
                      </span>
                    </div>
                  ),
                },
                {
                  label: 'Direction',
                  render: (r) =>
                    r.direction === 'OUTPUT' ? (
                      <Tone tone="blue">Output</Tone>
                    ) : (
                      <Tone tone="gray">Input</Tone>
                    ),
                },
                { label: 'Base', render: (r) => money(r.base) },
                { label: 'Tax', render: (r) => money(r.tax) },
              ]}
            />
            <LoadMore query={query} count={rows.length} />
          </>
        ) : (
          <NothingRecorded filtered={filtered} />
        )}
      </QueryState>
    </Panel>
  );
}

/** VAT return summary for a period: output vs input tax per code, standard/zero-rated/exempt bases, net payable. */
export function TaxReturnPanel() {
  const { notify } = useWorkspace();
  const initial = previousMonth(today());
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [exporting, setExporting] = useState(false);
  const valid = !!from && !!to && from <= to;
  const query = useQuery({
    queryKey: [TAX, 'return', from, to],
    queryFn: () => request<TaxReturn>(`/tax/return${params({ from, to })}`),
    enabled: valid,
  });
  const data = query.data;
  const t = data?.totals;

  async function exportCsv() {
    setExporting(true);
    try {
      await download(`/tax/return/export${params({ from, to })}`, `vat-return-${from}-to-${to}.csv`);
      notify('VAT return summary downloaded.');
    } catch (e) {
      notify((e as Error).message);
    } finally {
      setExporting(false);
    }
  }

  return (
    <Panel
      title="VAT return summary"
      subtitle="Output tax on sales against input tax on purchases for the period, by posting date. A working summary for your accountant, not a filed return."
      action={
        <button
          className="button secondary small"
          onClick={exportCsv}
          disabled={exporting || !valid || !data || data.empty}
        >
          {exporting ? <LoaderCircle className="spin" size={14} /> : <ArrowDownToLine size={14} />}
          Export CSV
        </button>
      }
    >
      <div className="adm-toolbar">
        <div className="adm-filters">
          <label className="adm-date">
            From
            <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="adm-date">
            To
            <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
          </label>
        </div>
        <span className="adm-muted">
          {valid ? `${longDate(from)} – ${longDate(to)}` : 'Choose a start date on or before the end date.'}
        </span>
      </div>
      {valid && (
        <QueryState pending={query.isPending} error={query.error} retry={() => query.refetch()}>
          {data && t && (
            <>
              <div className="tax-figures" style={{ paddingTop: 16 }}>
                <div>
                  <span>Output tax</span>
                  <strong>{money(t.output_tax)}</strong>
                  <small>on sales</small>
                </div>
                <div>
                  <span>Input tax</span>
                  <strong>{money(t.input_tax)}</strong>
                  <small>on purchases</small>
                </div>
                <div>
                  <span>{Number(t.net_payable) < 0 ? 'Net refundable' : 'Net payable'}</span>
                  <strong>{money(Math.abs(Number(t.net_payable)))}</strong>
                  <small>output − input</small>
                </div>
                <div>
                  <span>Sales bases</span>
                  <strong>{money(t.output_standard_base)}</strong>
                  <small>
                    zero-rated {money(t.output_zero_rated_base)} · exempt {money(t.output_exempt_base)}
                  </small>
                </div>
              </div>
              {data.empty ? (
                <NothingRecorded filtered={false} />
              ) : (
                <Table
                  rows={data.codes}
                  compact
                  label="VAT return by tax code"
                  pageSize={Math.max(data.codes.length, 1)}
                  columns={[
                    {
                      label: 'Tax code',
                      render: (r) => (
                        <div className="cell-stack">
                          <strong>{text(r.code)}</strong>
                          <span>
                            {KIND_LABELS[text(r.kind)]} · {text(r.documents)} documents
                          </span>
                        </div>
                      ),
                    },
                    { label: 'Sales base', render: (r) => money(r.output_base) },
                    { label: 'Output tax', render: (r) => money(r.output_tax) },
                    { label: 'Purchase base', render: (r) => money(r.input_base) },
                    { label: 'Input tax', render: (r) => money(r.input_tax) },
                  ]}
                />
              )}
            </>
          )}
        </QueryState>
      )}
    </Panel>
  );
}
