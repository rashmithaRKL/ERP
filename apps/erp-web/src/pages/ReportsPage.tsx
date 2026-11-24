import { useEffect, useState } from 'react';
import { motion } from 'motion/react';
import {
  ArrowDownToLine,
  Boxes,
  CircleDollarSign,
  Layers,
  LoaderCircle,
  Search,
  TriangleAlert,
} from 'lucide-react';
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import { useStateData, useWorkspace } from '../app/workspace';
import { Empty, Panel, Stat, Table } from '../components';
import { PageFooter, PageState, useCursorPage } from '../features/lists/cursorPage';
import type { CursorPage } from '../features/lists/cursorPage';
import { request } from '../lib/api';
import type { Row } from '../lib/api';
import { csv, date, fmt, labelState, money, num, text } from '../lib/format';
import { stagger } from '../motion/motion';

const PAGE = 25;
/** Rows exported by the cost-register CSV at most (fetched page by page). */
const EXPORT_CAP = 5000;

/** Debounced copy of a text input (server-side search). */
function useDebounced(value: string, delay = 300) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timeout = setTimeout(() => setDebounced(value.trim()), delay);
    return () => clearTimeout(timeout);
  }, [value, delay]);
  return debounced;
}

/**
 * Route `/reports`: production costs, reconciliation and the posted journal (finance roles only). The cost register
 * and journal are server-paged (`GET /api/reports/costs`, `/api/reports/journal`); composition and reconciliation
 * use the server's tenant-wide cost totals.
 */
