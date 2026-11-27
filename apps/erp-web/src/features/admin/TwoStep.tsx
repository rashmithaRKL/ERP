import { useEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Check, Copy, LoaderCircle, LogOut, ShieldAlert, ShieldCheck } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay } from '../../components';
import { request, token } from '../../lib/api';
import type { User } from '../../lib/api';
import { dateTime, PASSWORD_MAX } from './api';
import './admin.css';

/**
 * V17 two-step sign-in screens: the code step of a password sign-in, the set-up an administrator must finish before
 * using the workspace, and the profile dialog to set it up, replace recovery codes or turn it off.
 */

type Setup = { secret: string; uri: string; issuer: string; account: string };

function CodeField({ recovery = false }: { recovery?: boolean }) {
  return (
    <label className="field">
      <span>{recovery ? 'Recovery code' : 'Code from your authenticator app'}</span>
      <input
        name="code"
        required
        autoFocus
        maxLength={recovery ? 16 : 6}
        inputMode={recovery ? 'text' : 'numeric'}
        pattern={recovery ? undefined : '[0-9]{6}'}
        autoComplete="one-time-code"
        placeholder={recovery ? 'abcde-fghij' : '123456'}
      />
    </label>
  );
}

function PasswordField() {
  return (
    <label className="field">
      <span>Current password</span>
      <input
        name="currentPassword"
        type="password"
        autoComplete="current-password"
        required
        maxLength={PASSWORD_MAX}
      />
    </label>
  );
}

function ErrorLine({ error }: { error: string }) {
  return error ? (
    <div className="form-error" role="alert">
      {error}
    </div>
  ) : null;
}

/** Posts the form's fields once per idempotency key; a retry after a network error reuses the key. */
function useCommand<T>(url: string, onDone: (result: T) => void) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const key = useRef(crypto.randomUUID());
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const values = Object.fromEntries(new FormData(e.currentTarget).entries());
    setPending(true);
    setError('');
    try {
      const result = await request<T>(url, values, key.current);
      key.current = crypto.randomUUID();
      await token();
      onDone(result);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }
  return { pending, error, submit };
}

/** The ten one-time codes, shown once after set-up or replacement. */
export function RecoveryCodes({ codes, children }: { codes: string[] | null; children?: ReactNode }) {
  const [copied, setCopied] = useState(false);
  if (!codes)
    return (
      <div className="adm-note">
        <ShieldAlert size={16} />
        <span>These codes were already shown and cannot be shown again. Replace them to get new ones.</span>
      </div>
    );
  return (
    <div className="mfa-codes" role="status">
      <div className="adm-note warning">
        <ShieldAlert size={16} />
        <span>
          <strong>Shown once.</strong> Keep these recovery codes somewhere safe, away from your phone. Each
          one signs you in once if you lose your phone.
        </span>
      </div>
      <ol aria-label="Recovery codes">
        {codes.map((c) => (
          <li key={c}>
            <code>{c}</code>
          </li>
        ))}
      </ol>
      <div className="adm-buttons">
        <button
          type="button"
          className="button secondary small"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(codes.join('\n'));
              setCopied(true);
            } catch {
              setCopied(false);
            }
          }}
        >
          {copied ? <Check size={13} /> : <Copy size={13} />}
          {copied ? 'Copied' : 'Copy codes'}
        </button>
        {children}
      </div>
    </div>
  );
}

