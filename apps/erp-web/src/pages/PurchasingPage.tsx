import { useEffect, useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { ChevronLeft, ChevronRight, Layers, Plus, Search } from 'lucide-react';
import { useStateData, useWorkspace } from '../app/workspace';
import { Panel, Progress, Table } from '../components';
import { PartnerDirectory } from '../features/partners/PartnerDirectory';
import { statusLabel, usePurchaseOrders } from '../features/purchasing/api';
import type { PurchaseOrderDetail } from '../features/purchasing/api';
import { DocumentsPanel } from '../features/purchasing/DocumentsPanel';
import { onNewPurchaseOrder } from '../features/purchasing/events';
import { InvoiceDialog } from '../features/purchasing/InvoiceDialog';
import { PurchaseOrderDrawer } from '../features/purchasing/PurchaseOrderDrawer';
import { PurchaseOrderEditor } from '../features/purchasing/PurchaseOrderEditor';
import { PurchaseStatus } from '../features/purchasing/PurchaseStatus';
import { ReceiveDialog } from '../features/purchasing/ReceiveDialog';
import { ReplenishDialog } from '../features/purchasing/ReplenishDialog';
import type { Row } from '../lib/api';
import { date, fmt, money, num, text, today } from '../lib/format';
import { Tabs } from '../motion/motion';

const PAGE = 10;
const TABS = ['all', 'open', 'DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL', 'RECEIVED', 'CLOSED', 'CANCELLED'];

/**
 * Route `/purchasing`: server-paged purchase orders with the detail drawer, multi-line editor, goods receipt,
 * invoice matching and supplier returns; drafts from planning shortages; receipt/invoice/return registers; suppliers.
 */
export default function PurchasingPage() {
  const { can, summary } = useWorkspace();
  const data = useStateData();
  const [status, setStatus] = useState('open');
  const [input, setInput] = useState('');
  const [q, setQ] = useState('');
  const [cursors, setCursors] = useState<string[]>([]);
  const [selected, setSelected] = useState<string>();
  const [editor, setEditor] = useState<{ detail?: PurchaseOrderDetail; material?: Row }>();
  const [receive, setReceive] = useState<PurchaseOrderDetail>();
  const [invoice, setInvoice] = useState<PurchaseOrderDetail>();
  const [replenish, setReplenish] = useState(false);
  useEffect(() => onNewPurchaseOrder((material) => setEditor({ material })), []);
  useEffect(() => {
    const timeout = setTimeout(() => {
      setQ(input.trim());
      setCursors([]);
    }, 300);
    return () => clearTimeout(timeout);
  }, [input]);
  const before = cursors[cursors.length - 1];
  const page = usePurchaseOrders({ status, q, before, limit: PAGE });
  const counts = page.data?.counts || {};
  const count = (...s: string[]) => s.reduce((sum, x) => sum + num(counts[x]), 0);
  // Summary figures ignore the search box: one unfiltered row, counts only.
  const overview = usePurchaseOrders({ status: 'all', q: '', limit: 1 });
  const total = (...s: string[]) => s.reduce((sum, x) => sum + num(overview.data?.counts[x]), 0);
  const incomingQc = data.inspections.filter((i) => i.type === 'INCOMING' && i.status === 'PENDING').length;
  const shortages = summary.shortages.filter((m) => m.type !== 'FINISHED_GOOD' && m.active !== false);
  const buyer = can('PLANNER', 'WAREHOUSE');
  const rows = page.data?.items || [];
  const start = cursors.length * PAGE;
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Awaiting approval</span>
          <strong className={total('SUBMITTED') ? 'overdue' : ''}>{fmt(total('SUBMITTED'))}</strong>
        </div>
        <div>
          <span>Open orders</span>
          <strong>{fmt(total('APPROVED', 'PARTIAL'))}</strong>
        </div>
        <div>
          <span>Drafts</span>
          <strong>{fmt(total('DRAFT'))}</strong>
        </div>
        <div>
          <span>Receipts awaiting QC</span>
          <strong className={incomingQc ? 'overdue' : ''}>{fmt(incomingQc)}</strong>
        </div>
      </div>
      <Panel className="server-table">
        <Tabs
          label="Purchase order status filter"
          items={TABS.map((t) => ({
            value: t,
            label: t === 'all' ? 'All orders' : t === 'open' ? 'Open' : statusLabel(t),
            count:
              t === 'all'
                ? count(...TABS.slice(2))
                : t === 'open'
                  ? count('APPROVED', 'PARTIAL')
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
              aria-label="Search purchase orders"
              placeholder="Search order, supplier, quotation or material…"
              value={input}
              onChange={(e) => setInput(e.target.value)}
            />
          </div>
          {buyer ? (
            <div className="purchase-toolbar-actions">
              {shortages.length > 0 && (
                <button className="button secondary small" onClick={() => setReplenish(true)}>
                  <Layers size={14} />
                  From shortages ({shortages.length})
                </button>
              )}
              <button className="button secondary small" onClick={() => setEditor({})}>
                <Plus size={14} />
                New order
              </button>
            </div>
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
                label: 'Purchase order',
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
                    <span>{text(r.supplier) || 'Supplier to be chosen'}</span>
                  </div>
                ),
              },
              {
                label: 'Materials',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>
                      {fmt(r.line_count)} {num(r.line_count) === 1 ? 'line' : 'lines'}
                    </strong>
                    <span className="products-cell" title={text(r.items)}>
                      {text(r.items) || 'No lines yet'}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Receiving',
                render: (r) => (
                  <div className="table-progress">
                    <span>
                      {r.uom ? (
                        <>
                          {fmt(r.received, 3)} <em>/ {fmt(r.quantity, 3)}</em> {text(r.uom)}
                        </>
                      ) : (
                        <>
                          {fmt(r.lines_received)} <em>/ {fmt(r.line_count)} lines</em>
                        </>
                      )}
                      {num(r.awaiting_qc) > 0 && <em> · {fmt(r.awaiting_qc)} in QC</em>}
                    </span>
                    <Progress value={num(r.progress)} />
                  </div>
                ),
              },
              {
                label: 'Total',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{money(r.total)}</strong>
                    {num(r.variances) > 0 && <span className="negative">invoice variance</span>}
                  </div>
                ),
              },
              {
                label: 'Expected',
                render: (r) => (
                  <div className="cell-stack">
                    <strong
                      className={
                        text(r.due_date) < today() && ['APPROVED', 'PARTIAL'].includes(text(r.status))
                          ? 'overdue'
                          : ''
                      }
                    >
                      {date(r.due_date)}
                    </strong>
                    <span>{text(r.supplier_reference) || '—'}</span>
                  </div>
                ),
              },
              { label: 'Status', render: (r) => <PurchaseStatus status={r.status} /> },
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
      <DocumentsPanel onOpen={(id) => setSelected(id)} />
      <PartnerDirectory kind="SUPPLIER" />
      <AnimatePresence>
        {selected && (
          <PurchaseOrderDrawer
            key={`purchase-order-${selected}`}
            orderId={selected}
            onClose={() => setSelected(undefined)}
            onEdit={(detail) => setEditor({ detail })}
            onReceive={(detail) => setReceive(detail)}
            onInvoice={(detail) => setInvoice(detail)}
          />
        )}
        {editor && (
          <PurchaseOrderEditor
            key="purchase-order-editor"
            detail={editor.detail}
            material={editor.material}
            onClose={() => setEditor(undefined)}
            onSaved={(id) => setSelected(id)}
          />
        )}
        {receive && (
          <ReceiveDialog key="purchase-receive" detail={receive} onClose={() => setReceive(undefined)} />
        )}
        {invoice && (
          <InvoiceDialog key="purchase-invoice" detail={invoice} onClose={() => setInvoice(undefined)} />
        )}
        {replenish && (
          <ReplenishDialog
            key="purchase-replenish"
            onClose={() => setReplenish(false)}
            onCreated={() => {
              setStatus('DRAFT');
              setCursors([]);
            }}
          />
        )}
      </AnimatePresence>
    </>
  );
}
