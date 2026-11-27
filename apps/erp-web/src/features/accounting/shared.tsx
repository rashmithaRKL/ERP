import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { LoaderCircle } from 'lucide-react';
import { Overlay } from '../../components';
import { text } from '../../lib/format';
import { JOURNAL_STATUS_LABELS, SOURCE_LABELS, journalTone } from './api';
import type { Account } from './api';

/** System / header / contra / review / inactive markers of an account (always readable as text). */
export function AccountTags({ account }: { account: Account }) {
  return (
    <span className="acc-tags">
      {account.needs_review && <span className="acc-tag review">Needs review</span>}
      {!account.active && <span className="acc-tag inactive">Inactive</span>}
      {account.system && <span className="acc-tag system">System</span>}
      {!account.postable && <span className="acc-tag">Header</span>}
      {account.contra && <span className="acc-tag">Contra</span>}
      {account.active && !account.needs_review && !account.system && account.postable && !account.contra && (
        <span className="muted">—</span>
      )}
    </span>
  );
}

export function JournalBadge({ status }: { status: unknown }) {
  return (
    <span className={`badge ${journalTone(status)}`}>
      <i />
      {(JOURNAL_STATUS_LABELS[text(status)] ?? text(status)).toLowerCase()}
    </span>
  );
}

export function SourceBadge({ source }: { source: unknown }) {
  const code = text(source);
  return (
    <span className={`badge ${code === 'REVERSAL' ? 'blue' : code === 'MANUAL' ? 'amber' : 'gray'}`}>
      <i />
      {(SOURCE_LABELS[code] ?? code).toLowerCase()}
    </span>
  );
}

/**
 * A reason (and optionally a posting date) for return to draft, cancellation or reversal. `run` receives the values
 * and the dialog's idempotency key, which stays the same for every retry of this opening.
 */
export function ReasonDialog({
  title,
  subtitle,
  submit,
  label = 'Reason',
  withDate,
  minDate,
  onClose,
  run,
}: {
  title: string;
  subtitle: string;
  submit: string;
  label?: string;
  withDate?: { label: string; defaultValue: string; hint: string };
  minDate?: string;
  onClose: () => void;
  run: (values: { reason: string; postingDate?: string }, key: string) => Promise<unknown>;
}) {
  const key = useRef(crypto.randomUUID());
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    setPending(true);
    setError('');
    try {
      await run(
        {
          reason: text(form.get('reason')).trim(),
          postingDate: withDate ? text(form.get('postingDate')) || undefined : undefined,
        },
        key.current,
      );
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }
  return (
    <Overlay title={title} subtitle={subtitle} onClose={() => !pending && onClose()}>
      <form onSubmit={onSubmit}>
        <div className="acc-reason">
          {withDate && (
            <label className="field">
              <span>{withDate.label}</span>
              <input
                type="date"
                name="postingDate"
                required
                min={minDate}
                defaultValue={withDate.defaultValue}
              />
              <em>{withDate.hint}</em>
            </label>
          )}
          <label className="field">
            <span>{label}</span>
            <textarea name="reason" rows={3} required minLength={5} maxLength={400} />
            <em>At least 5 characters. Kept with the journal and in the audit trail.</em>
          </label>
          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <button type="button" className="button secondary" disabled={pending} onClick={onClose}>
            Cancel
          </button>
          <button className="button primary" disabled={pending}>
            {pending && <LoaderCircle className="spin" size={15} />}
            {submit}
          </button>
        </div>
      </form>
    </Overlay>
  );
}
