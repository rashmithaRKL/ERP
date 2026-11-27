import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { LoaderCircle } from 'lucide-react';
import { Overlay } from '../../components';
import { text } from '../../lib/format';
import { useAccountingCommand } from './api';
import type { JournalPolicy } from './api';

/**
 * The MANUAL_JOURNAL approval policy (administrators): whether a second person approves, above which total, and
 * whether finance users approve besides administrators. Saved through `POST /admin/approval-policies/MANUAL_JOURNAL`.
 */
export function PolicyDialog({ policy, onClose }: { policy: JournalPolicy; onClose: () => void }) {
  const { run, busy } = useAccountingCommand();
  const key = useRef(crypto.randomUUID());
  const [required, setRequired] = useState(policy.approvalRequired);
  const [error, setError] = useState('');
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const v = Object.fromEntries(new FormData(e.currentTarget).entries()) as Record<string, string>;
    setError('');
    try {
      await run(
        '/admin/approval-policies/MANUAL_JOURNAL',
        {
          approvalRequired: required,
          amountThreshold: required && v.threshold ? v.threshold : null,
          approverRoles: required && v.finance === 'on' ? ['FINANCE'] : [],
        },
        key.current,
        'Manual journal approval policy saved successfully.',
      );
      onClose();
    } catch (err) {
      setError((err as Error).message);
    }
  }
  return (
    <Overlay
      title="Manual journal approval"
      subtitle="Nobody approves a journal they created, edited or submitted, administrators included."
      onClose={() => !busy && onClose()}
    >
      <form onSubmit={submit}>
        <div className="form-fields">
          <label className="inv-check" style={{ gridColumn: '1 / -1' }}>
            <input type="checkbox" checked={required} onChange={(e) => setRequired(e.target.checked)} />A
            second person approves manual journals
          </label>
          <label className="field">
            <span>
              Only above (LKR)<small>Optional</small>
            </span>
            <input
              name="threshold"
              type="number"
              min="0"
              step="0.01"
              disabled={!required}
              defaultValue={text(policy.amountThreshold)}
              placeholder="Every journal"
            />
            <em>Journals at or below this total post when they are submitted.</em>
          </label>
          <label className="inv-check">
            <input
              type="checkbox"
              name="finance"
              disabled={!required}
              defaultChecked={policy.approverRoles.includes('FINANCE')}
            />
            Finance users approve (administrators always do)
          </label>
          {error && (
            <div className="form-error" role="alert" style={{ gridColumn: '1 / -1' }}>
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <button type="button" className="button secondary" disabled={busy} onClick={onClose}>
            Cancel
          </button>
          <button className="button primary" disabled={busy}>
            {busy && <LoaderCircle className="spin" size={15} />}
            Save policy
          </button>
        </div>
      </form>
    </Overlay>
  );
}
