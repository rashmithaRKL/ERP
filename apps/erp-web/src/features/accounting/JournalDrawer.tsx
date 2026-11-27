import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AnimatePresence } from 'motion/react';
import { Check, LoaderCircle, Pencil, Send, Undo2, X } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import { request } from '../../lib/api';
import { date, money, num, text, today } from '../../lib/format';
import { ActivityFeed, dateTime, Lifecycle, QueryState } from '../inventory/shared';
import { ActionError, Facts } from '../quality/shared';
import { ACCOUNTING, useAccountingCommand } from './api';
import type { JournalDetail } from './api';
import { EntryDialog } from './EntryDialog';
import { JournalEditor } from './JournalEditor';
import { JournalBadge, ReasonDialog } from './shared';

type Reasoned = 'return' | 'cancel' | 'reverse';

/**
 * One manual journal (`GET /accounting/journals/{id}`): lifecycle, facts, lines with totals and activity; edit, submit,
 * approve, return to draft, cancel and reverse as the server allows for the viewer (`can`).
 */
export function JournalDrawer({ id, onClose }: { id: string; onClose: () => void }) {
  const { notify } = useWorkspace();
  const detail = useQuery({
    queryKey: [ACCOUNTING, 'journal', id],
    queryFn: () => request<JournalDetail>(`/accounting/journals/${id}`),
  });
  const { run, busy } = useAccountingCommand();
  const submitKey = useRef(crypto.randomUUID());
  const approveKey = useRef(crypto.randomUUID());
  const [editing, setEditing] = useState(false);
  const [reasoned, setReasoned] = useState<Reasoned>();
  const [entry, setEntry] = useState<string>();
  const [error, setError] = useState('');
  const j = detail.data;
  const status = text(j?.status);
  const step = { DRAFT: 0, SUBMITTED: 1, POSTED: 2, REVERSED: 3 }[status] ?? 0;

  async function act(kind: 'submit' | 'approve') {
    setError('');
    try {
      const result = await run<{ status: string }>(
        `/accounting/journals/${id}/${kind}`,
        {},
        (kind === 'submit' ? submitKey : approveKey).current,
      );
      // A later submit or approval (after a return to draft) is a new command.
      (kind === 'submit' ? submitKey : approveKey).current = crypto.randomUUID();
      notify(
        kind === 'approve'
          ? `${text(j?.number)} approved and posted successfully.`
          : result.status === 'POSTED'
            ? `${text(j?.number)} posted successfully (no second approval needed by policy).`
            : `${text(j?.number)} submitted successfully; a second authorized person must approve it.`,
      );
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <Overlay
      title={text(j?.number) || 'Manual journal'}
      subtitle="A manual journal posts one balanced entry on its posting date once approved"
      onClose={onClose}
      drawer
      wide
    >
      <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
        {j && (
          <>
            <div className="drawer-body">
              <div className="detail-product">
                <div>
                  <h2>{text(j.narration)}</h2>
                  <p>
                    Created by {text(j.created_by_name)} · {dateTime(j.created_at)}
                  </p>
                </div>
                <JournalBadge status={j.status} />
              </div>
              {status === 'CANCELLED' ? (
                <div className="inv-warning">
                  <div>
                    <strong>Cancelled by {text(j.cancelled_by_name)}</strong>
                    <p>{text(j.cancel_reason)}</p>
                  </div>
                </div>
              ) : (
                <Lifecycle steps={['Draft', 'Submitted', 'Posted', 'Reversed']} index={step} />
              )}
              <div className="detail-stats">
                <div>
                  <span>Posting date</span>
                  <strong>{date(j.posting_date)}</strong>
                </div>
                <div>
                  <span>Debits</span>
                  <strong>{money(j.total_debit)}</strong>
                </div>
                <div>
                  <span>Credits</span>
                  <strong>{money(j.total_credit)}</strong>
                </div>
                <div>
                  <span>Lines</span>
                  <strong>{text(j.line_count)}</strong>
                </div>
              </div>
              <Facts
                items={[
                  ['Last edited by', status === 'DRAFT' ? text(j.updated_by_name) : ''],
                  [
                    'Submitted',
                    j.submitted_at ? `${dateTime(j.submitted_at)} by ${text(j.submitted_by_name)}` : '',
                  ],
                  [
                    'Approved',
                    j.posted_at
                      ? j.approved_by
                        ? `${dateTime(j.approved_at)} by ${text(j.approved_by_name)}`
                        : 'By policy, no second approval needed'
                      : '',
                  ],
                  [
                    'Posted entry',
                    j.entry_id ? (
                      <button className="text-button" onClick={() => setEntry(text(j.entry_id))}>
                        View entry of {date(j.posting_date)}
                      </button>
                    ) : (
                      ''
                    ),
                  ],
                  [
                    'Reversal',
                    j.reversal_entry_id ? (
                      <button className="text-button" onClick={() => setEntry(text(j.reversal_entry_id))}>
                        Posted {date(j.reversal_date)} by {text(j.reversed_by_name)}
                      </button>
                    ) : (
                      ''
                    ),
                  ],
                  ['Reversal reason', text(j.reversal_reason)],
                  [
                    'Returned to draft',
                    status === 'DRAFT' && j.returned_at
                      ? `${dateTime(j.returned_at)} by ${text(j.returned_by_name)}: ${text(j.return_reason)}`
                      : '',
                  ],
                ]}
              />
              {status === 'SUBMITTED' && !j.can.approve && (
                <p className="muted inv-small">
                  Waiting for an administrator
                  {j.approverRoles.includes('FINANCE') ? ' or a finance user' : ''} who neither created,
                  edited nor submitted it.
                </p>
              )}
              <h3>Lines</h3>
              <Table
                rows={[
                  ...j.lines,
                  {
                    id: 'total',
                    total: true,
                    account: '',
                    memo: 'Total',
                    debit: j.total_debit,
                    credit: j.total_credit,
                  },
                ]}
                compact
                label="Journal lines"
                pageSize={j.lines.length + 1}
                columns={[
                  {
                    label: 'Account',
                    render: (l) =>
                      l.total ? (
                        <strong>Total</strong>
                      ) : (
                        <div className="cell-stack">
                          <strong>{text(l.account_name) || text(l.account)}</strong>
                          <span>
                            {text(l.account)}
                            {l.account_active === false ? ' · inactive' : ''}
                          </span>
                        </div>
                      ),
                  },
                  { label: 'Memo', render: (l) => (l.total ? '' : text(l.memo) || '—') },
                  {
                    label: 'Debit',
                    render: (l) => (
                      <span className="acc-amount">
                        {l.total ? <strong>{money(l.debit)}</strong> : num(l.debit) ? money(l.debit) : '—'}
                      </span>
                    ),
                  },
                  {
                    label: 'Credit',
                    render: (l) => (
                      <span className="acc-amount">
                        {l.total ? <strong>{money(l.credit)}</strong> : num(l.credit) ? money(l.credit) : '—'}
                      </span>
                    ),
                  },
                ]}
              />
              <h3>Activity</h3>
              <ActivityFeed events={j.activity} />
              <ActionError message={error} />
            </div>
            {(j.can.edit ||
              j.can.submit ||
              j.can.approve ||
              j.can.return ||
              j.can.cancel ||
              j.can.reverse) && (
              <div className="dialog-footer detail-footer">
                {j.can.cancel && (
                  <button className="button secondary" disabled={busy} onClick={() => setReasoned('cancel')}>
                    <X size={15} />
                    Cancel journal
                  </button>
                )}
                {j.can.return && (
                  <button className="button secondary" disabled={busy} onClick={() => setReasoned('return')}>
                    <Undo2 size={15} />
                    Return to draft
                  </button>
                )}
                <span className="grow" />
                {j.can.edit && (
                  <button className="button secondary" disabled={busy} onClick={() => setEditing(true)}>
                    <Pencil size={15} />
                    Edit
                  </button>
                )}
                {j.can.submit && (
                  <button className="button primary" disabled={busy} onClick={() => act('submit')}>
                    {busy ? <LoaderCircle className="spin" size={15} /> : <Send size={15} />}
                    Submit
                  </button>
                )}
                {j.can.approve && (
                  <button className="button primary" disabled={busy} onClick={() => act('approve')}>
                    {busy ? <LoaderCircle className="spin" size={15} /> : <Check size={15} />}
                    Approve & post
                  </button>
                )}
                {j.can.reverse && (
                  <button className="button primary" disabled={busy} onClick={() => setReasoned('reverse')}>
                    <Undo2 size={15} />
                    Reverse
                  </button>
                )}
              </div>
            )}
          </>
        )}
      </QueryState>
      <AnimatePresence>
        {editing && j && (
          <JournalEditor key="edit" journal={j} onClose={() => setEditing(false)} onSaved={() => {}} />
        )}
        {reasoned === 'cancel' && j && (
          <ReasonDialog
            key="cancel"
            title={`Cancel ${text(j.number)}`}
            subtitle="Nothing was posted, so nothing is reversed. A cancelled journal stays on record."
            submit="Cancel journal"
            onClose={() => setReasoned(undefined)}
            run={(v, key) =>
              run(
                `/accounting/journals/${id}/cancel`,
                { reason: v.reason },
                key,
                `${text(j.number)} cancelled.`,
              )
            }
          />
        )}
        {reasoned === 'return' && j && (
          <ReasonDialog
            key="return"
            title={`Return ${text(j.number)} to draft`}
            subtitle="The journal becomes editable again and must be submitted once more."
            submit="Return to draft"
            onClose={() => setReasoned(undefined)}
            run={(v, key) =>
              run(
                `/accounting/journals/${id}/return`,
                { reason: v.reason },
                key,
                `${text(j.number)} returned to draft.`,
              )
            }
          />
        )}
        {reasoned === 'reverse' && j && (
          <ReasonDialog
            key="reverse"
            title={`Reverse ${text(j.number)}`}
            subtitle={`Posts the ${money(j.total_debit)} entry again with debit and credit swapped. A journal is reversed once.`}
            submit="Reverse journal"
            withDate={{
              label: 'Reversal posting date',
              defaultValue: today() < text(j.posting_date) ? text(j.posting_date) : today(),
              hint: 'In an open period, on or after the journal’s posting date.',
            }}
            minDate={text(j.posting_date)}
            onClose={() => setReasoned(undefined)}
            run={(v, key) =>
              run(
                `/accounting/entries/${text(j.entry_id)}/reverse`,
                { postingDate: v.postingDate || null, reason: v.reason },
                key,
                `${text(j.number)} reversed successfully.`,
              )
            }
          />
        )}
        {entry && (
          <EntryDialog
            key={entry}
            id={entry}
            onClose={() => setEntry(undefined)}
            onOpenJournal={() => setEntry(undefined)}
          />
        )}
      </AnimatePresence>
    </Overlay>
  );
}
