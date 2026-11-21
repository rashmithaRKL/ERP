import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { LoaderCircle } from 'lucide-react';
import { Overlay } from './Overlay';

export type Field = {
  name: string;
  label: string;
  type?: string;
  options?: { value: string; label: string }[];
  defaultValue?: string;
  min?: string;
  step?: string;
  hint?: string;
  optional?: boolean;
};
export type FormSpec = {
  title: string;
  description?: string;
  fields: Field[];
  submit?: string;
  run: (values: Record<string, string>, key: string) => Promise<unknown>;
};
/**
 * Generic transaction form. `spec.run` receives the submitted values and a stable idempotency key
 * (one per dialog instance, so retries after an error reuse the same key).
 */
export function FormDialog({
  spec,
  onClose,
  onSuccess,
}: {
  spec: FormSpec;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const requestKey = useRef(crypto.randomUUID());
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const values = Object.fromEntries(new FormData(e.currentTarget).entries()) as Record<string, string>;
    setPending(true);
    setError('');
    try {
      await spec.run(values, requestKey.current);
      onSuccess();
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }
  return (
    <Overlay
      title={spec.title}
      subtitle={spec.description}
      onClose={() => {
        if (!pending) onClose();
      }}
    >
      <form onSubmit={submit}>
        <div className="form-fields">
          {spec.fields.map((f) => (
            <label className="field" key={f.name}>
              <span>
                {f.label}
                {f.optional && <small>Optional</small>}
              </span>
              {f.options ? (
                <select name={f.name} required={!f.optional} defaultValue={f.defaultValue || ''}>
                  <option value="" disabled={!f.optional}>
                    Select {f.label.toLowerCase()}
                  </option>
                  {f.options.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              ) : f.type === 'textarea' ? (
                <textarea
                  name={f.name}
                  rows={3}
                  required={!f.optional}
                  maxLength={2000}
                  defaultValue={f.defaultValue}
                />
              ) : (
                <input
                  name={f.name}
                  type={f.type || 'text'}
                  required={!f.optional}
                  defaultValue={f.defaultValue}
                  min={f.min}
                  step={f.step || (f.type === 'number' ? '0.000001' : undefined)}
                  maxLength={f.type === 'text' || !f.type ? 160 : undefined}
                />
              )}
              {f.hint && <em>{f.hint}</em>}
            </label>
          ))}
          {error && (
            <div role="alert" className="form-error">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <button type="button" className="button secondary" onClick={onClose} disabled={pending}>
            Cancel
          </button>
          <button className="button primary" disabled={pending}>
            {pending && <LoaderCircle className="spin" size={16} />}
            {spec.submit || 'Save changes'}
          </button>
        </div>
      </form>
    </Overlay>
  );
}
