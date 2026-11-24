import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { Pencil, Plus, ShieldCheck } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Table } from '../../components';
import { request } from '../../lib/api';
import { labelState, money, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { approversText } from '../approvals/policies';
import { OPERATORS, REASONS, useDebounced, useInventoryCommand, usePaged, useSettings } from './api';
import { AdjustmentDialog } from './AdjustmentDialog';
import { AdjustmentDrawer } from './AdjustmentDrawer';
import { dateTime, LoadMore, MoneyDelta, QueryState, SearchBox, StatusBadge } from './shared';

const STATUSES = ['all', 'DRAFT', 'APPROVAL_REQUIRED', 'POSTED', 'CANCELLED'];

/** Stock adjustments: status filter, approval threshold, create dialog and detail drawer. */
export function AdjustmentsTab({ pending }: { pending: number }) {
  const { can, openForm } = useWorkspace();
  const settings = useSettings();
  const { invalidate } = useInventoryCommand();
  const [status, setStatus] = useState('all');
  const [reason, setReason] = useState('');
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState<string>();
  const [creating, setCreating] = useState(false);
  const q = useDebounced(search);
  const { query, rows } = usePaged('adjustments', '/inventory/adjustments', {
    status: status === 'all' ? undefined : status,
    reason,
    q,
    limit: 50,
  });
  const threshold = settings.data?.approvalThreshold;
  // The chip describes the STOCK_ADJUSTMENT approval policy; it is hidden while the policy requires no approval.
  const approvalOff = settings.data?.approvalRequired === false;
  const approvers = approversText(settings.data?.approverRoles ?? OPERATORS);
  function editThreshold() {
    openForm({
      title: 'Adjustment approval threshold',
      description: `Adjustments whose gross value (increases plus decreases) exceeds this amount need approval by a different person: ${approvers}.`,
      submit: 'Save threshold',
      fields: [
        {
          name: 'approvalThreshold',
          label: 'Threshold (LKR)',
          type: 'number',
          min: '0',
          step: '0.01',
          defaultValue: text(threshold ?? 50000),
        },
      ],
      run: async (values, key) => {
        await request('/inventory/settings', { approvalThreshold: values.approvalThreshold }, key);
        await invalidate();
      },
    });
  }
  return (
    <>
      <Tabs
        compact
        label="Adjustment status"
        items={STATUSES.map((s) => ({
          value: s,
          label: s === 'all' ? 'All' : labelState(s),
          count: s === 'APPROVAL_REQUIRED' && pending ? pending : undefined,
        }))}
        value={status}
        onChange={setStatus}
      />
      <div className="inv-toolbar">
        <div className="inv-filters">
          <SearchBox value={search} onChange={setSearch} placeholder="Search number, notes or person…" />
          <select aria-label="Reason" value={reason} onChange={(e) => setReason(e.target.value)}>
            <option value="">Any reason</option>
            {REASONS.map((r) => (
              <option key={r} value={r}>
                {labelState(r)}
              </option>
            ))}
          </select>
        </div>
        <div className="inv-actions">
          {!approvalOff && (
            <span className="inv-chip">
              <ShieldCheck size={13} />
              {threshold === undefined
                ? 'Approval above …'
                : num(threshold) === 0
                  ? 'Approval for every adjustment'
                  : `Approval above ${money(threshold)}`}
              {can() && (
                <button className="icon-button" aria-label="Edit approval threshold" onClick={editThreshold}>
                  <Pencil size={13} />
                </button>
              )}
            </span>
          )}
          {can(...OPERATORS) && (
            <button className="button primary small" onClick={() => setCreating(true)}>
              <Plus size={14} />
              New adjustment
            </button>
          )}
        </div>
      </div>
      <QueryState pending={query.isPending} error={query.error} retry={() => query.refetch()}>
        <Table
          rows={rows}
          compact
          pageSize={Math.max(rows.length, 1)}
          onRow={(r) => setOpen(r.id)}
          columns={[
            {
              label: 'Adjustment',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.number)}</strong>
                  <span>
                    {text(r.created_by_name)}
                    {r.count_number ? ` · ${text(r.count_number)}` : ''}
                  </span>
                </div>
              ),
            },
            { label: 'Reason', render: (r) => <span className="capitalize">{labelState(r.reason)}</span> },
            { label: 'Lines', key: 'line_count' },
            {
              label: 'Value',
              render: (r) => (
                <div className="cell-stack">
                  <strong>
                    <MoneyDelta value={r.net_value} />
                  </strong>
                  <span>gross {money(r.gross_value)}</span>
                </div>
              ),
            },
            { label: 'Status', render: (r) => <StatusBadge value={r.status} /> },
            { label: 'Created', render: (r) => dateTime(r.created_at) },
          ]}
        />
        <LoadMore query={query} count={rows.length} />
      </QueryState>
      <AnimatePresence>
        {open && <AdjustmentDrawer key={open} id={open} onClose={() => setOpen(undefined)} />}
        {creating && (
          <AdjustmentDialog
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
