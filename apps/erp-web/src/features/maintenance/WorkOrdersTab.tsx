import { useState } from 'react';
import { Plus } from 'lucide-react';
import { useStateData, useWorkspace } from '../../app/workspace';
import { Table } from '../../components';
import { money, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { useDebounced } from '../inventory/api';
import { SearchBox } from '../inventory/shared';
import { MAINTENANCE, REPORTERS, when } from './api';
import { requestMaintenance } from './forms';
import { PriorityText, StatusChip, TypeChip } from './shared';

const TABS = [
  { value: 'OPEN', label: 'Open' },
  { value: 'REQUESTED', label: 'Awaiting approval' },
  { value: 'SCHEDULED', label: 'Scheduled' },
  { value: 'IN_PROGRESS', label: 'In progress' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'CANCELLED', label: 'Cancelled' },
  { value: 'ALL', label: 'All' },
];

/** Maintenance work orders: status tabs, type/machine/text filters, server paging (rows open the work-order drawer). */
export function WorkOrdersTab({ onOpen }: { onOpen: (id: string) => void }) {
  const workspace = useWorkspace();
  const data = useStateData();
  const [status, setStatus] = useState('OPEN');
  const [type, setType] = useState('');
  const [machine, setMachine] = useState('');
  const [search, setSearch] = useState('');
  const q = useDebounced(search);
  const paged = useCursorPage(
    [MAINTENANCE, 'work-orders'],
    '/maintenance/work-orders',
    { status: status === 'ALL' ? undefined : status, type, machine, q },
    25,
  );
  const counts = (paged.query.data?.counts || {}) as Record<string, number>;
  const openCount =
    (counts.REQUESTED || 0) + (counts.APPROVED || 0) + (counts.SCHEDULED || 0) + (counts.IN_PROGRESS || 0);
  return (
    <>
      <Tabs
        compact
        label="Work order status"
        items={TABS.map((t) => ({
          ...t,
          count:
            t.value === 'OPEN'
              ? openCount || undefined
              : t.value === 'REQUESTED' || t.value === 'IN_PROGRESS'
                ? counts[t.value] || undefined
                : undefined,
        }))}
        value={status}
        onChange={setStatus}
      />
      <div className="inv-toolbar">
        <div className="inv-filters">
          <SearchBox value={search} onChange={setSearch} placeholder="Search order, problem or machine…" />
          <select aria-label="Type" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="">Any type</option>
            <option value="CORRECTIVE">Corrective</option>
            <option value="PREVENTIVE">Preventive</option>
          </select>
          <select aria-label="Work center" value={machine} onChange={(e) => setMachine(e.target.value)}>
            <option value="">All work centers</option>
            {data.machines.map((m) => (
              <option key={m.id} value={m.id}>
                {text(m.code)}
              </option>
            ))}
          </select>
        </div>
        {workspace.can(...REPORTERS) && (
          <div className="inv-actions">
            <button className="button primary small" onClick={() => requestMaintenance(workspace, machine || undefined)}>
              <Plus size={14} />
              Request maintenance
            </button>
          </div>
        )}
      </div>
      <PageState paged={paged}>
        <Table
          rows={paged.rows}
          compact
          label="Maintenance work orders"
          pageSize={Math.max(paged.rows.length, 1)}
          onRow={(r) => onOpen(r.id)}
          columns={[
            {
              label: 'Work order',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.number)}</strong>
                  <span>{text(r.title)}</span>
                </div>
              ),
            },
            {
              label: 'Work center',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.machine)}</strong>
                  <span>{text(r.machine_name)}</span>
                </div>
              ),
            },
            { label: 'Type', render: (r) => <TypeChip type={r.type} /> },
            { label: 'Priority', render: (r) => <PriorityText value={r.priority} /> },
            { label: 'Status', render: (r) => <StatusChip value={r.status} /> },
            {
              label: 'When',
              render: (r) => (
                <div className="cell-stack">
                  <span>
                    {r.status === 'COMPLETED'
                      ? `Done ${when(r.completed_at)}`
                      : r.scheduled_start
                        ? `From ${when(r.scheduled_start)}`
                        : `Requested ${when(r.created_at)}`}
                  </span>
                  <span>{text(r.assignee) || (r.plan ? `Plan ${text(r.plan)}` : '')}</span>
                </div>
              ),
            },
            { label: 'Cost', render: (r) => money(r.total_cost) },
          ]}
        />
        <PageFooter paged={paged} label="work orders" />
      </PageState>
    </>
  );
}
