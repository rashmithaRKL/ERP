import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { Pencil, Power, PowerOff } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Overlay, Table } from '../../components';
import type { Value } from '../../lib/api';
import { date, money, num, text } from '../../lib/format';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import type { CursorPage } from '../lists/cursorPage';
import { AccountDialog } from './AccountDialog';
import { ACCOUNTING, FINANCE_ROLES, TYPE_LABELS, useAccountingCommand, useChart } from './api';
import type { Account } from './api';
import { drCr } from './cents';
import { EntryDialog } from './EntryDialog';
import { AccountTags, SourceBadge } from './shared';

const PAGE = 50;

/**
 * Account ledger (`GET /accounting/accounts/{id}/ledger`): opening balance before the date range, posted lines oldest
 * first with the running balance, keyset-paged; a header account shows its sub-accounts' lines. Edit, deactivate and
 * reactivate for finance users; a line opens its journal entry (and its reversal).
 */
export function LedgerDrawer({
  id,
  onClose,
  onOpenJournal,
}: {
  id: string;
  onClose: () => void;
  onOpenJournal: (id: string) => void;
}) {
  const { can, notify } = useWorkspace();
  const chart = useChart();
  const account = chart.data?.items.find((a) => a.id === id);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [editing, setEditing] = useState(false);
  const [entry, setEntry] = useState<string>();
  const { run, busy } = useAccountingCommand();
  const ledger = useCursorPage(
    [ACCOUNTING, 'ledger', id],
    `/accounting/accounts/${id}/ledger`,
    { from, to },
    PAGE,
  );
  const data = ledger.query.data as (CursorPage & { opening?: Value; closing?: Value }) | undefined;
  const finance = can(...FINANCE_ROLES);

  async function toggleActive(a: Account) {
    try {
      await run(
        `/accounting/accounts/${a.id}/${a.active ? 'deactivate' : 'reactivate'}`,
        {},
        crypto.randomUUID(),
        `Account ${text(a.code)} ${a.active ? 'deactivated' : 'reactivated'} successfully.`,
      );
    } catch (e) {
      notify((e as Error).message);
    }
  }

  return (
    <Overlay
      title={account ? `${text(account.code)} · ${text(account.name)}` : 'Account ledger'}
      subtitle="Posted lines by posting date, with the running balance"
      onClose={onClose}
      drawer
      wide
    >
      <div className="drawer-body">
        {account && (
          <>
            <div className="detail-product">
              <div>
                <h2>{text(account.name)}</h2>
                <p>
                  {text(account.code)} · {TYPE_LABELS[text(account.type)]} · normal balance{' '}
                  {text(account.normal_balance).toLowerCase()}
                  {account.postable ? '' : ' · header (includes its sub-accounts)'}
                </p>
              </div>
              <AccountTags account={account} />
            </div>
            {text(account.description) && <p className="inv-notes">{text(account.description)}</p>}
            {account.needs_review && (
              <div className="inv-warning">
                <div>
                  <strong>This account needs review</strong>
                  <p>
                    It was created from a code found in posted entries. Check its name, type and parent, then
                    save it to mark it reviewed.
                  </p>
                </div>
              </div>
            )}
          </>
        )}
        <div className="inv-filters" style={{ marginTop: 12 }}>
          <label className="inv-date">
            From
            <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="inv-date">
            To
            <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
          </label>
        </div>
        {data && (
          <div className="detail-stats">
            <div>
              <span>Opening balance</span>
              <strong>{drCr(data.opening)}</strong>
            </div>
            <div>
              <span>Debits</span>
              <strong>{money(data.totals?.debit)}</strong>
            </div>
            <div>
              <span>Credits</span>
              <strong>{money(data.totals?.credit)}</strong>
            </div>
            <div>
              <span>Closing balance</span>
              <strong>{drCr(data.closing)}</strong>
            </div>
          </div>
        )}
        <PageState paged={ledger}>
          {ledger.rows.length ? (
            <Table
              rows={ledger.rows}
              compact
              label="Account ledger"
              pageSize={Math.max(ledger.rows.length, 1)}
              onRow={(r) => setEntry(text(r.entry_id))}
              columns={[
                {
                  label: 'Posted',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>{date(r.posting_date)}</strong>
                      <span>{text(r.reference)}</span>
                    </div>
                  ),
                },
                {
                  label: 'Description',
                  render: (r) => (
                    <div className="cell-stack">
                      <span>{text(r.description)}</span>
                      <span>
                        {account && !account.postable ? `${text(r.account)} · ` : ''}
                        {text(r.memo)}
                        {r.reversed_by_entry_id ? ' · reversed' : ''}
                      </span>
                    </div>
                  ),
                },
                { label: 'Source', render: (r) => <SourceBadge source={r.source} /> },
                {
                  label: 'Debit',
                  render: (r) => <span className="acc-amount">{num(r.debit) ? money(r.debit) : '—'}</span>,
                },
                {
                  label: 'Credit',
                  render: (r) => <span className="acc-amount">{num(r.credit) ? money(r.credit) : '—'}</span>,
                },
                { label: 'Balance', render: (r) => <span className="acc-amount">{drCr(r.balance)}</span> },
              ]}
            />
          ) : (
            <Empty
              title="No postings in this range"
              body={
                from || to ? 'Widen the date range to see more lines.' : 'Nothing has been posted to it yet.'
              }
            />
          )}
          <PageFooter paged={ledger} label="lines" />
        </PageState>
      </div>
      {account && finance && (
        <div className="dialog-footer detail-footer">
          <span className="muted inv-small">
            {account.system ? 'System account: posting code uses it.' : 'Accounts are never deleted.'}
          </span>
          <span className="grow" />
          {!account.system && (
            <button className="button secondary" disabled={busy} onClick={() => toggleActive(account)}>
              {account.active ? <PowerOff size={15} /> : <Power size={15} />}
              {account.active ? 'Deactivate' : 'Reactivate'}
            </button>
          )}
          <button className="button primary" onClick={() => setEditing(true)}>
            <Pencil size={15} />
            Edit account
          </button>
        </div>
      )}
      <AnimatePresence>
        {editing && account && (
          <AccountDialog
            key="edit"
            account={account}
            accounts={chart.data?.items ?? []}
            onClose={() => setEditing(false)}
          />
        )}
        {entry && (
          <EntryDialog
            key={entry}
            id={entry}
            onClose={() => setEntry(undefined)}
            onOpenJournal={(journal) => {
              setEntry(undefined);
              onOpenJournal(journal);
            }}
          />
        )}
      </AnimatePresence>
    </Overlay>
  );
}
