import { useQueryClient } from '@tanstack/react-query';
import { ArrowRight, KeyRound, LoaderCircle, LogOut } from 'lucide-react';
import { request, token } from '../../lib/api';
import type { User } from '../../lib/api';
import { PASSWORD_MIN } from './api';
import { PasswordFields, usePasswordChange } from './PasswordFields';
import './admin.css';

/**
 * Shown instead of the workspace while `user.mustChangePassword` is set (after an administrator reset or for a new
 * account): the API refuses everything else until the temporary password is replaced.
 */
export function ForcePasswordChange({ user, onDone }: { user: User; onDone: () => void }) {
  const qc = useQueryClient();
  const { pending, error, submit } = usePasswordChange(user.email, onDone);
  return (
    <div className="login-page">
      <div className="login-story">
        <div className="brand">
          <span>Dex</span>Lanka
        </div>
        <span className="login-eyebrow">MANUFACTURING PRO</span>
        <div>
          <span className="eyebrow">ONE MORE STEP</span>
          <h1>
            Choose your own
            <br />
            password.
          </h1>
          <p>
            Your administrator issued a temporary password.
            <br />
            Replace it to open {String(user.tenant.name ?? 'your workspace')}.
          </p>
        </div>
        <span className="login-foot">Precision in every process.</span>
      </div>
      <div className="login-form">
        <KeyRound size={30} />
        <h2>Set a new password</h2>
        <p>
          Signed in as {user.email}. Use {PASSWORD_MIN} or more characters; a short sentence works well.
        </p>
        <form onSubmit={submit}>
          <input type="hidden" name="username" autoComplete="username" value={user.email} readOnly />
          <PasswordFields currentLabel="Temporary password">
            {error && (
              <div className="form-error" role="alert">
                {error}
              </div>
            )}
          </PasswordFields>
          <button className="button primary" disabled={pending}>
            {pending && <LoaderCircle className="spin" size={16} />}
            Save and continue
            <ArrowRight size={16} />
          </button>
        </form>
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
      </div>
    </div>
  );
}
