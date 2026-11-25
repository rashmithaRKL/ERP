import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Search } from 'lucide-react';
import { labelState, text } from '../../lib/format';

const TONES: Record<string, string> = {
  // NCR, CAPA, rework and disposition statuses
  OPEN: 'amber',
  DISPOSITIONED: 'blue',
  CLOSED: 'green',
  CANCELLED: 'gray',
  PENDING_APPROVAL: 'amber',
  AWAITING_RETURN: 'amber',
  POSTED: 'green',
  REJECTED: 'red',
  COMPLETED: 'green',
  IN_PROGRESS: 'blue',
  VERIFICATION: 'violet',
  DONE: 'green',
  // severities
  CRITICAL: 'red',
  MAJOR: 'amber',
  MINOR: 'gray',
  // lots and inspections
  RELEASED: 'green',
  QUARANTINE: 'amber',
  PENDING: 'amber',
  PASSED: 'green',
  FAILED: 'red',
  ACTIVE: 'green',
  INACTIVE: 'gray',
  OVERDUE: 'red',
};

/** Status / severity chip on the shared `.badge` tones; always readable as text. */
export function QualityBadge({ value, label }: { value: unknown; label?: string }) {
  const code = text(value);
  return (
    <span className={`badge ${TONES[code] || 'gray'}`}>
      <i />
      {label ?? labelState(code)}
    </span>
  );
}

/** `value` once it has stopped changing for 300 ms (search boxes). */
export function useDebounced(value: string, delay = 300) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timeout = setTimeout(() => setDebounced(value.trim()), delay);
    return () => clearTimeout(timeout);
  }, [value, delay]);
  return debounced;
}

/** Toolbar search input in the server-table style. */
export function QualitySearch({
  value,
  onChange,
  placeholder,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
}) {
  return (
    <div className="search-input">
      <Search size={16} />
      <input aria-label={placeholder} placeholder={placeholder} value={value} onChange={(e) => onChange(e.target.value)} />
    </div>
  );
}

/** Two-column definition list for drawer facts (wraps to one column on phones). */
export function Facts({ items }: { items: [string, ReactNode][] }) {
  const shown = items.filter(([, v]) => v !== null && v !== undefined && v !== '' && v !== false);
  if (!shown.length) return null;
  return (
    <dl className="qa-facts">
      {shown.map(([k, v]) => (
        <div key={k}>
          <dt>{k}</dt>
          <dd>{v}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Inline error box for a drawer or dialog action. */
export function ActionError({ message }: { message: string }) {
  return message ? (
    <div className="form-error" role="alert">
      {message}
    </div>
  ) : null;
}
