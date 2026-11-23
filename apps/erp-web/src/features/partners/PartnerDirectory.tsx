import { useEffect, useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight, Pencil, Plus, Search } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Panel, Table } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { fmt, money, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { createPartner, editPartner } from './forms';
import '../sales/sales.css';

type Page = { items: Row[]; next: string; total: number };
const PAGE = 8;

/** Active/inactive marker in the badge style. */
export function PartnerStatus({ active }: { active: unknown }) {
  return active === false ? (
    <span className="badge gray">
      <i />
      inactive
    </span>
  ) : (
    <span className="badge green">
      <i />
      active
    </span>
  );
}

/**
 * Server-paged customer or supplier directory (`GET /api/partners`) with search, an active/inactive filter and
 * create/edit dialogs. Customers show credit limit and receivables; suppliers show open purchase orders.
 */
export function PartnerDirectory({ kind }: { kind: 'CUSTOMER' | 'SUPPLIER' }) {
  const workspace = useWorkspace();
  const { can } = workspace;
  const qc = useQueryClient();
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
  const before = cursors[cursors.length - 1];
  const params = new URLSearchParams({ type: kind, status, limit: String(PAGE) });
  if (q) params.set('q', q);
  if (before) params.set('before', before);
  const page = useQuery({
    queryKey: ['partners', kind, status, q, before ?? ''],
    queryFn: () => request<Page>(`/partners?${params}`),
    placeholderData: keepPreviousData,
  });
  const customer = kind === 'CUSTOMER';
  const editable = customer ? can('PLANNER', 'SALES') : can('PLANNER', 'WAREHOUSE');
  const done = () => qc.invalidateQueries({ queryKey: ['partners'] });
  const rows = page.data?.items || [];
  const start = cursors.length * PAGE;
  return (
    <Panel
      title={customer ? 'Customer directory' : 'Supplier directory'}
      subtitle={
        customer
          ? 'Contacts, terms and credit limits. Inactive customers cannot be used on new orders.'
          : 'Contacts and terms. Inactive suppliers cannot be used on new purchase orders.'
      }
      className="spaced-panel server-table"
      action={
        editable && (
          <button className="button secondary small" onClick={() => createPartner(workspace, kind, done)}>
            <Plus size={14} />
            {customer ? 'Add customer' : 'Add supplier'}
          </button>
        )
      }
    >
      <Tabs
        compact
        label={`${customer ? 'Customer' : 'Supplier'} status filter`}
        items={[
          { value: 'all', label: 'All' },
          { value: 'active', label: 'Active' },
          { value: 'inactive', label: 'Inactive' },
        ]}
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
            aria-label={`Search ${customer ? 'customers' : 'suppliers'}`}
            placeholder="Search name, email, phone or tax ID…"
            value={input}
            onChange={(e) => setInput(e.target.value)}
          />
        </div>
        <span className="muted">{fmt(page.data?.total)} records</span>
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
          columns={[
            {
              label: customer ? 'Customer' : 'Supplier',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.name)}</strong>
                  <span>{text(r.email) || 'No email'}</span>
                </div>
              ),
            },
            {
              label: 'Phone / tax ID',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.phone) || '—'}</strong>
                  <span>{text(r.tax_id) || 'No tax ID'}</span>
                </div>
              ),
            },
            { label: 'Terms', render: (r) => `Net ${text(r.payment_days)} days` },
            ...(customer
              ? [
                  {
                    label: 'Credit limit',
                    render: (r: Row) =>
                      r.credit_limit == null ? <span className="muted">None</span> : money(r.credit_limit),
                  },
                  {
                    label: 'Receivables',
                    render: (r: Row) => (
                      <span
                        className={
                          r.credit_limit != null && num(r.receivables) > num(r.credit_limit) ? 'overdue' : ''
                        }
                      >
                        {money(r.receivables)}
                      </span>
                    ),
                  },
                ]
              : []),
            { label: customer ? 'Open orders' : 'Open POs', render: (r) => fmt(r.open_documents) },
            { label: 'Status', render: (r) => <PartnerStatus active={r.active} /> },
            {
              label: '',
              render: (r) =>
                editable && (
                  <button
                    className="button small secondary"
                    aria-label={`Edit ${text(r.name)}`}
                    onClick={() => editPartner(workspace, r, done)}
                  >
                    <Pencil size={13} />
                    Edit
                  </button>
                ),
            },
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
  );
}
