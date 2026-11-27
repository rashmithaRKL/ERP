import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AnimatePresence } from 'motion/react';
import { Undo2 } from 'lucide-react';
import { Overlay, Table } from '../../components';
import { request } from '../../lib/api';
import { date, money, num, text, today } from '../../lib/format';
import { QueryState } from '../inventory/shared';
import { Facts } from '../quality/shared';
import { ACCOUNTING, useAccountingCommand } from './api';
import type { EntryDetail } from './api';
import { ReasonDialog, SourceBadge } from './shared';

/** One posted journal entry (`GET /accounting/entries/{id}`) with its lines and, for finance users, its reversal. */
export function EntryDialog({
  id,
  onClose,
  onOpenJournal,
}: {
  id: string;
  onClose: () => void;
  onOpenJournal: (id: string) => void;
}) {
  const entry = useQuery({
    queryKey: [ACCOUNTING, 'entry', id],
    queryFn: () => request<EntryDetail>(`/accounting/entries/${id}`),
  });
  const { run } = useAccountingCommand();
  const [reversing, setReversing] = useState(false);
  const e = entry.data;
  return (
    <Overlay
      title={e ? text(e.reference) : 'Journal entry'}
      subtitle="A posted entry is immutable: correct it with a reversal"
      onClose={onClose}
      wide
    >
      <QueryState pending={entry.isPending} error={entry.error} retry={() => entry.refetch()}>
        {e && (
          <>
            <div className="drawer-body">
              <div className="detail-product">
                <div>
                  <h2>{text(e.description)}</h2>
                  <p>
                    Posted {date(e.posting_date)} by {text(e.actor)}
                  </p>
                </div>
                <SourceBadge source={e.source} />
              </div>
              <Facts
                items={[
                  ['Posting date', date(e.posting_date)],
                  ['Total', money(e.total)],
                  [
                    'Manual journal',
                    e.manual_journal_id ? (
                      <button
                        className="text-button"
                        onClick={() => onOpenJournal(text(e.manual_journal_id))}
                      >
                        {text(e.manual_journal_number)}
                      </button>
                    ) : (
                      ''
                    ),
                  ],
                  [
                    'Reverses',
                    e.reverses_entry_id
                      ? `${text(e.reverses_reference)} of ${date(e.reverses_posting_date)}`
                      : '',
                  ],
                  ['Reversed', e.reversed_by_entry_id ? `On ${date(e.reversed_on)}` : ''],
                ]}
              />
              <h3>Lines</h3>
              <Table
                rows={e.lines}
                compact
                label="Entry lines"
                pageSize={Math.max(e.lines.length, 1)}
                columns={[
                  {
                    label: 'Account',
                    render: (l) => (
                      <div className="cell-stack">
                        <strong>{text(l.account_name) || text(l.account)}</strong>
                        <span>{text(l.account)}</span>
                      </div>
                    ),
                  },
                  { label: 'Memo', render: (l) => text(l.memo) || '—' },
                  {
                    label: 'Debit',
                    render: (l) => <span className="acc-amount">{num(l.debit) ? money(l.debit) : '—'}</span>,
                  },
                  {
                    label: 'Credit',
                    render: (l) => (
                      <span className="acc-amount">{num(l.credit) ? money(l.credit) : '—'}</span>
                    ),
                  },
                ]}
              />
            </div>
            {e.can_reverse && (
              <div className="dialog-footer">
                <span className="muted inv-small">
                  Reversing posts the same lines with debit and credit swapped; stock and documents stay as
                  they are.
                </span>
                <span className="grow" />
                <button className="button primary" onClick={() => setReversing(true)}>
                  <Undo2 size={15} />
                  Reverse entry
                </button>
              </div>
            )}
          </>
        )}
      </QueryState>
      <AnimatePresence>
        {reversing && e && (
          <ReasonDialog
            key="reverse"
            title={`Reverse ${text(e.reference)}`}
            subtitle={`Posts a reversal of this ${money(e.total)} entry. An entry can be reversed once.`}
            submit="Reverse entry"
            withDate={{
              label: 'Reversal posting date',
              defaultValue: today() < text(e.posting_date) ? text(e.posting_date) : today(),
              hint: 'In an open period, on or after the original posting date.',
            }}
            minDate={text(e.posting_date)}
            onClose={() => setReversing(false)}
            run={(v, key) =>
              run(
                `/accounting/entries/${id}/reverse`,
                { postingDate: v.postingDate || null, reason: v.reason },
                key,
                `${text(e.reference)} reversed successfully.`,
              )
            }
          />
        )}
      </AnimatePresence>
    </Overlay>
  );
}
