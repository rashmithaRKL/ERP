import { describe, expect, it } from 'vitest';
import type { State } from '../../lib/api';
import { buildAttention } from '../attention/attention';

/** The minimal shared state with V24 accounting counts. */
function state(accounting: State['accounting']): State {
  return {
    company: { id: 't' },
    factories: [],
    warehouses: [],
    partners: [],
    machines: [],
    items: [],
    orders: [],
    boms: [],
    bomLines: [],
    sales: [],
    purchases: [],
    lots: [],
    inspections: [],
    mrp: [],
    trend: [],
    metrics: { id: 'metrics', overdue: 0, pending_qc: 0 },
    sync: { id: 'sync' },
    accounting,
  };
}

const journal = (id: string) => ({
  id,
  number: `JV-2026-${id}`,
  narration: 'Accrued electricity',
  total_debit: 182450,
  posting_date: '2026-11-27',
});

describe('general-ledger attention (V24)', () => {
  it('lists the manual journals the viewer may approve, linking to each journal', () => {
    const attention = buildAttention({
      data: state({
        awaiting_approval: 3,
        awaiting_my_approval: 2,
        drafts: 1,
        needs_review: 0,
        awaiting: [journal('A1'), journal('B2')],
      }),
      user: { id: 'u-finance', role: 'FINANCE' },
      today: '2026-11-27',
      purchaseApprovers: ['PLANNER'],
      canOpen: (path) => path === '/accounting',
    });
    const group = attention.groups.find((g) => g.key === 'journal-approvals')!;
    expect(group.count).toBe(2);
    expect(group.title).toBe('2 manual journals await your approval');
    expect(group.path).toBe('/accounting?tab=journals');
    expect(group.items.map((i) => i.path)).toEqual([
      '/accounting?tab=journals&journal=A1',
      '/accounting?tab=journals&journal=B2',
    ]);
    expect(group.items[0].body).toBe('Accrued electricity · Rs. 182,450 · dated 2026-11-27');
    expect(attention.total).toBe(2);
  });

  it('links a single journal directly and counts accounts to review', () => {
    const attention = buildAttention({
      data: state({
        awaiting_approval: 1,
        awaiting_my_approval: 1,
        drafts: 0,
        needs_review: 2,
        awaiting: [journal('C3')],
      }),
      user: { id: 'u-admin', role: 'ADMIN' },
      today: '2026-11-27',
      purchaseApprovers: ['PLANNER'],
      canOpen: () => true,
    });
    expect(attention.groups.find((g) => g.key === 'journal-approvals')!.path).toBe(
      '/accounting?tab=journals&journal=C3',
    );
    const review = attention.groups.find((g) => g.key === 'accounts-review')!;
    expect(review).toMatchObject({ tone: 'blue', count: 2, title: '2 ledger accounts need review' });
    expect(attention.total).toBe(3);
  });

  it('shows nothing to roles that cannot open the Accounting page', () => {
    const attention = buildAttention({
      data: state({
        awaiting_approval: 1,
        awaiting_my_approval: 1,
        drafts: 0,
        needs_review: 1,
        awaiting: [journal('D4')],
      }),
      user: { id: 'u-planner', role: 'PLANNER' },
      today: '2026-11-27',
      purchaseApprovers: ['PLANNER'],
      canOpen: (path) => path !== '/accounting',
    });
    expect(attention.caughtUp).toBe(true);
  });
});
