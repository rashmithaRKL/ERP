import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useWorkspace } from '../../app/workspace';
import { request } from '../../lib/api';
import type { Row, Value } from '../../lib/api';
import { APPROVAL_POLICIES } from '../approvals/policies';

/** Query-key prefix of every general-ledger read (V24); one invalidation refreshes the whole Accounting page. */
export const ACCOUNTING = 'accounting';

/** Who works in the general ledger (ADMIN always). */
export const FINANCE_ROLES = ['FINANCE'];

export const ACCOUNT_TYPES = ['ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'] as const;
export const TYPE_LABELS: Record<string, string> = {
  ASSET: 'Asset',
  LIABILITY: 'Liability',
  EQUITY: 'Equity',
  INCOME: 'Income',
  EXPENSE: 'Expense',
};

export const JOURNAL_STATUS_LABELS: Record<string, string> = {
  DRAFT: 'Draft',
  SUBMITTED: 'Awaiting approval',
  POSTED: 'Posted',
  REVERSED: 'Reversed',
  CANCELLED: 'Cancelled',
};
const JOURNAL_TONES: Record<string, string> = {
  DRAFT: 'gray',
  SUBMITTED: 'amber',
  POSTED: 'green',
  REVERSED: 'blue',
  CANCELLED: 'red',
};
export const journalTone = (status: unknown) => JOURNAL_TONES[String(status)] || 'gray';

export const SOURCE_LABELS: Record<string, string> = {
  SYSTEM: 'System posting',
  MANUAL: 'Manual journal',
  REVERSAL: 'Reversal',
};

/** One row of `GET /accounting/accounts`: tree order, own and roll-up (`total_*`) figures up to `asOf`. */
export type Account = Row & {
  code: string;
  name: string;
  type: string;
  normal_balance: 'DEBIT' | 'CREDIT';
  parent_id: string | null;
  postable: boolean;
  active: boolean;
  system: boolean;
  needs_review: boolean;
  contra: boolean;
  depth: number;
  children: number;
  posted: boolean;
  on_journals: boolean;
};
export type Chart = {
  asOf: string;
  items: Account[];
  totals: { debit: Value; credit: Value };
  counts: { accounts: number; needs_review: number; inactive: number };
};

/** `GET /accounting/journals/{id}`. */
export type JournalDetail = Row & {
  lines: Row[];
  activity: Row[];
  can: {
    edit: boolean;
    submit: boolean;
    approve: boolean;
    return: boolean;
    cancel: boolean;
    reverse: boolean;
  };
  approverRoles: string[];
};

/** `GET /accounting/entries/{id}`. */
export type EntryDetail = Row & { lines: Row[]; can_reverse: boolean };

/** `GET /accounting/policy`: the MANUAL_JOURNAL approval policy. */
export type JournalPolicy = {
  documentType: string;
  approvalRequired: boolean;
  amountThreshold: Value;
  approverRoles: string[];
  customized: boolean;
  updatedAt: Value;
  updatedBy: Value;
};

/** The chart of accounts with balances as of `asOf` (today when blank). */
export function useChart(asOf = '') {
  return useQuery({
    queryKey: [ACCOUNTING, 'accounts', asOf],
    queryFn: () => request<Chart>(`/accounting/accounts${asOf ? `?asOf=${asOf}` : ''}`),
  });
}

export function useJournalPolicy() {
  return useQuery({
    queryKey: [ACCOUNTING, 'policy'],
    queryFn: () => request<JournalPolicy>('/accounting/policy'),
  });
}

/**
 * Runs a general-ledger command and refreshes everything that may show it: the Accounting page (`accounting` keys),
 * the shared state (attention counts, financial figures) and the approval-policy summaries.
 */
export function useAccountingCommand() {
  const qc = useQueryClient();
  const { notify, refresh } = useWorkspace();
  const [busy, setBusy] = useState(false);
  async function run<T = Row>(url: string, body: unknown, key: string, success?: string): Promise<T> {
    setBusy(true);
    try {
      const result = await request<T>(url, body ?? {}, key);
      await Promise.all([
        qc.invalidateQueries({ queryKey: [ACCOUNTING] }),
        qc.invalidateQueries({ queryKey: APPROVAL_POLICIES }),
        refresh(),
      ]);
      if (success) notify(success);
      return result;
    } finally {
      setBusy(false);
    }
  }
  return { run, busy };
}

/** `CASH · Cash on hand` for pickers and ledgers. */
export const accountLabel = (a: Row | undefined) => (a ? `${a.code} · ${a.name}` : '');
