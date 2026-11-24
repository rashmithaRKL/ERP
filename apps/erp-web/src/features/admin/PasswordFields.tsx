import { useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { request } from '../../lib/api';
import { PASSWORD_MAX, PASSWORD_MIN, refreshToken } from './api';

/**
 * Current / new / confirm password fields and the `POST /auth/password` call, shared by the profile dialog and the
 * forced change screen. `render` lays out the fields, the error and the pending state.
 */
export function usePasswordChange(email: string, onChanged: () => void) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const key = useRef(crypto.randomUUID());
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const values = Object.fromEntries(new FormData(e.currentTarget).entries()) as Record<string, string>;
    setError('');
    if (values.newPassword.length < PASSWORD_MIN)
      return setError(`Use at least ${PASSWORD_MIN} characters. A short sentence is easy to remember.`);
    if (values.newPassword !== values.confirmPassword) return setError('The new passwords do not match.');
    if (values.newPassword.trim().toLowerCase() === email.toLowerCase())
      return setError('Your password cannot be your email address.');
    setPending(true);
    try {
      await request(
        '/auth/password',
        { currentPassword: values.currentPassword, newPassword: values.newPassword },
        key.current,
      );
      key.current = crypto.randomUUID();
      await refreshToken();
      onChanged();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }
  return { pending, error, submit };
}

export function PasswordFields({ currentLabel, children }: { currentLabel: string; children?: ReactNode }) {
  return (
    <>
      <label className="field">
        <span>{currentLabel}</span>
        <input
          name="currentPassword"
          type="password"
          autoComplete="current-password"
          required
          maxLength={PASSWORD_MAX}
        />
      </label>
      <label className="field">
        <span>
          New password<small>{PASSWORD_MIN}+ characters</small>
        </span>
        <input
          name="newPassword"
          type="password"
          autoComplete="new-password"
          required
          minLength={PASSWORD_MIN}
          maxLength={PASSWORD_MAX}
        />
      </label>
      <label className="field">
        <span>Confirm new password</span>
        <input
          name="confirmPassword"
          type="password"
          autoComplete="new-password"
          required
          minLength={PASSWORD_MIN}
          maxLength={PASSWORD_MAX}
        />
      </label>
      {children}
    </>
  );
}
