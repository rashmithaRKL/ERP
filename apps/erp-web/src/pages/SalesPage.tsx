import { useEffect, useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { ChevronLeft, ChevronRight, Plus, Search } from 'lucide-react';
import { useStateData, useWorkspace } from '../app/workspace';
import { Badge, Panel, Progress, Table } from '../components';
import { PartnerDirectory } from '../features/partners/PartnerDirectory';
import { useSalesOrders } from '../features/sales/api';
import type { SalesOrderDetail } from '../features/sales/api';
import { DispatchDialog } from '../features/sales/DispatchDialog';
import { onNewSalesOrder } from '../features/sales/events';
import { SalesOrderDrawer } from '../features/sales/SalesOrderDrawer';
import { SalesOrderEditor } from '../features/sales/SalesOrderEditor';
import type { Row } from '../lib/api';
import { date, fmt, labelState, money, num, text, today } from '../lib/format';
import { Tabs } from '../motion/motion';

const PAGE = 10;
const TABS = ['all', 'open', 'DRAFT', 'CONFIRMED', 'PARTIAL', 'DELIVERED', 'CLOSED', 'CANCELLED'];

/** Route `/sales`: server-paged sales orders with their detail drawer, editor and dispatch, and the customer directory. */
export default function SalesPage() {
  const { can } = useWorkspace();
  const data = useStateData();
  const [status, setStatus] = useState('open');
  const [input, setInput] = useState('');
  const [q, setQ] = useState('');
  const [cursors, setCursors] = useState<string[]>([]);
  const [selected, setSelected] = useState<string>();
  const [editor, setEditor] = useState<{ detail?: SalesOrderDetail }>();
  const [dispatch, setDispatch] = useState<{ detail: SalesOrderDetail; line: Row }>();
  useEffect(() => onNewSalesOrder(() => setEditor({})), []);
  useEffect(() => {
    const timeout = setTimeout(() => {
      setQ(input.trim());
      setCursors([]);
    }, 300);
    return () => clearTimeout(timeout);
  }, [input]);
  const before = cursors[cursors.length - 1];
  const page = useSalesOrders({ status, q, before, limit: PAGE });
  const counts = page.data?.counts || {};
  const count = (...s: string[]) => s.reduce((sum, x) => sum + num(counts[x]), 0);
  // Summary figures ignore the search box: one unfiltered row, counts only.
  const overview = useSalesOrders({ status: 'all', q: '', limit: 1 });
  const total = (...s: string[]) => s.reduce((sum, x) => sum + num(overview.data?.counts[x]), 0);
  const returnsAwaitingQc = data.inspections.filter(
    (i) => i.type === 'RETURN' && i.status === 'PENDING',
  ).length;
  const rows = page.data?.items || [];
  const start = cursors.length * PAGE;
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Open orders</span>
          <strong>{fmt(total('CONFIRMED', 'PARTIAL'))}</strong>
        </div>
        <div>
          <span>Drafts to confirm</span>
          <strong>{fmt(total('DRAFT'))}</strong>
        </div>
        <div>
          <span>Delivered</span>
          <strong>{fmt(total('DELIVERED'))}</strong>
        </div>
        <div>
          <span>Returns awaiting QC</span>
          <strong className={returnsAwaitingQc ? 'overdue' : ''}>{fmt(returnsAwaitingQc)}</strong>
        </div>
      </div>
      <Panel className="server-table">
        <Tabs
          label="Sales order status filter"
          items={TABS.map((t) => ({
            value: t,
            label: t === 'all' ? 'All orders' : t === 'open' ? 'Open' : labelState(t),
            count:
              t === 'all'
                ? count(...TABS.slice(2))
                : t === 'open'
                  ? count('CONFIRMED', 'PARTIAL')
                  : page.data
                    ? num(counts[t])
                    : undefined,
          }))}
          value={status}
          onChange={(v) => {
            setStatus(v);
            setCursors([]);
          }}
        />
        <div className="server-toolbar">
          <div className="search-input">
            <Search size={16} />
            <input
              aria-label="Search sales orders"
              placeholder="Search order, customer, PO reference or product…"
              value={input}
              onChange={(e) => setInput(e.target.value)}
            />
          </div>
          {can('PLANNER', 'SALES') ? (
            <button className="button secondary small" onClick={() => setEditor({})}>
              <Plus size={14} />
              New order
            </button>
          ) : (
            <span className="muted">{fmt(page.data?.total)} orders</span>
          )}
        </div>
        {page.isPending ? (
          <div className="server-loading">
            <div className="skeleton" />
          </div>
        ) : page.isError ? (
          <div className="form-error server-error" role="alert">
            {(page.error as Error).message}
            <div>
              <button className="button secondary small" onClick={() => page.refetch()}>
                Retry
              </button>
            </div>
          </div>
        ) : (
          <Table
            rows={rows}
            compact
            pageSize={PAGE}
            onRow={(r) => setSelected(r.id)}
            columns={[
              {
                label: 'Sales order',
                render: (r) => (
                  <div className="cell-stack">
                    <button
                      className="text-button order-number"
                      onClick={(e) => {
                        e.stopPropagation();
                        setSelected(r.id);
                      }}
                    >
                      {text(r.number)}
                    </button>
                    <span>{text(r.customer)}</span>
                  </div>
                ),
              },
              {
                label: 'Products',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>
                      {fmt(r.line_count)} {num(r.line_count) === 1 ? 'line' : 'lines'}
                    </strong>
                    <span className="products-cell" title={text(r.products)}>
                      {text(r.products) || 'No lines yet'}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Fulfilment',
                render: (r) => (
                  <div className="table-progress">
                    <span>
                      {fmt(r.delivered)} <em>/ {fmt(r.quantity)}</em> {text(r.uom)}
                      {num(r.returned) > 0 && <em> · {fmt(r.returned)} returned</em>}
                    </span>
                    <Progress value={num(r.quantity) ? (num(r.delivered) / num(r.quantity)) * 100 : 0} />
                  </div>
                ),
              },
              { label: 'Total', render: (r) => money(r.total) },
              {
                label: 'Required by',
                render: (r) => (
                  <div className="cell-stack">
                    <strong
                      className={
                        text(r.due_date) < today() && ['CONFIRMED', 'PARTIAL'].includes(text(r.status))
                          ? 'overdue'
                          : ''
                      }
                    >
                      {date(r.due_date)}
                    </strong>
                    <span>{text(r.customer_reference) || '—'}</span>
                  </div>
                ),
              },
              { label: 'Status', render: (r) => <Badge value={r.status} /> },
            ]}
          />
        )}
        <div className="table-footer">
          <span>
            Showing {rows.length ? start + 1 : 0}–{start + rows.length} of {fmt(page.data?.total)}
          </span>
          <div>
            <button
              className="icon-button"
              aria-label="Previous page"
              disabled={!cursors.length || page.isFetching}
              onClick={() => setCursors(cursors.slice(0, -1))}
            >
              <ChevronLeft size={16} />
            </button>
            <span>{cursors.length + 1}</span>
            <button
              className="icon-button"
              aria-label="Next page"
              disabled={!page.data?.next || page.isFetching}
              onClick={() => page.data?.next && setCursors([...cursors, page.data.next])}
            >
              <ChevronRight size={16} />
            </button>
          </div>
        </div>
      </Panel>
      <PartnerDirectory kind="CUSTOMER" />
      <AnimatePresence>
        {selected && (
          <SalesOrderDrawer
            key={`sales-order-${selected}`}
            orderId={selected}
            onClose={() => setSelected(undefined)}
            onEdit={(detail) => setEditor({ detail })}
            onDispatch={(detail, line) => setDispatch({ detail, line })}
          />
        )}
        {editor && (
          <SalesOrderEditor
            key="sales-order-editor"
            detail={editor.detail}
            onClose={() => setEditor(undefined)}
            onSaved={(id) => setSelected(id)}
          />
        )}
        {dispatch && (
          <DispatchDialog
            key="sales-dispatch"
            order={dispatch.detail.order}
            line={dispatch.line}
            lots={dispatch.detail.lots}
            deliveries={dispatch.detail.deliveries.length}
            onClose={() => setDispatch(undefined)}
          />
        )}
      </AnimatePresence>
    </>
  );
}
