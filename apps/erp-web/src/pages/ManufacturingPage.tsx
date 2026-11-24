import { useEffect, useState } from 'react';
import { Search } from 'lucide-react';
import { useWorkspace } from '../app/workspace';
import { Panel, Table } from '../components';
import { PageFooter, PageState, useCursorPage } from '../features/lists/cursorPage';
import { useOrderColumns } from '../features/manufacturing/orderColumns';
import { fmt, labelState, num } from '../lib/format';
import { Tabs } from '../motion/motion';

const PAGE = 25;
const TABS = ['all', 'active', 'DRAFT', 'COMPLETED', 'CLOSED'];
const ACTIVE = [
  'DRAFT',
  'APPROVED',
  'MATERIALS_RESERVED',
  'RELEASED',
  'IN_PROGRESS',
  'ON_HOLD',
  'PARTIALLY_COMPLETED',
  'QUALITY_REVIEW',
];

/** Route `/manufacturing`: server-paged manufacturing order list (`GET /api/manufacturing/orders`). */
export default function ManufacturingPage() {
  const { openOrder } = useWorkspace();
  const orderColumns = useOrderColumns();
  const [tab, setTab] = useState('all');
  const [input, setInput] = useState('');
  const [q, setQ] = useState('');
  useEffect(() => {
    const timeout = setTimeout(() => setQ(input.trim()), 300);
    return () => clearTimeout(timeout);
  }, [input]);
  const paged = useCursorPage(['manufacturing', 'orders'], '/manufacturing/orders', { status: tab, q }, PAGE);
  // Tenant-wide counts per status (the server ignores the search box for these).
  const counts = paged.query.data?.counts || {};
  const count = (...statuses: string[]) => statuses.reduce((sum, s) => sum + num(counts[s]), 0);
  const loaded = !!paged.query.data;
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Active orders</span>
          <strong>{loaded ? fmt(count(...ACTIVE)) : '—'}</strong>
        </div>
        <div>
          <span>Awaiting approval</span>
          <strong>{loaded ? fmt(count('DRAFT')) : '—'}</strong>
        </div>
        <div>
          <span>In production</span>
          <strong>{loaded ? fmt(count('IN_PROGRESS', 'PARTIALLY_COMPLETED')) : '—'}</strong>
        </div>
        <div>
          <span>Quality review</span>
          <strong>{loaded ? fmt(count('QUALITY_REVIEW')) : '—'}</strong>
        </div>
      </div>
      <Panel className="server-table">
        <Tabs
          label="Manufacturing order filters"
          items={TABS.map((t) => ({
            value: t,
            label: t === 'all' ? 'All orders' : labelState(t),
            count: !loaded
              ? undefined
              : t === 'all'
                ? count(...Object.keys(counts))
                : t === 'active'
                  ? count(...ACTIVE)
                  : num(counts[t]),
          }))}
          value={tab}
          onChange={setTab}
        />
        <div className="server-toolbar">
          <div className="search-input">
            <Search size={16} />
            <input
              aria-label="Search manufacturing orders"
              placeholder="Search order, customer, product or work center…"
              value={input}
              onChange={(e) => setInput(e.target.value)}
            />
          </div>
          <span className="muted">{fmt(paged.query.data?.total)} orders</span>
        </div>
        <PageState paged={paged}>
          <Table
            rows={paged.rows}
            columns={orderColumns}
            compact
            pageSize={PAGE}
            onRow={(r) => openOrder(r.id)}
          />
        </PageState>
        <PageFooter paged={paged} label="orders" />
      </Panel>
    </>
  );
}
