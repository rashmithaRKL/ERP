import type { ReactNode } from 'react';
import { LoaderCircle, RefreshCw, Search } from 'lucide-react';
import type { UseInfiniteQueryResult } from '@tanstack/react-query';
import type { Row } from '../../lib/api';
import { date, fmt, labelState, num, text } from '../../lib/format';
import { auditLabel } from '../audit/auditLabel';

const TONES: Record<string, string> = {
  DRAFT: 'gray',
  APPROVAL_REQUIRED: 'amber',
  POSTED: 'green',
  CANCELLED: 'red',
  COUNTING: 'blue',
  REVIEW: 'amber',
  RELEASED: 'green',
  QUARANTINE: 'amber',
  REJECTED: 'red',
  ACTIVE: 'green',
  INACTIVE: 'gray',
  PASSED: 'green',
  FAILED: 'red',
  PENDING: 'amber',
};

/** Inventory document/lot status chip, using the shared `.badge` tones. */
export function StatusBadge({ value }: { value: unknown }) {
  const code = text(value);
  return (
    <span className={`badge ${TONES[code] || 'gray'}`}>
      <i />
      {labelState(code)}
    </span>
  );
}

/** Quantity with its base unit, e.g. `1,250.5 kg`. */
export const qty = (value: unknown, uom: unknown) => `${fmt(value, 3)} ${text(uom)}`;

/** Signed quantity with colour: `+5 kg` / `−3 kg`. */
export function Delta({ value, uom }: { value: unknown; uom?: unknown }) {
  const n = num(value);
  return (
    <span className={n > 0 ? 'positive' : n < 0 ? 'negative' : 'muted'}>
      {n > 0 ? '+' : n < 0 ? '−' : ''}
      {fmt(Math.abs(n), 3)}
      {uom ? ` ${text(uom)}` : ''}
    </span>
  );
}

/** Signed money with colour. */
export function MoneyDelta({ value }: { value: unknown }) {
  const n = num(value);
  return (
    <span className={n > 0 ? 'positive' : n < 0 ? 'negative' : 'muted'}>
      {n > 0 ? '+' : n < 0 ? '−' : ''}Rs. {fmt(Math.abs(n), 2)}
    </span>
  );
}

/** Loading / error / content for a query-backed section. */
export function QueryState({
  pending,
  error,
  retry,
  children,
}: {
  pending: boolean;
  error: Error | null;
  retry: () => void;
  children: ReactNode;
}) {
  if (pending)
    return (
      <div className="inv-state" role="status">
        <LoaderCircle className="spin" size={20} />
        <span>Loading…</span>
      </div>
    );
  if (error)
    return (
      <div className="inv-state">
        <div className="form-error" role="alert">
          {error.message}
        </div>
        <button className="button secondary small" onClick={retry}>
          <RefreshCw size={13} />
          Try again
        </button>
      </div>
    );
  return <>{children}</>;
}

/** Footer of a server-paged table: loaded count and a "Load more" button. */
export function LoadMore({ query, count }: { query: UseInfiniteQueryResult<unknown, Error>; count: number }) {
  return (
    <div className="inv-load-more">
      <span>
        {fmt(count)} loaded{query.hasNextPage ? '' : count ? ' · end of list' : ''}
      </span>
      {query.hasNextPage && (
        <button
          className="button secondary small"
          disabled={query.isFetchingNextPage}
          onClick={() => query.fetchNextPage()}
        >
          {query.isFetchingNextPage && <LoaderCircle className="spin" size={13} />}
          Load more
        </button>
      )}
    </div>
  );
}

export function SearchBox({
  value,
  onChange,
  placeholder,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
}) {
  return (
    <div className="search-input inv-search">
      <Search size={16} />
      <input
        aria-label={placeholder}
        placeholder={placeholder}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </div>
  );
}

export function ActivityFeed({ events }: { events: Row[] }) {
  if (!events.length) return <p className="muted inv-small">No activity recorded yet.</p>;
  return (
    <div className="activity-feed">
      {events.map((e) => (
        <div key={e.id}>
          <span className="activity-dot" />
          <div>
            <strong>{auditLabel(e.action)}</strong>
            <p>{text(e.detail)}</p>
            <small>
              {text(e.actor)} · {dateTime(e.created_at)}
            </small>
          </div>
        </div>
      ))}
    </div>
  );
}

/** `22 Nov, 14:05` in the browser's locale. */
export const dateTime = (x: unknown) =>
  x
    ? new Date(text(x)).toLocaleString('en-GB', {
        day: 'numeric',
        month: 'short',
        hour: '2-digit',
        minute: '2-digit',
      })
    : '—';

/** Lifecycle strip reusing the order drawer's `.lifecycle` styles. */
export function Lifecycle({ steps, index }: { steps: string[]; index: number }) {
  return (
    <div className="lifecycle">
      {steps.map((s, i) => (
        <div key={s} className={i <= index ? 'done' : ''}>
          <span>{i + 1}</span>
          <small>{s}</small>
        </div>
      ))}
    </div>
  );
}

export { date };
