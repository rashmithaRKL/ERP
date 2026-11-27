import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Check, Copy, KeyRound, LoaderCircle, ShieldAlert, ShieldOff, UserCheck, UserX } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay } from '../../components';
import type { Row } from '../../lib/api';
import { request } from '../../lib/api';
import { text } from '../../lib/format';
import { dateTime, ROLE_LABELS, ROLES } from './api';
import { UserStatus } from './shared';

type Pending = 'edit' | 'reset' | 'deactivate' | 'reactivate' | 'mfa';

/**
 * One user: details, name/role edit, one-time temporary password, two-step sign-in reset (V17), deactivate/reactivate. Each action keeps its own
 * idempotency key for the dialog's lifetime, so a retry after a network error cannot run twice.
 */
export function UserDialog({
  user,
  onClose,
  onChanged,
}: {
  user: Row;
  onClose: () => void;
  onChanged: (user: Row) => void;
}) {
  const { user: me, notify } = useWorkspace();
  const self = user.id === me.id;
  const [busy, setBusy] = useState<Pending>();
  const [error, setError] = useState('');
  const [confirm, setConfirm] = useState<'reset' | 'deactivate' | 'mfa'>();
  const [temporary, setTemporary] = useState<string | null>();
  const [copied, setCopied] = useState(false);
  const keys = useRef<Record<Pending, string>>({
    edit: crypto.randomUUID(),
    reset: crypto.randomUUID(),
    deactivate: crypto.randomUUID(),
    reactivate: crypto.randomUUID(),
    mfa: crypto.randomUUID(),
  });

  async function run(kind: Pending, url: string, body: unknown, success: string) {
    setBusy(kind);
    setError('');
    try {
      const result = await request<Row & { temporaryPassword?: string | null }>(
        url,
        body,
        keys.current[kind],
      );
      keys.current[kind] = crypto.randomUUID();
      onChanged(result);
      if (kind === 'reset') setTemporary(result.temporaryPassword ?? null);
      else notify(success);
      setConfirm(undefined);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(undefined);
    }
  }

  function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const values = Object.fromEntries(new FormData(e.currentTarget).entries());
    void run('edit', `/admin/users/${user.id}`, values, 'User updated successfully.');
  }

  async function copy() {
    if (!temporary) return;
    try {
      await navigator.clipboard.writeText(temporary);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <Overlay
      title={text(user.name)}
      subtitle={`${text(user.email)}${self ? ' · this is you' : ''}`}
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <div className="adm-dialog-body">
        <dl>
          <dt>Status</dt>
          <dd>
            <UserStatus user={user} />
          </dd>
          <dt>Last sign-in</dt>
          <dd>{user.last_login_at ? dateTime(user.last_login_at) : 'Never'}</dd>
          <dt>Two-step sign-in</dt>
          <dd>{user.mfa_enabled ? `On since ${dateTime(user.mfa_enabled_at)}` : 'Off'}</dd>
          <dt>Password last set</dt>
          <dd>{dateTime(user.password_changed_at)}</dd>
          <dt>Account created</dt>
          <dd>{dateTime(user.created_at)}</dd>
          {!user.active && (
            <>
              <dt>Deactivated</dt>
              <dd>{dateTime(user.deactivated_at)}</dd>
            </>
          )}
        </dl>

        <form onSubmit={save}>
          <h3>Profile and role</h3>
          <div className="adm-form-grid" style={{ marginTop: 12 }}>
            <label className="field">
              <span>Full name</span>
              <input name="name" required maxLength={160} defaultValue={text(user.name)} />
            </label>
            <label className="field">
              <span>Role</span>
              <select name="role" required defaultValue={text(user.role)}>
                {ROLES.map((r) => (
                  <option key={r} value={r}>
                    {ROLE_LABELS[r]}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <p className="adm-muted" style={{ marginTop: 10 }}>
            A role change applies to the user’s next request; they stay signed in. The last active
            administrator cannot be demoted.
          </p>
          <div className="adm-buttons" style={{ marginTop: 12 }}>
            <button className="button secondary small" disabled={!!busy}>
              {busy === 'edit' && <LoaderCircle className="spin" size={13} />}
              Save changes
            </button>
          </div>
        </form>

        {temporary !== undefined && (
          <div className="adm-confirm" role="status">
            {temporary ? (
              <>
                <div className="adm-note warning">
                  <ShieldAlert size={16} />
                  <span>
                    <strong>Shown once.</strong> Give this temporary password to {text(user.name)} through
                    your approved secure channel. They must replace it at the next sign-in; their open
                    sessions have ended.
                  </span>
                </div>
                <div className="adm-secret">
                  <code aria-label="Temporary password">{temporary}</code>
                  <button className="button secondary small" onClick={copy}>
                    {copied ? <Check size={13} /> : <Copy size={13} />}
                    {copied ? 'Copied' : 'Copy'}
                  </button>
                </div>
              </>
            ) : (
              <div className="adm-note">
                <ShieldAlert size={16} />
                <span>
                  This reset was already completed earlier and its password cannot be shown again. Reset again
                  to issue a new temporary password.
                </span>
              </div>
            )}
          </div>
        )}

        {confirm === 'reset' && (
          <div className="adm-confirm">
            <p>
              Issue a new one-time password for <strong>{text(user.name)}</strong>? Their current password
              stops working and every open session signs out.
            </p>
            <div className="adm-buttons">
              <button
                className="button secondary small"
                onClick={() => setConfirm(undefined)}
                disabled={!!busy}
              >
                Cancel
              </button>
              <button
                className="button primary small"
                disabled={!!busy}
                onClick={() => run('reset', `/admin/users/${user.id}/reset-password`, {}, '')}
              >
                {busy === 'reset' && <LoaderCircle className="spin" size={13} />}
                Reset password
              </button>
            </div>
          </div>
        )}
        {confirm === 'mfa' && (
          <div className="adm-confirm">
            <p>
              Turn off two-step sign-in for <strong>{text(user.name)}</strong>? Use this when they lost their
              phone. Their recovery codes stop working and every open session signs out; an administrator must
              set it up again at the next sign-in.
            </p>
            <div className="adm-buttons">
              <button
                className="button secondary small"
                onClick={() => setConfirm(undefined)}
                disabled={!!busy}
              >
                Cancel
              </button>
              <button
                className="button primary small"
                disabled={!!busy}
                onClick={() =>
                  run('mfa', `/admin/users/${user.id}/reset-mfa`, {}, 'Two-step sign-in was reset.')
                }
              >
                {busy === 'mfa' && <LoaderCircle className="spin" size={13} />}
                Reset two-step sign-in
              </button>
            </div>
          </div>
        )}
        {confirm === 'deactivate' && (
          <div className="adm-confirm">
            <p>
              Deactivate <strong>{text(user.name)}</strong>? They can no longer sign in and their open
              sessions end on their next request. History and documents stay unchanged; you can reactivate
              them later.
            </p>
            <div className="adm-buttons">
              <button
                className="button secondary small"
                onClick={() => setConfirm(undefined)}
                disabled={!!busy}
              >
                Cancel
              </button>
              <button
                className="button primary small"
                disabled={!!busy}
                onClick={() =>
                  run(
                    'deactivate',
                    `/admin/users/${user.id}/deactivate`,
                    {},
                    'User deactivated successfully.',
                  )
                }
              >
                {busy === 'deactivate' && <LoaderCircle className="spin" size={13} />}
                Deactivate user
              </button>
            </div>
          </div>
        )}

        {error && (
          <div className="form-error" role="alert">
            {error}
          </div>
        )}

        {self ? (
          <p className="adm-muted">
            To change your own password or two-step sign-in use the profile menu. You cannot deactivate your
            own account.
          </p>
        ) : (
          <div className="adm-buttons">
            {user.active ? (
              <>
                <button
                  className="button secondary small"
                  disabled={!!busy || !!confirm}
                  onClick={() => {
                    setTemporary(undefined);
                    setConfirm('reset');
                  }}
                >
                  <KeyRound size={13} />
                  Reset password
                </button>
                {!!user.mfa_enabled && (
                  <button
                    className="button secondary small"
                    disabled={!!busy || !!confirm}
                    onClick={() => setConfirm('mfa')}
                  >
                    <ShieldOff size={13} />
                    Reset two-step sign-in
                  </button>
                )}
                <button
                  className="button danger-ghost small"
                  disabled={!!busy || !!confirm}
                  onClick={() => setConfirm('deactivate')}
                >
                  <UserX size={13} />
                  Deactivate
                </button>
              </>
            ) : (
              <button
                className="button secondary small"
                disabled={!!busy}
                onClick={() =>
                  run(
                    'reactivate',
                    `/admin/users/${user.id}/reactivate`,
                    {},
                    'User reactivated successfully.',
                  )
                }
              >
                {busy === 'reactivate' ? (
                  <LoaderCircle className="spin" size={13} />
                ) : (
                  <UserCheck size={13} />
                )}
                Reactivate
              </button>
            )}
          </div>
        )}
      </div>
    </Overlay>
  );
}
