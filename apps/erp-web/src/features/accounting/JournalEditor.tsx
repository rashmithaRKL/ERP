import { useRef, useState } from 'react';
import type { FormEvent, KeyboardEvent } from 'react';
import { AnimatePresence, motion } from 'motion/react';
import { CircleAlert, CircleCheck, LoaderCircle, Plus, Trash2 } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay } from '../../components';
import type { Row } from '../../lib/api';
import { text, today } from '../../lib/format';
import { useMotionPreferences } from '../../motion/motion';
import { TYPE_LABELS, useAccountingCommand, useChart } from './api';
import type { JournalDetail } from './api';
import { centsToDecimal, formatCents, sumCents, toCents } from './cents';

type Line = { key: string; account: string; debit: string; credit: string; memo: string };

const blank = (): Line => ({ key: crypto.randomUUID(), account: '', debit: '', credit: '', memo: '' });

/** A server amount (`100`, `99.5`) as an editable string to the cent. */
const amountText = (value: unknown) => {
  const cents = toCents(text(value ?? ''));
  return cents && cents > 0n ? centsToDecimal(cents) : '';
};

/**
 * Manual journal editor (`POST /accounting/journals`, `/accounting/journals/{id}`): posting date, narration and lines
 * (account, debit or credit to the cent, memo). Totals and the balancing indicator use exact cents. "Save draft" keeps
 * an incomplete journal; "Save & submit" sends a balanced one for approval (or posts it when the policy needs no second
 * person). Enter in the last line adds a line; Ctrl+Delete removes the current one.
 */
