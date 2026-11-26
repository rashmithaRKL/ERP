import { useState } from 'react';
import { Plus } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Table } from '../../components';
import { date, fmt, labelState, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { SEVERITIES } from '../quality/api';
import { QualitySearch, useDebounced } from '../quality/shared';
import { RECALL_ROLES, TRACEABILITY } from './api';
import { TraceBadge } from './shared';

const PAGE = 25;
const STATUS_TABS = ['active', 'OPEN', 'CONTAINED', 'CLOSED', 'CANCELLED', 'all'];

/** Recalls (`GET /api/recalls`): status tabs, severity filter, search (number, title, reason, suspect lot). */
export function RecallsTab({ onOpen, onNew }: { onOpen: (id: string) => void; onNew: () => void }) {
  const { can } = useWorkspace();
  const [status, setStatus] = useState('active');
  const [severity, setSeverity] = useState('');
  const [input, setInput] = useState('');
  const q = useDebounced(input);
  const paged = useCursorPage([TRACEABILITY, 'recalls'], '/recalls', { status, severity, q }, PAGE);
  return (
    <>
      <Tabs
        compact
        label="Recall status"
        items={STATUS_TABS.map((t) => ({
          value: t,
          label: t === 'active' ? 'Open & contained' : t === 'all' ? 'All' : labelState(t),
        }))}
        value={status}
        onChange={setStatus}
      />
      <div className="server-toolbar">
        <QualitySearch value={input} onChange={setInput} placeholder="Search recall, title or suspect lot…" />
        <select aria-label="Severity" value={severity} onChange={(e) => setSeverity(e.target.value)}>
          <option value="">All severities</option>
          {SEVERITIES.map((s) => (
            <option key={s} value={s}>
              {labelState(s)}
            </option>
          ))}
        </select>
        {can(...RECALL_ROLES) && (
          <button className="button primary small qa-toolbar-action" onClick={onNew}>
            <Plus size={14} />
            Open recall
          </button>
        )}
      </div>
      <PageState paged={paged}>
        {paged.rows.length ? (
          <Table
            rows={paged.rows}
            compact
            pageSize={PAGE}
            label="Recalls"
            onRow={(r) => onOpen(r.id)}
            columns={[
              {
                label: 'Recall',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.number)}</strong>
                    <span className="notes-cell">{text(r.title)}</span>
                  </div>
                ),
              },
              { label: 'Severity', render: (r) => <TraceBadge value={r.severity} /> },
              {
                label: 'Affected',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>
                      {fmt(r.in_stock_lots)} in stock of {fmt(r.affected_lots)} lots
                    </strong>
                    <span>
                      {r.status === 'OPEN' ? 'Not yet contained' : `${fmt(r.held_lots)} held`} ·{' '}
                      {r.scope === 'FORWARD' ? 'forward' : 'forward + siblings'}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Customers',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{fmt(r.customers)}</strong>
                    <span className={num(r.pending_customers) ? 'inv-attention' : undefined}>
                      {num(r.customers) ? `${fmt(r.pending_customers)} to notify` : 'none shipped'}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Status',
                render: (r) => (
                  <div className="cell-stack">
                    <TraceBadge value={r.status} />
                    {r.overridden === true && <span>by override</span>}
                  </div>
                ),
              },
              {
                label: 'Opened',
                render: (r) => (
                  <div className="cell-stack">
                    <span>{date(r.created_at)}</span>
                    <span>{text(r.created_by_name)}</span>
                  </div>
                ),
              },
            ]}
          />
        ) : (
          <Empty
            title="No recalls here"
            body="Open a recall from a suspect lot or a supplier's deliveries; the genealogy finds every affected lot and customer."
          />
        )}
      </PageState>
      <PageFooter paged={paged} label="recalls" />
    </>
  );
}
