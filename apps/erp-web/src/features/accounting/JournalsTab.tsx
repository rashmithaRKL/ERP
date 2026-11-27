import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { Info, Plus, Settings2 } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Table } from '../../components';
import { date, money, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { SearchBox } from '../inventory/shared';
import { useDebounced } from '../quality/shared';
import { ACCOUNTING, FINANCE_ROLES, useJournalPolicy } from './api';
import { JournalEditor } from './JournalEditor';
import { PolicyDialog } from './PolicyDialog';
import { JournalBadge } from './shared';

const TABS = [
  { value: 'SUBMITTED', label: 'Awaiting approval' },
  { value: 'DRAFT', label: 'Drafts' },
  { value: 'POSTED', label: 'Posted' },
  { value: 'REVERSED', label: 'Reversed' },
  { value: 'CANCELLED', label: 'Cancelled' },
  { value: 'all', label: 'All' },
];

/** Manual journals: status tabs, search, server paging; rows open the journal drawer. Shows the approval policy. */
export function JournalsTab({ onOpen }: { onOpen: (id: string) => void }) {
  const { can } = useWorkspace();
  const [status, setStatus] = useState('SUBMITTED');
  const [search, setSearch] = useState('');
  const [creating, setCreating] = useState(false);
  const [editingPolicy, setEditingPolicy] = useState(false);
  const q = useDebounced(search);
  const paged = useCursorPage([ACCOUNTING, 'journals'], '/accounting/journals', { status, q }, 25);
  const counts = (paged.query.data?.counts || {}) as Record<string, number>;
  const policy = useJournalPolicy();
  const p = policy.data;
  const finance = can(...FINANCE_ROLES);
  return (
    <>
      <Tabs
        compact
        label="Journal status"
        items={TABS.map((t) => ({
          ...t,
          count: t.value === 'SUBMITTED' || t.value === 'DRAFT' ? counts[t.value] || undefined : undefined,
        }))}
        value={status}
        onChange={setStatus}
      />
      <div className="inv-toolbar">
        <div className="inv-filters">
          <SearchBox value={search} onChange={setSearch} placeholder="Search number or narration…" />
        </div>
        {finance && (
          <div className="inv-actions">
            <button className="button primary small" onClick={() => setCreating(true)}>
              <Plus size={14} />
              New journal
            </button>
          </div>
        )}
      </div>
      {p && (
        <div className="acc-policy">
          <Info size={16} />
          <div>
            <strong>Approval policy: </strong>
            {!p.approvalRequired
              ? 'manual journals post when they are submitted (no second approval).'
              : p.amountThreshold === null || p.amountThreshold === undefined
                ? 'every manual journal needs a second person’s approval'
                : `journals above ${money(p.amountThreshold)} need a second person’s approval; smaller ones post when submitted`}
            {p.approvalRequired &&
              ` (${['an administrator', ...(p.approverRoles.includes('FINANCE') ? ['a finance user'] : [])].join(' or ')}, never the person who created, edited or submitted it).`}
          </div>
          {can() && (
            <button className="button secondary small" onClick={() => setEditingPolicy(true)}>
              <Settings2 size={14} />
              Change
            </button>
          )}
        </div>
      )}
      <PageState paged={paged}>
        {paged.rows.length ? (
          <Table
            rows={paged.rows}
            compact
            label="Manual journals"
            pageSize={Math.max(paged.rows.length, 1)}
            onRow={(r) => onOpen(r.id)}
            columns={[
              {
                label: 'Journal',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.number)}</strong>
                    <span>{text(r.narration)}</span>
                  </div>
                ),
              },
              {
                label: 'Posting date',
                render: (r) => (
                  <div className="cell-stack">
                    <span>{date(r.posting_date)}</span>
                    <span>
                      {text(r.line_count)} {r.line_count === 1 ? 'line' : 'lines'}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Status',
                render: (r) => (
                  <div className="cell-stack">
                    <JournalBadge status={r.status} />
                    {r.can_approve ? <span className="acc-attention">Awaits your approval</span> : null}
                  </div>
                ),
              },
              {
                label: 'People',
                render: (r) => (
                  <div className="cell-stack">
                    <span>By {text(r.created_by_name)}</span>
                    <span>
                      {r.approved_by_name
                        ? `Approved by ${text(r.approved_by_name)}`
                        : r.submitted_by_name
                          ? `Submitted by ${text(r.submitted_by_name)}`
                          : ''}
                    </span>
                  </div>
                ),
              },
              {
                label: 'Amount',
                render: (r) => <span className="acc-amount">{money(r.total_debit)}</span>,
              },
            ]}
          />
        ) : (
          <Empty
            title={q ? 'No journal matches this search' : 'No manual journals here'}
            body={
              status === 'SUBMITTED'
                ? 'Nothing is waiting for approval.'
                : finance
                  ? 'Create a manual journal for accruals, reclassifications and corrections.'
                  : ''
            }
          />
        )}
        <PageFooter paged={paged} label="journals" />
      </PageState>
      <AnimatePresence>
        {creating && <JournalEditor key="new" onClose={() => setCreating(false)} onSaved={onOpen} />}
        {editingPolicy && p && (
          <PolicyDialog key="policy" policy={p} onClose={() => setEditingPolicy(false)} />
        )}
      </AnimatePresence>
    </>
  );
}