/** Set-up: a new secret for the authenticator app, confirmed with the current password and a code. */
export function SetupForm({ onDone, onCancel }: { onDone: () => void; onCancel?: () => void }) {
  const [setup, setSetup] = useState<Setup>();
  const [loadError, setLoadError] = useState('');
  const [codes, setCodes] = useState<string[] | null>();
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    request<Setup>('/auth/mfa/setup', {})
      .then(setSetup)
      .catch((e) => setLoadError((e as Error).message));
  }, []);
  const { pending, error, submit } = useCommand<{ recoveryCodes: string[] | null }>('/auth/mfa/enable', (r) =>
    setCodes(r.recoveryCodes),
  );
  if (codes !== undefined)
    return (
      <RecoveryCodes codes={codes}>
        <button type="button" className="button primary small" onClick={onDone}>
          I saved my codes
          <ArrowRight size={13} />
        </button>
      </RecoveryCodes>
    );
  if (loadError) return <ErrorLine error={loadError} />;
  if (!setup) return <LoaderCircle className="spin" />;
  return (
    <form onSubmit={submit} className="mfa-setup">
      <ol className="mfa-steps">
        <li>
          Open an authenticator app on your phone (for example Google Authenticator, Microsoft Authenticator
          or 1Password) and add an account with this setup key:
          <div className="adm-secret">
            <code aria-label="Setup key">{setup.secret.match(/.{1,4}/g)?.join(' ')}</code>
          </div>
          <small className="adm-muted">
            Account: {setup.account} · time-based · 6 digits.{' '}
            <a href={setup.uri}>Open in an authenticator app on this device</a>
          </small>
        </li>
        <li>Enter the 6-digit code the app shows, and your password.</li>
      </ol>
      <div className="form-fields">
        <CodeField />
        <PasswordField />
        <ErrorLine error={error} />
      </div>
      <div className="dialog-footer">
        {onCancel && (
          <button type="button" className="button secondary" onClick={onCancel} disabled={pending}>
            Cancel
          </button>
        )}
        <button className="button primary" disabled={pending}>
          {pending && <LoaderCircle className="spin" size={16} />}
          Turn on two-step sign-in
        </button>
      </div>
    </form>
  );
}

function SignOutButton({ user }: { user: User }) {
  const qc = useQueryClient();
  return (
    <button
      className="text-button"
      onClick={async () => {
        await request('/auth/logout', {});
        await token();
        qc.clear();
        window.location.reload();
      }}
    >
      <LogOut size={13} />
      {user.tenant.demo ? 'Restart demo session' : 'Sign out'}
    </button>
  );
}

function FullPage({
  eyebrow,
  title,
  lead,
  children,
}: {
  eyebrow: string;
  title: ReactNode;
  lead: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="login-page">
      <div className="login-story">
        <div className="brand">
          <span>Dex</span>Lanka
        </div>
        <span className="login-eyebrow">MANUFACTURING PRO</span>
        <div>
          <span className="eyebrow">{eyebrow}</span>
          <h1>{title}</h1>
          <p>{lead}</p>
        </div>
        <span className="login-foot">Precision in every process.</span>
      </div>
      <div className="login-form">{children}</div>
    </div>
  );
}

/** Shown while `user.mfaPending`: the password was right, the code is still due. */
export function MfaChallenge({ user, onDone }: { user: User; onDone: () => void }) {
  const [recovery, setRecovery] = useState(false);
  const { pending, error, submit } = useCommand('/auth/mfa/verify', onDone);
  return (
    <FullPage
      eyebrow="TWO-STEP SIGN-IN"
      title={
        <>
          One more
          <br />
          step.
        </>
      }
      lead="Your account is protected with an authenticator app."
    >
      <ShieldCheck size={30} />
      <h2>Enter your code</h2>
      <p>
        Signed in as {user.email}.{' '}
        {recovery ? 'Each recovery code works once.' : 'Open your authenticator app for the current code.'}
      </p>
      <form onSubmit={submit} key={recovery ? 'recovery' : 'app'}>
        <CodeField recovery={recovery} />
        <ErrorLine error={error} />
        <button className="button primary" disabled={pending}>
          {pending && <LoaderCircle className="spin" size={16} />}
          Verify and continue
          <ArrowRight size={16} />
        </button>
      </form>
      <button className="text-button" onClick={() => setRecovery((x) => !x)}>
        {recovery ? 'Use the authenticator app instead' : 'Lost your phone? Use a recovery code'}
      </button>
      <SignOutButton user={user} />
    </FullPage>
  );
}

