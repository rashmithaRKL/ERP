import { LoaderCircle } from 'lucide-react';
import { Overlay } from '../../components';
import { PASSWORD_MIN } from './api';
import { PasswordFields, usePasswordChange } from './PasswordFields';
import './admin.css';

/** Profile menu → Change password. Every role can change its own password; other sessions are signed out. */
export function ChangePasswordDialog({
  email,
  onClose,
  onChanged,
}: {
  email: string;
  onClose: () => void;
  onChanged: () => void;
}) {
  const { pending, error, submit } = usePasswordChange(email, onChanged);
  return (
    <Overlay
      title="Change password"
      subtitle={`Use ${PASSWORD_MIN} or more characters. Your other signed-in devices will be signed out.`}
      onClose={() => {
        if (!pending) onClose();
      }}
    >
      <form onSubmit={submit}>
        <div className="form-fields adm-password-fields">
          <input type="hidden" name="username" autoComplete="username" value={email} readOnly />
          <PasswordFields currentLabel="Current password">
            {error && (
              <div role="alert" className="form-error">
                {error}
              </div>
            )}
          </PasswordFields>
        </div>
        <div className="dialog-footer">
          <button type="button" className="button secondary" onClick={onClose} disabled={pending}>
            Cancel
          </button>
          <button className="button primary" disabled={pending}>
            {pending && <LoaderCircle className="spin" size={16} />}
            Change password
          </button>
        </div>
      </form>
    </Overlay>
  );
}
