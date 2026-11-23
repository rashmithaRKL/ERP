import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { ArrowRight, Plus } from 'lucide-react';
import { useStateData, useWorkspace } from '../../app/workspace';
import { Table } from '../../components';
import { labelState, money, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { OPERATORS, useDebounced, usePaged } from './api';
import { TransferDialog } from './TransferDialog';
import { TransferDrawer } from './TransferDrawer';
import { dateTime, LoadMore, QueryState, SearchBox, StatusBadge } from './shared';

const STATUSES = ['all', 'DRAFT', 'POSTED', 'CANCELLED'];

/** Warehouse transfers: status / warehouse filters, create dialog and detail drawer. */
export function TransfersTab() {
  const data = useStateData();
  const { can } = useWorkspace();
  const [status, setStatus] = useState('all');
  const [warehouseId, setWarehouseId] = useState('');
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState<string>();
  const [creating, setCreating] = useState(false);
  const q = useDebounced(search);
  const { query, rows } = usePaged('transfers', '/inventory/transfers', {
    status: status === 'all' ? undefined : status,
    warehouseId,
    q,
    limit: 50,
  });
  return (
    <>
      <Tabs
        compact
        label="Transfer status"
        items={STATUSES.map((s) => ({ value: s, label: s === 'all' ? 'All' : labelState(s) }))}
        value={status}
        onChange={setStatus}
      />
      <div className="inv-toolbar">
        <div className="inv-filters">
          <SearchBox value={search} onChange={setSearch} placeholder="Search number, notes or warehouse…" />
          <select aria-label="Warehouse" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
            <option value="">Any warehouse</option>
            {data.warehouses.map((w) => (
              <option key={w.id} value={w.id}>
                {text(w.name)}
              </option>
            ))}
          </select>
        </div>
        {can(...OPERATORS) && (
          <div className="inv-actions">
            <button className="button primary small" onClick={() => setCreating(true)}>
              <Plus size={14} />
              New transfer
            </button>
          </div>
        )}
      </div>
      <QueryState pending={query.isPending} error={query.error} retry={() => query.refetch()}>
        <Table
          rows={rows}
          compact
          pageSize={Math.max(rows.length, 1)}
          onRow={(r) => setOpen(r.id)}
          columns={[
            {
              label: 'Transfer',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.number)}</strong>
                  <span>{text(r.created_by_name)}</span>
                </div>
              ),
            },
            {
              label: 'Route',
              render: (r) => (
                <span className="inv-route">
                  {text(r.from_warehouse)} <ArrowRight size={12} /> {text(r.to_warehouse)}
                </span>
              ),
            },
            { label: 'Lots', key: 'line_count' },
            { label: 'Value', render: (r) => (r.status === 'POSTED' ? money(r.total_value) : '—') },
            { label: 'Status', render: (r) => <StatusBadge value={r.status} /> },
            { label: 'Created', render: (r) => dateTime(r.created_at) },
          ]}
        />
        <LoadMore query={query} count={rows.length} />
      </QueryState>
      <AnimatePresence>
        {open && <TransferDrawer key={open} id={open} onClose={() => setOpen(undefined)} />}
        {creating && (
          <TransferDialog
            onClose={() => setCreating(false)}
            onDone={(id) => {
              setCreating(false);
              setOpen(id);
            }}
          />
        )}
      </AnimatePresence>
    </>
  );
}