export function JournalEditor({
  journal,
  onClose,
  onSaved,
}: {
  journal?: JournalDetail;
  onClose: () => void;
  onSaved: (id: string) => void;
}) {
  const { notify } = useWorkspace();
  const { reduced } = useMotionPreferences();
  const chart = useChart();
  const accounts = (chart.data?.items ?? []).filter((a) => a.postable && a.active);
  const { run, busy } = useAccountingCommand();
  const saveKey = useRef(crypto.randomUUID());
  const submitKey = useRef(crypto.randomUUID());
  const formRef = useRef<HTMLFormElement>(null);
  const [postingDate, setPostingDate] = useState(text(journal?.posting_date) || today());
  const [narration, setNarration] = useState(text(journal?.narration));
  const [lines, setLines] = useState<Line[]>(() =>
    journal?.lines.length
      ? journal.lines.map((l: Row) => ({
          key: crypto.randomUUID(),
          account: text(l.account),
          debit: amountText(l.debit),
          credit: amountText(l.credit),
          memo: text(l.memo),
        }))
      : [blank(), blank()],
  );
  const [error, setError] = useState('');

  const debits = sumCents(lines.map((l) => l.debit));
  const credits = sumCents(lines.map((l) => l.credit));
  const difference = debits - credits;
  const balanced = debits > 0n && difference === 0n;
  const filled = lines.filter((l) => l.account || l.debit || l.credit || l.memo);
  // A journal loaded with an account that is no longer offered keeps it visible in the picker.
  const missing = lines.map((l) => l.account).filter((c) => c && !accounts.some((a) => a.code === c));

  const update = (index: number, patch: Partial<Line>) =>
    setLines((prev) => prev.map((l, i) => (i === index ? { ...l, ...patch } : l)));

  function onLineKey(e: KeyboardEvent<HTMLDivElement>, index: number) {
    if (e.key === 'Delete' && e.ctrlKey && lines.length > 1) {
      e.preventDefault();
      setLines(lines.filter((_, i) => i !== index));
    } else if (e.key === 'Enter' && index === lines.length - 1 && !(e.target instanceof HTMLSelectElement)) {
      e.preventDefault();
      setLines([...lines, blank()]);
    }
  }

  function validate(submit: boolean) {
    for (const l of filled) {
      const number = lines.indexOf(l) + 1;
      if (!l.account) return `Line ${number}: choose an account.`;
      const dr = toCents(l.debit);
      const cr = toCents(l.credit);
      if ((l.debit && dr === null) || (l.credit && cr === null))
        return `Line ${number}: amounts are numbers with at most two decimals.`;
      if (!!dr === !!cr || (dr ?? 0n) + (cr ?? 0n) <= 0n)
        return `Line ${number}: enter either a debit or a credit amount.`;
    }
    if (submit && filled.length < 2) return 'A journal needs at least two lines to be submitted.';
    if (submit && !balanced)
      return `The journal does not balance yet: debits ${formatCents(debits)}, credits ${formatCents(credits)}.`;
    return '';
  }

  async function save(submit: boolean) {
    const problem = validate(submit);
    setError(problem);
    if (problem) return;
    try {
      const body = {
        postingDate,
        narration: narration.trim(),
        lines: filled.map((l) => ({
          account: l.account,
          debit: l.debit ? centsToDecimal(toCents(l.debit)!) : null,
          credit: l.credit ? centsToDecimal(toCents(l.credit)!) : null,
          memo: l.memo.trim() || null,
        })),
      };
      const saved = await run<Row>(
        journal ? `/accounting/journals/${journal.id}` : '/accounting/journals',
        body,
        saveKey.current,
        submit ? undefined : `${text(journal?.number) || 'Draft'} saved successfully.`,
      );
      if (submit) {
        try {
          const submitted = await run<Row>(`/accounting/journals/${saved.id}/submit`, {}, submitKey.current);
          notify(
            submitted.status === 'POSTED'
              ? `${text(saved.number)} posted successfully (no second approval needed by policy).`
              : `${text(saved.number)} submitted successfully; a second authorized person must approve it.`,
          );
        } catch (e) {
          notify(
            `${text(saved.number)} was saved as a draft, but submitting failed: ${(e as Error).message}`,
          );
        }
      }
      onSaved(saved.id);
      onClose();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    void save(true);
  }

  return (
    <Overlay
      title={journal ? `Edit ${text(journal.number)}` : 'New manual journal'}
      subtitle="Amounts in LKR to the cent. A journal posts on its posting date once it is approved."
      onClose={() => !busy && onClose()}
      wide
    >
      <form ref={formRef} onSubmit={onSubmit}>
        <div className="acc-editor-head">
          <label className="field">
            <span>Posting date</span>
            <input
              type="date"
              required
              value={postingDate}
              onChange={(e) => setPostingDate(e.target.value)}
            />
            <em>Must fall in an open period.</em>
          </label>
          <label className="field">
            <span>Narration</span>
            <input
              required
              maxLength={400}
              placeholder="e.g. Accrued electricity for November"
              value={narration}
              onChange={(e) => setNarration(e.target.value)}
            />
          </label>
        </div>
        <div className="acc-lines" role="group" aria-label="Journal lines">
          <div className="acc-line head" aria-hidden="true">
            <span />
            <span>Account</span>
            <span>Debit</span>
            <span>Credit</span>
            <span>Memo</span>
            <span />
          </div>
          <AnimatePresence initial={false}>
            {lines.map((line, index) => {
              const n = index + 1;
              const badDebit = !!line.debit && toCents(line.debit) === null;
              const badCredit = !!line.credit && toCents(line.credit) === null;
              return (
                <motion.div
                  key={line.key}
                  className="acc-line"
                  onKeyDown={(e) => onLineKey(e, index)}
                  initial={reduced ? false : { opacity: 0, y: -4 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={reduced ? { opacity: 0 } : { opacity: 0, height: 0 }}
                >
                  <span className="acc-line-no">
                    <span className="acc-line-label">Line</span>
                    {n}
                  </span>
                  <label className="acc-line-account">
                    <span className="acc-line-label">Account</span>
                    <select
                      aria-label={`Line ${n} account`}
                      value={line.account}
                      onChange={(e) => update(index, { account: e.target.value })}
                    >
                      <option value="">Choose an account…</option>
                      {missing.includes(line.account) && line.account && (
                        <option value={line.account}>{line.account} (not available)</option>
                      )}
                      {accounts.map((a) => (
                        <option key={a.id} value={a.code}>
                          {a.code} · {a.name} ({TYPE_LABELS[a.type]})
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    <span className="acc-line-label">Debit</span>
                    <input
                      aria-label={`Line ${n} debit`}
                      inputMode="decimal"
                      autoComplete="off"
                      placeholder="0.00"
                      value={line.debit}
                      aria-invalid={badDebit}
                      onChange={(e) =>
                        update(index, { debit: e.target.value, credit: e.target.value ? '' : line.credit })
                      }
                    />
                  </label>
                  <label>
                    <span className="acc-line-label">Credit</span>
                    <input
                      aria-label={`Line ${n} credit`}
                      inputMode="decimal"
                      autoComplete="off"
                      placeholder="0.00"
                      value={line.credit}
                      aria-invalid={badCredit}
                      onChange={(e) =>
                        update(index, { credit: e.target.value, debit: e.target.value ? '' : line.debit })
                      }
                    />
                  </label>
                  <label className="acc-line-memo">
                    <span className="acc-line-label">Memo</span>
                    <input
                      aria-label={`Line ${n} memo`}
                      maxLength={200}
                      placeholder="Optional"
                      value={line.memo}
                      onChange={(e) => update(index, { memo: e.target.value })}
                    />
                  </label>
                  <button
                    type="button"
                    className="icon-button acc-line-remove"
                    aria-label={`Remove line ${n}`}
                    disabled={lines.length === 1}
                    onClick={() => setLines(lines.filter((_, i) => i !== index))}
                  >
                    <Trash2 size={16} />
                  </button>
                </motion.div>
              );
            })}
          </AnimatePresence>
        </div>
        <div className="acc-editor-actions">
          <button
            className="button secondary small"
            type="button"
            onClick={() => setLines([...lines, blank()])}
          >
            <Plus size={14} />
            Add line
          </button>
          {difference !== 0n && (
            <button
              className="button secondary small"
              type="button"
              title="Adds a line for the difference"
              onClick={() =>
                setLines([
                  ...lines,
                  {
                    ...blank(),
                    debit: difference < 0n ? centsToDecimal(-difference) : '',
                    credit: difference > 0n ? centsToDecimal(difference) : '',
                  },
                ])
              }
            >
              <Plus size={14} />
              Add balancing line
            </button>
          )}
        </div>
        <div className="acc-balance-bar" aria-live="polite">
          <span>
            Debits <strong>{formatCents(debits)}</strong>
          </span>
          <span>
            Credits <strong>{formatCents(credits)}</strong>
          </span>
          {balanced ? (
            <span className="acc-state ok">
              <CircleCheck size={16} />
              Balanced
            </span>
          ) : (
            <span className="acc-state off">
              <CircleAlert size={16} />
              {debits === 0n && credits === 0n
                ? 'Enter the amounts'
                : `Out of balance by ${formatCents(difference < 0n ? -difference : difference)}`}
            </span>
          )}
        </div>
        {error && (
          <div className="form-error" role="alert" style={{ margin: '0 22px 16px' }}>
            {error}
          </div>
        )}
        <div className="dialog-footer">
          <button type="button" className="button secondary" disabled={busy} onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={() => {
              if (formRef.current?.reportValidity()) void save(false);
            }}
          >
            Save draft
          </button>
          <button className="button primary" disabled={busy}>
            {busy && <LoaderCircle className="spin" size={15} />}
            Save & submit
          </button>
        </div>
      </form>
    </Overlay>
  );
}
