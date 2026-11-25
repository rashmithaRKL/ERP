import { useState } from 'react';
import { Plus } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Progress, Table } from '../../components';
import type { Workspace } from '../../app/workspace';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, labelState, num, text, today } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { DECIDERS } from './api';
import { QualityBadge, QualitySearch, useDebounced } from './shared';

const PAGE = 25;
const TABS = ['active', 'OPEN', 'IN_PROGRESS', 'VERIFICATION', 'CLOSED', 'all'];

/** Opens the "New CAPA" form; `ncr` links the CAPA to that nonconformance. */
export function createCapa(ws: Workspace, people: Row[], ncr?: Row) {
  ws.openForm({
    title: ncr ? `CAPA for ${text(ncr.number)}` : 'New CAPA',
    description: 'Corrective and preventive action: record the problem now, the root cause and actions next.',
    submit: 'Create CAPA',
    fields: [
      { name: 'title', label: 'Title', defaultValue: ncr ? text(ncr.title) : undefined },
      {
        name: 'problem',
        label: 'Problem statement',
        type: 'textarea',
        defaultValue: ncr ? text(ncr.description) : undefined,
      },
      {
        name: 'source',
        label: 'Source',
        options: ['NCR', 'AUDIT', 'CUSTOMER', 'SUPPLIER', 'INTERNAL'].map((s) => ({
          value: s,
          label: labelState(s),
        })),
        defaultValue: ncr ? 'NCR' : 'INTERNAL',
      },
      {
        name: 'ownerId',
        label: 'Owner',
        options: people.map((p) => ({ value: p.id, label: `${text(p.name)} · ${labelState(p.role)}` })),
        defaultValue: ws.user.id,
      },
      { name: 'dueDate', label: 'Target date', type: 'date', optional: true, min: today() },
    ],
    run: (v, key) =>
      request('/quality/capas', { ...v, dueDate: v.dueDate || null, ncrIds: ncr ? [ncr.id] : [] }, key),
  });
}

/** CAPAs (`GET /api/quality/capas`): status tabs, overdue filter and search. */
export function CapaTab({ onOpen, people }: { onOpen: (id: string) => void; people: Row[] }) {
  const ws = useWorkspace();
  const [status, setStatus] = useState('active');
  const [overdue, setOverdue] = useState(false);
  const [input, setInput] = useState('');
  const q = useDebounced(input);
  const paged = useCursorPage(
    ['quality', 'capas'],
    '/quality/capas',
    { status, q, overdue: overdue ? 'true' : undefined },
    PAGE,
  );
  const counts = paged.query.data?.counts || {};
  const loaded = !!paged.query.data;
  return (
    <>
      <Tabs
        compact
        label="CAPA status"
        items={TABS.map((t) => ({
          value: t,
          label: t === 'active' ? 'Active' : t === 'all' ? 'All' : labelState(t),
          count: !loaded
            ? undefined
            : t === 'active'
              ? num(counts.OPEN) + num(counts.IN_PROGRESS) + num(counts.VERIFICATION)
              : t === 'all'
                ? undefined
                : num(counts[t]),
        }))}
        value={status}
        onChange={setStatus}
      />
      <div className="server-toolbar">
        <QualitySearch value={input} onChange={setInput} placeholder="Search CAPA, title or owner…" />
        <label className="inv-check">
          <input type="checkbox" checked={overdue} onChange={(e) => setOverdue(e.target.checked)} />
          Overdue only
          {loaded && num(counts.OVERDUE_ACTIONS) ? ` (${num(counts.OVERDUE_ACTIONS)} actions)` : ''}
        </label>
        {ws.can(...DECIDERS) && (
          <button className="button primary small qa-toolbar-action" onClick={() => createCapa(ws, people)}>
            <Plus size={14} />
            New CAPA
          </button>
        )}
      </div>
      <PageState paged={paged}>
        {paged.rows.length ? (
          <Table
            rows={paged.rows}
            compact
            pageSize={PAGE}
            label="Corrective and preventive actions"
            onRow={(r) => onOpen(r.id)}
            columns={[
              {
                label: 'CAPA',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.number)}</strong>
                    <span className="notes-cell">{text(r.title)}</span>
                  </div>
                ),
              },
              { label: 'Owner', key: 'owner' },
              {
                label: 'Actions',
                render: (r) => (
                  <div className="table-progress">
                    <Progress value={num(r.actions) ? (num(r.actions_done) / num(r.actions)) * 100 : 0} />
                    <span>
                      {num(r.actions_done)} / {num(r.actions)} done
                      {num(r.overdue_actions) ? ` · ${num(r.overdue_actions)} overdue` : ''}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Target',
                render: (r) =>
                  r.due_date ? (
                    <span className={r.overdue ? 'negative' : undefined}>{date(r.due_date)}</span>
                  ) : (
                    '—'
                  ),
              },
              { label: 'NCRs', render: (r) => <span className="notes-cell">{text(r.ncrs) || '—'}</span> },
              {
                label: 'Status',
                render: (r) => (
                  <div className="cell-stack">
                    <QualityBadge value={r.status} />
                    {num(r.overdue_actions) > 0 && <QualityBadge value="OVERDUE" label="Action overdue" />}
                  </div>
                ),
              },
            ]}
          />
        ) : (
          <Empty
            title="No CAPAs here"
            body="Open a CAPA from a nonconformance, an audit finding or a customer complaint."
          />
        )}
      </PageState>
      <PageFooter paged={paged} label="CAPAs" />
    </>
  );
}
