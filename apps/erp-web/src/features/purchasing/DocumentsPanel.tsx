import { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, Search } from 'lucide-react';
import { Badge, Panel, Table } from '../../components';
import type { Column } from '../../components';
import { date, fmt, money, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { usePurchaseDocuments } from './api';
import { MatchBadge } from './PurchaseOrderDrawer';
import '../sales/sales.css';
import './purchasing.css';

type Kind = 'receipts' | 'invoices' | 'returns';
const PAGE = 8;

const FILTERS: Record<Kind, { value: string; label: string }[]> = {
  receipts: [
    { value: 'all', label: 'All' },
    { value: 'QUARANTINE', label: 'In QC' },
    { value: 'uninvoiced', label: 'To invoice' },
    { value: 'REJECTED', label: 'Rejected' },
  ],
  invoices: [
    { value: 'all', label: 'All' },
    { value: 'VARIANCE', label: 'With variance' },
    { value: 'MATCHED', label: 'Matched' },
  ],
  returns: [{ value: 'all', label: 'All' }],
};

const COLUMNS: Record<Kind, Column[]> = {
  receipts: [
    {
      label: 'Receipt',
      render: (r) => (
        <div className="cell-stack">
          <strong>{text(r.number)}</strong>
          <span>
            {text(r.purchase_order)} · {text(r.supplier)}
          </span>
        </div>
      ),
    },
    {
      label: 'Material',
      render: (r) => (
        <div className="cell-stack">
          <strong>{text(r.item)}</strong>
          <span>
            lot {text(r.lot)} · {text(r.warehouse)}
          </span>
        </div>
      ),
    },
    { label: 'Received', render: (r) => `${fmt(r.quantity, 3)} ${text(r.uom)}` },
    { label: 'Quality', render: (r) => <Badge value={r.inspection_status || r.status} /> },
    {
      label: 'To invoice',
      render: (r) => (num(r.invoiceable) > 0 ? `${fmt(r.invoiceable, 3)} ${text(r.uom)}` : '—'),
    },
    { label: 'Date', render: (r) => date(r.created_at) },
  ],
  invoices: [
    {
      label: 'Supplier invoice',
      render: (r) => (
        <div className="cell-stack">
          <strong>{text(r.supplier_reference)}</strong>
          <span>
            {text(r.number)} · {text(r.purchase_order)}
          </span>
        </div>
      ),
    },
    { label: 'Supplier', key: 'supplier' },
    { label: 'Billed', render: (r) => money(r.amount) },
    {
      label: 'Variance',
      render: (r) => (
        <div className="cell-stack">
          <span className={Math.abs(num(r.price_variance)) > 0.004 ? 'negative' : 'muted'}>
            {num(r.price_variance) > 0 ? '+' : ''}
            {money(r.price_variance)}
          </span>
          {num(r.quantity_variance) < 0 && <span>{fmt(-num(r.quantity_variance), 3)} not billed</span>}
        </div>
      ),
    },
    { label: 'Match', render: (r) => <MatchBadge status={r.status} /> },
    { label: 'Invoice date', render: (r) => date(r.invoice_date) },
  ],
  returns: [
    {
      label: 'Return',
      render: (r) => (
        <div className="cell-stack">
          <strong>{text(r.number)}</strong>
          <span>
            {text(r.purchase_order)} · debit note {text(r.debit_note)}
          </span>
        </div>
      ),
    },
    {
      label: 'Material',
      render: (r) => (
        <div className="cell-stack">
          <strong>{text(r.item)}</strong>
          <span>
            lot {text(r.lot)} · {text(r.supplier)}
          </span>
        </div>
      ),
    },
    { label: 'Quantity', render: (r) => `${fmt(r.quantity, 3)} ${text(r.uom)}` },
    { label: 'Value', render: (r) => money(r.value_amount) },
    { label: 'Date', render: (r) => date(r.created_at) },
  ],
};

/**
 * Server-paged registers of goods receipts, supplier invoices and supplier returns across all orders. Clicking a row
 * opens its purchase order.
 */
export function DocumentsPanel({ onOpen }: { onOpen: (purchaseOrderId: string) => void }) {
  const [kind, setKind] = useState<Kind>('receipts');
  const [status, setStatus] = useState('all');
  const [input, setInput] = useState('');
  const [q, setQ] = useState('');
  const [cursors, setCursors] = useState<string[]>([]);
  useEffect(() => {
    const timeout = setTimeout(() => {
      setQ(input.trim());
      setCursors([]);
    }, 300);
    return () => clearTimeout(timeout);
  }, [input]);
  const page = usePurchaseDocuments(kind, { status, q, before: cursors[cursors.length - 1], limit: PAGE });
  const rows = page.data?.items || [];
  return (
    <Panel
      title="Receipts, invoices & returns"
      subtitle="Every goods receipt with its quality decision, matched supplier invoices and returns to suppliers."
      className="spaced-panel server-table"
    >
      <Tabs
        label="Procurement register"
        items={[
          { value: 'receipts', label: 'Goods receipts' },
          { value: 'invoices', label: 'Supplier invoices' },
          { value: 'returns', label: 'Supplier returns' },
        ]}
        value={kind}
        onChange={(v) => {
          setKind(v as Kind);
          setStatus('all');
          setCursors([]);
        }}
      />
      <div className="server-toolbar">
        <div className="search-input">
          <Search size={16} />
          <input
            aria-label={`Search ${kind}`}
            placeholder={
              kind === 'receipts'
                ? 'Search receipt, delivery note, order, lot or material…'
                : kind === 'invoices'
                  ? 'Search invoice number, order or supplier…'
                  : 'Search return, debit note, order, lot or material…'
            }
            value={input}
            onChange={(e) => setInput(e.target.value)}
          />
        </div>
        {FILTERS[kind].length > 1 && (
          <Tabs
            compact
            label={`${kind} filter`}
            items={FILTERS[kind]}
            value={status}
            onChange={(v) => {
              setStatus(v);
              setCursors([]);
            }}
          />
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
          onRow={(r) => onOpen(text(r.purchase_order_id))}
          columns={COLUMNS[kind]}
        />
      )}
      <div className="table-footer">
        <span>
          Page {cursors.length + 1} · {rows.length} {rows.length === 1 ? 'record' : 'records'}
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
  );
}