/** Shown while `user.mfaSetupRequired`: administrators must turn on two-step sign-in before working. */
export function MfaSetupRequired({ user, onDone }: { user: User; onDone: () => void }) {
  return (
    <FullPage
      eyebrow="PROTECT YOUR ACCOUNT"
      title={
        <>
          Set up two-step
          <br />
          sign-in.
        </>
      }
      lead={`Administrators of ${String(user.tenant.name ?? 'this workspace')} sign in with a password and a code from their phone.`}
    >
      <ShieldCheck size={30} />
      <h2>Two-step sign-in</h2>
      <p>Signed in as {user.email}. This takes about a minute.</p>
      <SetupForm onDone={onDone} />
      <SignOutButton user={user} />
    </FullPage>
  );
}

type Mode = 'setup' | 'codes' | 'disable';

/** Profile menu → Two-step sign-in. */
export function TwoStepDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const { user, notify } = useWorkspace();
  const [mode, setMode] = useState<Mode>();
  const [codes, setCodes] = useState<string[] | null>();
  const refresh = () => qc.invalidateQueries({ queryKey: ['user'] });
  const regenerate = useCommand<{ recoveryCodes: string[] | null }>('/auth/mfa/recovery-codes', (r) => {
    setCodes(r.recoveryCodes);
    setMode(undefined);
    void refresh();
  });
  const disable = useCommand('/auth/mfa/disable', () => {
    notify('Two-step sign-in was turned off. Your other sessions were signed out.');
    void refresh();
    onClose();
  });
  const confirm = mode === 'codes' ? regenerate : disable;
  return (
    <Overlay
      title="Two-step sign-in"
      subtitle={
        user.mfaEnabled
          ? `On since ${dateTime(user.mfaEnabledAt)} · ${user.recoveryCodesLeft ?? 0} recovery codes left`
          : 'Sign in with your password and a code from an authenticator app on your phone.'
      }
      onClose={onClose}
    >
      <div className="adm-dialog-body">
        {mode === 'setup' ? (
          <SetupForm
            onCancel={() => setMode(undefined)}
            onDone={() => {
              notify('Two-step sign-in is on. Your other sessions were signed out.');
              void refresh();
              onClose();
            }}
          />
        ) : codes !== undefined ? (
          <RecoveryCodes codes={codes}>
            <button type="button" className="button primary small" onClick={() => setCodes(undefined)}>
              Done
            </button>
          </RecoveryCodes>
        ) : mode ? (
          <form onSubmit={confirm.submit}>
            <p>
              {mode === 'codes'
                ? 'Your unused recovery codes stop working and ten new ones are shown once.'
                : 'Your account will be protected by your password only, and your other sessions sign out.'}
            </p>
            <div className="form-fields">
              <PasswordField />
              <CodeField />
              <ErrorLine error={confirm.error} />
            </div>
            <div className="dialog-footer">
              <button
                type="button"
                className="button secondary"
                onClick={() => setMode(undefined)}
                disabled={confirm.pending}
              >
                Cancel
              </button>
              <button className="button primary" disabled={confirm.pending}>
                {confirm.pending && <LoaderCircle className="spin" size={16} />}
                {mode === 'codes' ? 'Replace recovery codes' : 'Turn off'}
              </button>
            </div>
          </form>
        ) : user.mfaEnabled ? (
          <>
            {user.mfaRequired && (
              <p className="adm-muted">
                Administrators must keep two-step sign-in on. If you change phones, ask another administrator
                to reset it, then set it up again.
              </p>
            )}
            <div className="adm-buttons">
              <button className="button secondary small" onClick={() => setMode('codes')}>
                Replace recovery codes
              </button>
              {!user.mfaRequired && (
                <button className="button danger-ghost small" onClick={() => setMode('disable')}>
                  Turn off
                </button>
              )}
            </div>
          </>
        ) : (
          <div className="adm-buttons">
            <button className="button primary small" onClick={() => setMode('setup')}>
              <ShieldCheck size={13} />
              Set up two-step sign-in
            </button>
          </div>
        )}
      </div>
    </Overlay>
  );
}
