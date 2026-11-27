import { lazy, Suspense } from 'react';
import { AnimatePresence } from 'motion/react';
import { LoaderCircle } from 'lucide-react';
import { useTabParam, useUrlParams } from '../app/urlState';
import { useStateData, useWorkspace } from '../app/workspace';
import { Empty, Panel } from '../components';
import '../features/accounting/accounting.css';
import '../features/admin/admin.css';
import '../features/inventory/inventory.css';
import '../features/quality/quality.css';
import { FINANCE_ROLES } from '../features/accounting/api';
import { ChartTab } from '../features/accounting/ChartTab';
import { JournalDrawer } from '../features/accounting/JournalDrawer';
import { JournalsTab } from '../features/accounting/JournalsTab';
import { LedgerDrawer } from '../features/accounting/LedgerDrawer';
import { TrialBalanceTab } from '../features/accounting/TrialBalanceTab';
import { fmt, num } from '../lib/format';
import { Tabs } from '../motion/motion';

// The accounting periods section is the Administration tab's component, loaded on demand.
const PeriodsTab = lazy(() =>
  import('../features/admin/PeriodsTab').then((m) => ({ default: m.PeriodsTab })),
);

const TABS = [
  { value: 'chart', label: 'Chart of accounts' },
  { value: 'journals', label: 'Journals' },
  { value: 'trial-balance', label: 'Trial balance' },
  { value: 'periods', label: 'Periods' },
];

/**
 * Route `/accounting` (V24, FINANCE and ADMIN): chart of accounts with balances and the account ledger, manual journals
 * with independent approval and reversal, the trial balance and the accounting periods (`?tab=journals|trial-balance|
 * periods`; `?journal=<id>` opens a journal, `?account=<id>` an account ledger).
 */
export default function AccountingPage() {
  const { can } = useWorkspace();
  const data = useStateData();
  const [tab, setTab] = useTabParam(
    TABS.map((t) => t.value),
    'chart',
  );
  const [params, update] = useUrlParams();
  const journal = params.get('journal');
  const account = params.get('account');
  if (!can(...FINANCE_ROLES))
    return (
      <Empty
        title="Accounting requires a finance role"
        body="Ask your administrator for finance access to the general ledger."
      />
    );
  const a = data.accounting;
  const awaiting = num(a?.awaiting_approval);
  const mine = num(a?.awaiting_my_approval);
  const review = num(a?.needs_review);
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Awaiting your approval</span>
          <strong className={mine ? 'acc-attention' : undefined}>{fmt(mine)}</strong>
        </div>
        <div>
          <span>Awaiting approval (all)</span>
          <strong>{fmt(awaiting)}</strong>
        </div>
        <div>
          <span>Draft journals</span>
          <strong>{fmt(a?.drafts)}</strong>
        </div>
        <div>
          <span>Accounts to review</span>
          <strong className={review ? 'acc-attention' : undefined}>{fmt(review)}</strong>
        </div>
      </div>
      <Panel className={`inventory-panel acc-panel ${tab === 'periods' ? 'admin-tabbar' : ''}`}>
        <Tabs
          label="Accounting views"
          items={TABS.map((t) => ({
            ...t,
            count:
              t.value === 'journals' && awaiting
                ? awaiting
                : t.value === 'chart' && review
                  ? review
                  : undefined,
          }))}
          value={tab}
          onChange={setTab}
        />
        {tab === 'chart' && <ChartTab onOpenAccount={(id) => update({ account: id })} />}
        {tab === 'journals' && <JournalsTab onOpen={(id) => update({ journal: id })} />}
        {tab === 'trial-balance' && <TrialBalanceTab onOpenAccount={(id) => update({ account: id })} />}
      </Panel>
      {tab === 'periods' && (
        <Suspense
          fallback={
            <div className="panel inv-state" role="status">
              <LoaderCircle className="spin" size={20} />
              <span>Loading…</span>
            </div>
          }
        >
          <PeriodsTab />
        </Suspense>
      )}
      <AnimatePresence>
        {account && (
          <LedgerDrawer
            key={`account-${account}`}
            id={account}
            onClose={() => update({ account: null })}
            onOpenJournal={(id) => update({ account: null, journal: id })}
          />
        )}
        {journal && (
          <JournalDrawer key={`journal-${journal}`} id={journal} onClose={() => update({ journal: null })} />
        )}
      </AnimatePresence>
    </>
  );
}