export default function ReportsPage() {
  const { days, summary, notify } = useWorkspace();
  const { output, scrap } = summary;
  const data = useStateData();
  const finance = !!data.financial;
  const [costSearch, setCostSearch] = useState('');
  const costQ = useDebounced(costSearch);
  const costs = useCursorPage(['reports', 'costs'], '/reports/costs', { q: costQ }, PAGE, finance);
  const [journalSearch, setJournalSearch] = useState('');
  const journalQ = useDebounced(journalSearch);
  const [account, setAccount] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const journal = useCursorPage(
    ['reports', 'journal'],
    '/reports/journal',
    { account, q: journalQ, from, to },
    50,
    finance,
  );
  const [accounts, setAccounts] = useState<string[]>([]);
  const listed = journal.query.data?.accounts;
  useEffect(() => {
    if (listed?.length) setAccounts(listed);
  }, [listed]);
  const [exporting, setExporting] = useState(false);
  const totals = costs.query.data?.summary || {};
  const totalsTo = journal.query.data?.totals || {};

  async function exportCosts() {
    setExporting(true);
    try {
      const rows: Row[] = [];
      let before = '';
      do {
        const params = new URLSearchParams({ limit: '200' });
        if (costQ) params.set('q', costQ);
        if (before) params.set('before', before);
        const page = await request<CursorPage>(`/reports/costs?${params}`);
        rows.push(...page.items);
        before = page.next;
      } while (before && rows.length < EXPORT_CAP);
      csv('manufacturing-costs', rows);
      notify(
        before
          ? `Cost register downloaded with the first ${fmt(EXPORT_CAP)} orders; narrow the search for the rest.`
          : 'Cost register downloaded successfully.',
      );
    } catch (e) {
      notify((e as Error).message);
    } finally {
      setExporting(false);
    }
  }

  if (!finance)
    return (
      <Empty
        title="Financial reports require an authorized role"
        body="Ask your administrator for the appropriate access."
      />
    );
  const materials = num(totals.material_cost);
  const conversion = num(totals.conversion_cost);
  return (
    <>
      <motion.div className="stats-grid" variants={stagger} initial="hidden" animate="visible">
        <Stat
          label="Posted manufacturing cost"
          value={money(data.financial!.actual_cost)}
          icon={CircleDollarSign}
          note="Materials + conversion"
        />
        <Stat
          label="Unfinished WIP value"
          value={money(data.financial!.wip)}
          icon={Layers}
          note="Remains in production"
        />
        <Stat
          label="Recorded good output"
          value={fmt(output)}
          icon={Boxes}
          note={`${days}-day reporting period`}
        />
        <Stat label="Recorded scrap" value={fmt(scrap)} icon={TriangleAlert} note="Expensed separately" />
      </motion.div>
      <div className="reports-layout">
        <Panel
          title="Production cost composition"
          subtitle={`Posted cost across all ${fmt(totals.orders)} manufacturing orders`}
        >
          <div className="cost-composition">
            <div className="cost-donut">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    isAnimationActive={false}
                    data={[
                      { name: 'Raw materials', value: materials },
                      { name: 'Labour & machinery', value: conversion },
                    ]}
                    dataKey="value"
                    innerRadius={56}
                    outerRadius={78}
                    paddingAngle={3}
                  >
                    <Cell fill="#d72638" />
                    <Cell fill="#24364d" />
                  </Pie>
                  <Tooltip formatter={(v) => money(v)} />
                </PieChart>
              </ResponsiveContainer>
            </div>
            <div>
              <p>
                <i className="legend-dot red" />
                Raw materials
                <strong>{money(materials)}</strong>
              </p>
              <p>
                <i className="legend-dot navy" />
                Labour & machinery
                <strong>{money(conversion)}</strong>
              </p>
            </div>
          </div>
        </Panel>
        <Panel title="Cost reconciliation" subtitle="Material + conversion = WIP + finished output + scrap">
          <div className="reconciliation">
            {['material_cost', 'conversion_cost', 'wip_cost', 'finished_cost', 'scrap_cost'].map((k) => (
              <div key={k}>
                <span>{labelState(k)}</span>
                <strong>{money(totals[k])}</strong>
              </div>
            ))}
          </div>
        </Panel>
      </div>
      <Panel
        title="Manufacturing cost register"
        className="spaced-panel server-table"
        action={
          <button className="button small secondary" disabled={exporting} onClick={exportCosts}>
            {exporting ? <LoaderCircle className="spin" size={14} /> : <ArrowDownToLine size={14} />}
            CSV
          </button>
        }
      >
        <div className="server-toolbar">
          <div className="search-input">
            <Search size={16} />
            <input
              aria-label="Search the cost register"
              placeholder="Search order, product or SKU…"
              value={costSearch}
              onChange={(e) => setCostSearch(e.target.value)}
            />
          </div>
          <span className="muted">{fmt(costs.query.data?.total)} orders</span>
        </div>
        <PageState paged={costs}>
          <Table
            rows={costs.rows}
            compact
            pageSize={PAGE}
            columns={[
              { label: 'Order', key: 'number' },
              { label: 'Product', key: 'product' },
              { label: 'Estimated materials', render: (r) => money(r.estimated_cost) },
              { label: 'Actual materials', render: (r) => money(r.material_cost) },
              { label: 'Conversion', render: (r) => money(r.conversion_cost) },
              { label: 'WIP', render: (r) => money(r.wip_cost) },
              { label: 'Finished cost', render: (r) => money(r.finished_cost) },
            ]}
          />
        </PageState>
        <PageFooter paged={costs} label="orders" />
      </Panel>
      <Panel
        title="Posted journal"
        subtitle="Immutable, balanced manufacturing and delivery postings"
        className="spaced-panel server-table"
      >
        <div className="server-toolbar">
          <div className="search-input">
            <Search size={16} />
            <input
              aria-label="Search the journal"
              placeholder="Search reference or description…"
              value={journalSearch}
              onChange={(e) => setJournalSearch(e.target.value)}
            />
          </div>
          <select aria-label="Account" value={account} onChange={(e) => setAccount(e.target.value)}>
            <option value="">All accounts</option>
            {accounts.map((a) => (
              <option key={a} value={a}>
                {labelState(a)}
              </option>
            ))}
          </select>
          <input
            type="date"
            aria-label="Posted from"
            value={from}
            max={to || undefined}
            onChange={(e) => setFrom(e.target.value)}
          />
          <input
            type="date"
            aria-label="Posted to"
            value={to}
            min={from || undefined}
            onChange={(e) => setTo(e.target.value)}
          />
          <span className="muted">
            Debits {money(totalsTo.debit)} · credits {money(totalsTo.credit)}
          </span>
        </div>
        <PageState paged={journal}>
          <Table
            rows={journal.rows}
            compact
            pageSize={50}
            columns={[
              {
                label: 'Reference',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.reference)}</strong>
                    <span>{date(r.created_at)}</span>
                  </div>
                ),
              },
              { label: 'Description', key: 'description' },
              { label: 'Account', render: (r) => labelState(r.account) },
              { label: 'Debit', render: (r) => (num(r.debit) ? money(r.debit) : '—') },
              { label: 'Credit', render: (r) => (num(r.credit) ? money(r.credit) : '—') },
            ]}
          />
        </PageState>
        <PageFooter paged={journal} label="journal lines" />
      </Panel>
    </>
  );
}
