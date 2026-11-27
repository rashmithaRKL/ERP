import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ArrowDownToLine, CircleAlert, CircleCheck } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Table } from '../../components';
import type { Column } from '../../components';
import { request } from '../../lib/api';
import type { Row, Value } from '../../lib/api';
import { csv, date, money, num, text, today } from '../../lib/format';
import { QueryState } from '../inventory/shared';
import { ACCOUNTING, TYPE_LABELS } from './api';

type TrialBalance = {
  mode: 'AS_OF' | 'PERIOD';
  from: string | null;
  to: string;
  items: Row[];
  totals: Record<string, Value>;
  balanced: boolean;
};

const amount = (x: unknown) => (num(x) ? money(x) : '—');
const cell = (x: unknown) => <span className="acc-amount">{amount(x)}</span>;

/**
 * Trial balance (`GET /accounting/trial-balance`): as of a date (cumulative debits, credits and the balance in the
 * debit or credit column) or for a period (opening, the period's movements, closing). Totals must agree; CSV export.
 * Rows open the account ledger.
 */
export function TrialBalanceTab({ onOpenAccount }: { onOpenAccount: (id: string) => void }) {
  const { notify } = useWorkspace();
  const [mode, setMode] = useState<'AS_OF' | 'PERIOD'>('AS_OF');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState(today());
  const [zero, setZero] = useState(false);
  const period = mode === 'PERIOD' && !!from;
  const params = new URLSearchParams({ to: to || today() });
  if (period) params.set('from', from);
  if (zero) params.set('zero', 'true');
  const tb = useQuery({
    queryKey: [ACCOUNTING, 'trial-balance', params.toString()],
    queryFn: () => request<TrialBalance>(`/accounting/trial-balance?${params}`),
    placeholderData: keepPreviousData,
  });
  const data = tb.data;
  const t = data?.totals ?? {};

  function exportCsv() {
    if (!data?.items.length) return;
    csv(
      period ? `trial-balance-${data.from}-to` : 'trial-balance-as-of',
      data.items.map((r) => ({
        id: text(r.id),
        code: r.code,
        account: r.name,
        type: TYPE_LABELS[text(r.type)],
        ...(period ? { opening_debit: r.opening_debit, opening_credit: r.opening_credit } : {}),
        debit: r.debit,
        credit: r.credit,
        [period ? 'closing_debit' : 'balance_debit']: r.closing_debit,
        [period ? 'closing_credit' : 'balance_credit']: r.closing_credit,
      })),
    );
    notify('Trial balance downloaded successfully.');
  }

  const columns: Column[] = [
    {
      label: 'Account',
      render: (r) => (
        <div className="cell-stack">
          <strong>{text(r.name)}</strong>
          <span>
            {text(r.code)} · {TYPE_LABELS[text(r.type)]}
            {r.needs_review ? ' · needs review' : ''}
          </span>
        </div>
      ),
    },
    ...(period
      ? [
          { label: 'Opening Dr', render: (r: Row) => cell(r.opening_debit) },
          { label: 'Opening Cr', render: (r: Row) => cell(r.opening_credit) },
        ]
      : []),
    { label: period ? 'Period debits' : 'Debits', render: (r) => cell(r.debit) },
    { label: period ? 'Period credits' : 'Credits', render: (r) => cell(r.credit) },
    { label: period ? 'Closing Dr' : 'Balance Dr', render: (r) => cell(r.closing_debit) },
    { label: period ? 'Closing Cr' : 'Balance Cr', render: (r) => cell(r.closing_credit) },
  ];

  return (
    <>
      <div className="inv-toolbar acc-tb-toolbar">
        <div className="inv-filters">
          <div className="inv-segmented" role="group" aria-label="Trial balance type">
            {(
              [
                ['AS_OF', 'As of a date'],
                ['PERIOD', 'For a period'],
              ] as const
            ).map(([value, label]) => (
              <button
                key={value}
                type="button"
                className={mode === value ? 'active' : ''}
                aria-pressed={mode === value}
                onClick={() => setMode(value)}
              >
                {label}
              </button>
            ))}
          </div>
          {mode === 'PERIOD' && (
            <label className="inv-date">
              From
              <input
                type="date"
                value={from}
                max={to || undefined}
                onChange={(e) => setFrom(e.target.value)}
              />
            </label>
          )}
          <label className="inv-date">
            {mode === 'PERIOD' ? 'To' : 'As of'}
            <input
              type="date"
              required
              value={to}
              min={mode === 'PERIOD' ? from || undefined : undefined}
              onChange={(e) => setTo(e.target.value)}
            />
          </label>
          <label className="inv-check">
            <input type="checkbox" checked={zero} onChange={(e) => setZero(e.target.checked)} />
            Include accounts without activity
          </label>
        </div>
        <div className="inv-actions">
          {data && (
            <span className={`acc-tb-status ${data.balanced ? 'ok' : 'off'}`}>
              {data.balanced ? <CircleCheck size={15} /> : <CircleAlert size={15} />}
              {data.balanced ? 'Debits equal credits' : 'Out of balance'}
            </span>
          )}
          <button className="button secondary small" disabled={!data?.items.length} onClick={exportCsv}>
            <ArrowDownToLine size={14} />
            CSV
          </button>
        </div>
      </div>
      {mode === 'PERIOD' && !from && (
        <p className="muted inv-small" style={{ padding: '0 18px 12px' }}>
          Choose the first day of the period; until then the trial balance shows every posting up to the end
          date.
        </p>
      )}
      <QueryState pending={tb.isPending} error={tb.error} retry={() => tb.refetch()}>
        {data && data.items.length ? (
          <>
            <Table
              rows={data.items}
              compact
              label="Trial balance"
              pageSize={Math.max(data.items.length, 1)}
              onRow={(r) => onOpenAccount(r.id)}
              columns={columns}
            />
            <div className="acc-tb-totals" aria-label="Trial balance totals">
              {period && (
                <div>
                  <span>Opening Dr / Cr</span>
                  <strong>
                    {money(t.opening_debit)} / {money(t.opening_credit)}
                  </strong>
                </div>
              )}
              <div>
                <span>{period ? 'Period debits' : 'Total debits'}</span>
                <strong>{money(t.debit)}</strong>
              </div>
              <div>
                <span>{period ? 'Period credits' : 'Total credits'}</span>
                <strong>{money(t.credit)}</strong>
              </div>
              <div>
                <span>{period ? 'Closing Dr / Cr' : 'Balances Dr / Cr'}</span>
                <strong>
                  {money(t.closing_debit)} / {money(t.closing_credit)}
                </strong>
              </div>
            </div>
          </>
        ) : (
          <Empty
            title="Nothing posted yet"
            body={`No entry is posted ${period ? `between ${date(from)} and ${date(to)}` : `up to ${date(to)}`}.`}
          />
        )}
      </QueryState>
    </>
  );
}
