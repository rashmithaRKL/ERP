import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { LoaderCircle } from 'lucide-react';
import { Overlay } from '../../components';
import type { Row } from '../../lib/api';
import { text } from '../../lib/format';
import { ACCOUNT_TYPES, TYPE_LABELS, useAccountingCommand } from './api';
import type { Account } from './api';

/**
 * Create or edit a chart-of-accounts entry (`POST /accounting/accounts`, `/accounting/accounts/{id}`). The server
 * enforces the rules; the dialog only offers what can work: a parent is a header of the same type (never the account
 * itself or one of its sub-accounts), system accounts keep their code and type, a used account keeps its code.
 */
export function AccountDialog({
  account,
  accounts,
  onClose,
  onSaved,
}: {
  account?: Account;
  accounts: Account[];
  onClose: () => void;
  onSaved?: (id: string) => void;
}) {
  const { run, busy } = useAccountingCommand();
  const key = useRef(crypto.randomUUID());
  const [type, setType] = useState(text(account?.type) || 'EXPENSE');
  const [error, setError] = useState('');
  const system = !!account?.system;
  const used = !!account && (account.posted || account.on_journals);
  const descendants = new Set<string>();
  if (account) {
    // The account and everything below it cannot become its parent.
    descendants.add(account.id);
    let grew = true;
    while (grew) {
      grew = false;
      for (const a of accounts)
        if (a.parent_id && descendants.has(a.parent_id) && !descendants.has(a.id)) {
          descendants.add(a.id);
          grew = true;
        }
    }
  }
  const parents = accounts.filter(
    (a) => !a.postable && a.active && a.type === type && !descendants.has(a.id),
  );

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const v = Object.fromEntries(new FormData(e.currentTarget).entries()) as Record<string, string>;
    setError('');
    try {
      const saved = await run<Row>(
        account ? `/accounting/accounts/${account.id}` : '/accounting/accounts',
        {
          code: (v.code ?? text(account?.code)).trim().toUpperCase(),
          name: v.name.trim(),
          type,
          contra: v.contra === 'on',
          header: v.header === 'on',
          parentId: v.parentId || null,
          description: v.description?.trim() || '',
        },
        key.current,
        account
          ? `Account ${text(account.code)} saved successfully.`
          : `Account ${v.code.trim().toUpperCase()} created successfully.`,
      );
      onSaved?.(saved.id);
      onClose();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <Overlay
      title={account ? `Edit ${text(account.code)}` : 'New ledger account'}
      subtitle={
        system
          ? 'A system account: posting code uses it, so its code, type and postable state stay as they are.'
          : 'Codes use capital letters, digits, underscores, dots or hyphens and are unique in your chart.'
      }
      onClose={() => !busy && onClose()}
    >
      <form onSubmit={submit}>
        <div className="form-fields">
          <label className="field">
            <span>Code</span>
            <input
              name="code"
              required
              maxLength={40}
              pattern="[A-Za-z0-9][A-Za-z0-9_.\-]{0,39}"
              defaultValue={text(account?.code)}
              disabled={system || used}
              style={{ textTransform: 'uppercase' }}
            />
            {used && !system && <em>Postings or manual journal lines use this code, so it is fixed.</em>}
          </label>
          <label className="field">
            <span>Name</span>
            <input name="name" required maxLength={120} defaultValue={text(account?.name)} />
          </label>
          <label className="field">
            <span>Type</span>
            <select
              value={type}
              disabled={system || (account?.children ?? 0) > 0}
              onChange={(e) => setType(e.target.value)}
            >
              {ACCOUNT_TYPES.map((t) => (
                <option key={t} value={t}>
                  {TYPE_LABELS[t]}
                </option>
              ))}
            </select>
            <em>Assets and expenses are debit accounts; liabilities, equity and income credit accounts.</em>
          </label>
          <label className="field">
            <span>
              Parent<small>Optional</small>
            </span>
            <select name="parentId" defaultValue={text(account?.parent_id)}>
              <option value="">Top level</option>
              {parents.map((p) => (
                <option key={p.id} value={p.id}>
                  {text(p.code)} · {text(p.name)}
                </option>
              ))}
            </select>
            <em>A header account of the same type.</em>
          </label>
          <label className="inv-check">
            <input
              type="checkbox"
              name="header"
              defaultChecked={!!account && !account.postable}
              disabled={system || (account?.children ?? 0) > 0 || (!!account && account.postable && used)}
            />
            Header account (groups sub-accounts, takes no postings)
          </label>
          <label className="inv-check">
            <input type="checkbox" name="contra" defaultChecked={!!account?.contra} disabled={system} />
            Contra account (normal balance on the opposite side)
          </label>
          <label className="field" style={{ gridColumn: '1 / -1' }}>
            <span>
              Description<small>Optional</small>
            </span>
            <textarea name="description" rows={2} maxLength={400} defaultValue={text(account?.description)} />
            {account?.needs_review && <em>Saving marks this account as reviewed.</em>}
          </label>
          {error && (
            <div className="form-error" role="alert" style={{ gridColumn: '1 / -1' }}>
              {error}
            </div>
          )}
        </div>
        {/* Disabled controls are not submitted: keep the stored values. */}
        {system || (account?.children ?? 0) > 0 ? (
          <input type="hidden" name="header" value={account?.postable ? '' : 'on'} />
        ) : null}
        {system ? <input type="hidden" name="contra" value={account?.contra ? 'on' : ''} /> : null}
        <div className="dialog-footer">
          <button type="button" className="button secondary" disabled={busy} onClick={onClose}>
            Cancel
          </button>
          <button className="button primary" disabled={busy}>
            {busy && <LoaderCircle className="spin" size={15} />}
            {account ? 'Save account' : 'Create account'}
          </button>
        </div>
      </form>
    </Overlay>
  );
}
