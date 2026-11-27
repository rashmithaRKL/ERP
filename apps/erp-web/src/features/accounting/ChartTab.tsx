import { useMemo, useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { Plus } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Table } from '../../components';
import { date, money, text } from '../../lib/format';
import { QueryState, SearchBox } from '../inventory/shared';
import { AccountDialog } from './AccountDialog';
import { ACCOUNT_TYPES, FINANCE_ROLES, TYPE_LABELS, useChart } from './api';
import type { Account } from './api';
import { drCr } from './cents';
import { AccountTags } from './shared';

/**
 * Chart of accounts as an indented tree (type, then code; sub-accounts under their header) with each account's balance
 * as of a date, header roll-ups, review and inactive badges. Rows open the account ledger; finance users add accounts.
 */
export function ChartTab({ onOpenAccount }: { onOpenAccount: (id: string) => void }) {
  const { can } = useWorkspace();
  const [asOf, setAsOf] = useState('');
  const [type, setType] = useState('');
  const [search, setSearch] = useState('');
  const [inactive, setInactive] = useState(false);
  const [reviewOnly, setReviewOnly] = useState(false);
  const [creating, setCreating] = useState(false);
  const chart = useChart(asOf);
  const items = useMemo(() => chart.data?.items ?? [], [chart.data]);
  const q = search.trim().toLowerCase();
  const filtering = !!q || reviewOnly;
  const rows = items.filter(
    (a) =>
      (!type || a.type === type) &&
      (inactive || a.active) &&
      (!reviewOnly || a.needs_review) &&
      (!q || `${a.code} ${a.name}`.toLowerCase().includes(q)),
  );
  return (
    <>
      <div className="inv-toolbar">
        <div className="inv-filters">
          <SearchBox value={search} onChange={setSearch} placeholder="Search code or name…" />
          <select aria-label="Account type" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="">All types</option>
            {ACCOUNT_TYPES.map((t) => (
              <option key={t} value={t}>
                {TYPE_LABELS[t]}
              </option>
            ))}
          </select>
          <label className="inv-date">
            Balances as of
            <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
          </label>
          <label className="inv-check">
            <input type="checkbox" checked={inactive} onChange={(e) => setInactive(e.target.checked)} />
            Show inactive
          </label>
          {(chart.data?.counts.needs_review ?? 0) > 0 && (
            <label className="inv-check">
              <input type="checkbox" checked={reviewOnly} onChange={(e) => setReviewOnly(e.target.checked)} />
              Needs review ({chart.data?.counts.needs_review})
            </label>
          )}
        </div>
        {can(...FINANCE_ROLES) && (
          <div className="inv-actions">
            <button className="button primary small" onClick={() => setCreating(true)}>
              <Plus size={14} />
              New account
            </button>
          </div>
        )}
      </div>
      <QueryState pending={chart.isPending} error={chart.error} retry={() => chart.refetch()}>
        {rows.length ? (
          <Table
            rows={rows}
            compact
            label="Chart of accounts"
            pageSize={Math.max(rows.length, 1)}
            onRow={(r) => onOpenAccount(r.id)}
            columns={[
              {
                label: 'Account',
                render: (r) => {
                  const a = r as Account;
                  return (
                    <div
                      className={`acc-code ${a.postable ? '' : 'acc-header-row'}`}
                      style={{ paddingLeft: filtering ? 0 : a.depth * 16 }}
                    >
                      <div className="acc-name">
                        <span>{text(a.name)}</span>
                        <small>{text(a.code)}</small>
                      </div>
                    </div>
                  );
                },
              },
              {
                label: 'Type',
                render: (r) => (
                  <div className="cell-stack">
                    <span>{TYPE_LABELS[text(r.type)]}</span>
                    <span>
                      {r.postable ? 'Postable' : 'Header'} · normal {text(r.normal_balance).toLowerCase()}
                      {r.contra ? ' (contra)' : ''}
                    </span>
                  </div>
                ),
              },
              { label: 'Status', render: (r) => <AccountTags account={r as Account} /> },
              {
                label: 'Balance',
                render: (r) => {
                  const a = r as Account;
                  const value = a.postable ? a.balance : a.total_balance;
                  return (
                    <span
                      className={`acc-amount ${Math.abs(Number(value)) < 0.005 ? 'muted' : ''} ${a.postable ? '' : 'acc-header-row'}`}
                    >
                      {drCr(value)}
                    </span>
                  );
                },
              },
            ]}
          />
        ) : (
          <Empty
            title={items.length ? 'No account matches these filters' : 'No accounts yet'}
            body={items.length ? 'Clear the search or the filters to see the whole chart.' : ''}
          />
        )}
        {chart.data && (
          <p className="muted inv-small" style={{ padding: '10px 18px 16px' }}>
            {chart.data.counts.accounts} accounts · balances of entries posted up to and including{' '}
            {asOf ? date(asOf) : 'today'} (debits {money(chart.data.totals.debit)}, credits{' '}
            {money(chart.data.totals.credit)}). Header balances include their sub-accounts.
          </p>
        )}
      </QueryState>
      <AnimatePresence>
        {creating && (
          <AccountDialog
            key="new"
            accounts={items}
            onClose={() => setCreating(false)}
            onSaved={(id) => {
              setCreating(false);
              onOpenAccount(id);
            }}
          />
        )}
      </AnimatePresence>
    </>
  );
}
