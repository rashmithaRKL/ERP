import { useState } from 'react';
import type { FormEvent } from 'react';
import { ArrowRight, Factory, LoaderCircle } from 'lucide-react';
import { token } from '../lib/api';

/** Sign-in screen, shown by the App gate when there is no session (non-demo deployments). */
export function Login({ onSuccess }: { onSuccess: () => void }) {
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setPending(true);
    setError('');
    try {
      const values = new FormData(e.currentTarget);
      const c = await fetch('/api/auth/csrf').then((r) => r.json());
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { [c.headerName]: c.token },
        body: new URLSearchParams(values as unknown as Record<string, string>),
      });
      if (!res.ok) throw new Error((await res.json()).message);
      await token();
      onSuccess();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(false);
    }
  }
  return (
    <div className="login-page">
      <div className="login-story">
        <div className="brand">
          <span>Dex</span>Lanka
        </div>
        <span className="login-eyebrow">MANUFACTURING PRO</span>
        <div>
          <span className="eyebrow">BUILT FOR THE FACTORY FLOOR</span>
          <h1>
            Better visibility.
            <br />
            Smoother production.
          </h1>
          <p>
            From the first raw material to the final delivery.
            <br />
            Bring your entire manufacturing operation together.
          </p>
        </div>
        <span className="login-foot">Precision in every process.</span>
      </div>
      <div className="login-form">
        <Factory size={30} />
        <h2>Welcome to your workspace</h2>
        <p>Sign in to keep your factory moving.</p>
        <form onSubmit={submit}>
          <label className="field">
            <span>Work email</span>
            <input name="email" type="email" autoComplete="username" required />
          </label>
          <label className="field">
            <span>Password</span>
            <input name="password" type="password" autoComplete="current-password" required />
          </label>
          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}
          <button className="button primary" disabled={pending}>
            {pending && <LoaderCircle className="spin" size={16} />}Sign in
            <ArrowRight size={16} />
          </button>
        </form>
        <small>Contact your company administrator for account assistance.</small>
      </div>
    </div>
  );
}
