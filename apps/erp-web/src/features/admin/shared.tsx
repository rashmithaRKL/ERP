import type { ReactNode } from 'react';
import { LoaderCircle, RefreshCw, Search } from 'lucide-react';
import type { UseInfiniteQueryResult } from '@tanstack/react-query';
import type { Row } from '../../lib/api';
import { fmt } from '../../lib/format';

/** Loading / error / content for a query-backed administration section. */
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
      <div className="adm-state" role="status">
        <LoaderCircle className="spin" size={20} />
        <span>Loading…</span>
      </div>
    );
  if (error)
    return (
      <div className="adm-state">
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
    <div className="adm-load-more">
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
    <div className="search-input adm-search">
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

/** A user's sign-in state: inactive, waiting for a password change, or active. */
export function UserStatus({ user }: { user: Row }) {
  if (!user.active) return <Tone tone="gray">Inactive</Tone>;
  if (user.must_change_password) return <Tone tone="amber">Password change pending</Tone>;
  return <Tone tone="green">Active</Tone>;
}

/** Status chip using the shared `.badge` tones. */
export function Tone({
  tone,
  children,
}: {
  tone: 'green' | 'amber' | 'red' | 'blue' | 'gray';
  children: ReactNode;
}) {
  return (
    <span className={`badge ${tone} adm-badge`}>
      <i />
      {children}
    </span>
  );
}
