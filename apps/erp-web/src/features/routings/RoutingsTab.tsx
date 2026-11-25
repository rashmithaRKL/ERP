import { useEffect, useState } from 'react';
import { Search } from 'lucide-react';
import { Panel, Table } from '../../components';
import { date, fmt, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { minutesText } from './api';
import { StatusChip } from './shared';

const PAGE = 25;

/** Routings tab: keyset-paged routing revisions (`GET /api/routings`) with status tabs and search. */
export function RoutingsTab({ onOpen }: { onOpen: (id: string) => void }) {
  const [status, setStatus] = useState('all');
  const [input, setInput] = useState('');
  const [q, setQ] = useState('');
  useEffect(() => {
    const timeout = setTimeout(() => setQ(input.trim()), 300);
    return () => clearTimeout(timeout);
  }, [input]);
  const paged = useCursorPage(['routings', 'list'], '/routings', { status, q }, PAGE);
  const counts = paged.query.data?.counts as Record<string, number> | undefined;
  return (
    <Panel className="server-table">
      <Tabs
        label="Routing filters"
        value={status}
        onChange={setStatus}
        items={[
          { value: 'all', label: 'All revisions', count: counts && num(counts.total) },
          { value: 'current', label: 'Current', count: counts && num(counts.current) },
          { value: 'APPROVED', label: 'Approved', count: counts && num(counts.approved) },
          { value: 'DRAFT', label: 'Drafts', count: counts && num(counts.draft) },
        ]}
      />
      <div className="server-toolbar">
        <div className="search-input">
          <Search size={16} />
          <input
            aria-label="Search routings"
            placeholder="Search product, SKU, revision or number…"
            value={input}
            onChange={(e) => setInput(e.target.value)}
          />
        </div>
        <span className="muted">{fmt(paged.query.data?.total)} revisions</span>
      </div>
      <PageState paged={paged}>
        <Table
          label="Routing revisions"
          rows={paged.rows}
          compact
          pageSize={PAGE}
          onRow={(r) => onOpen(r.id)}
          columns={[
            {
              label: 'Product',
              render: (r) => (
                <span className="cell-stack">
                  <strong>{text(r.product)}</strong>
                  <small>{text(r.sku)}</small>
                </span>
              ),
            },
            {
              label: 'Revision',
              render: (r) => (
                <span className="cell-stack">
                  <strong>
                    {text(r.revision)}
                    {r.current && <span className="bom-chip level inline-chip">current</span>}
                  </strong>
                  <small>{text(r.number)}</small>
                </span>
              ),
            },
            { label: 'Status', render: (r) => <StatusChip value={r.status} /> },
            { label: 'Operations', render: (r) => fmt(r.operation_count) },
            { label: 'Setup', render: (r) => minutesText(r.setup_minutes) },
            { label: 'Run / 1,000', render: (r) => minutesText(num(r.run_minutes_per_unit) * 1000) },
            {
              label: 'Authored',
              render: (r) => (
                <span className="cell-stack">
                  <strong>{text(r.created_by_name)}</strong>
                  <small>{date(r.created_at)}</small>
                </span>
              ),
            },
            {
              label: 'Approved',
              render: (r) =>
                r.approved_by_name ? (
                  <span className="cell-stack">
                    <strong>{text(r.approved_by_name)}</strong>
                    <small>{date(r.approved_at)}</small>
                  </span>
                ) : (
                  <span className="muted">Awaiting approval</span>
                ),
            },
          ]}
        />
      </PageState>
      <PageFooter paged={paged} label="revisions" />
    </Panel>
  );
}
