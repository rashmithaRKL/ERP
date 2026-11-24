import { useState } from 'react';
import type { ReactNode } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { request } from '../../lib/api';
import type { Row, Value } from '../../lib/api';
import { fmt } from '../../lib/format';
import '../sales/sales.css';

/**
 * One keyset page of a V9 list endpoint (`/manufacturing/orders`, `/quality/inspections`, `/reports/costs`,
 * `/reports/journal`): `next` is the cursor of the following page ("" on the last page).
 */
export type CursorPage = {
  items: Row[];
  next: string;
  total?: number;
  counts?: Record<string, number>;
  summary?: Record<string, Value>;
  totals?: Record<string, Value>;
  accounts?: string[];
};

type Filters = Record<string, string | number | undefined>;

/** `?a=1&b=x` from the defined, non-empty values. */
function query(values: Filters) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(values))
    if (value !== undefined && value !== '') search.set(key, String(value));
  const text = search.toString();
  return text ? `?${text}` : '';
}

/**
 * Previous/next paging over a keyset-paged endpoint. The cursor stack resets whenever `filters` change. `key` is the
 * query-key prefix (`['manufacturing', 'orders']`); `workspace.refresh()` invalidates the `manufacturing`, `quality`
 * and `reports` prefixes after every transaction.
 */
export function useCursorPage(
  key: readonly string[],
  path: string,
  filters: Filters,
  limit: number,
  enabled = true,
) {
  const signature = JSON.stringify(filters);
  const [paging, setPaging] = useState<{ signature: string; cursors: string[] }>({ signature, cursors: [] });
  const cursors = paging.signature === signature ? paging.cursors : [];
  const before = cursors[cursors.length - 1];
  const result = useQuery({
    queryKey: [...key, filters, limit, before ?? ''],
    queryFn: () => request<CursorPage>(`${path}${query({ ...filters, limit, before })}`),
    placeholderData: keepPreviousData,
    enabled,
  });
  const next = result.data?.next;
  return {
    query: result,
    rows: result.data?.items ?? [],
    /** Index (0-based) of the first row of this page in the whole list. */
    start: cursors.length * limit,
    page: cursors.length + 1,
    hasPrevious: cursors.length > 0,
    hasNext: !!next,
    previous: () => setPaging({ signature, cursors: cursors.slice(0, -1) }),
    nextPage: () => next && setPaging({ signature, cursors: [...cursors, next] }),
  };
}

/** Footer of a cursor-paged table: "Showing 1–25 of 140" with previous/next buttons. */
export function PageFooter({
  paged,
  label = 'records',
}: {
  paged: ReturnType<typeof useCursorPage>;
  label?: string;
}) {
  const { rows, start, page, hasPrevious, hasNext, previous, nextPage, query: result } = paged;
  const total = result.data?.total;
  return (
    <div className="table-footer">
      <span>
        Showing {rows.length ? start + 1 : 0}–{start + rows.length}
        {total !== undefined ? ` of ${fmt(total)}` : ''} {label}
      </span>
      <div>
        <button
          className="icon-button"
          aria-label="Previous page"
          disabled={!hasPrevious || result.isFetching}
          onClick={previous}
        >
          <ChevronLeft size={16} />
        </button>
        <span>{page}</span>
        <button
          className="icon-button"
          aria-label="Next page"
          disabled={!hasNext || result.isFetching}
          onClick={nextPage}
        >
          <ChevronRight size={16} />
        </button>
      </div>
    </div>
  );
}

/** Loading skeleton / error with retry / content, in the server-table style. */
export function PageState({
  paged,
  children,
}: {
  paged: ReturnType<typeof useCursorPage>;
  children: ReactNode;
}) {
  const result = paged.query;
  if (result.isPending)
    return (
      <div className="server-loading">
        <div className="skeleton" />
      </div>
    );
  if (result.isError)
    return (
      <div className="form-error server-error" role="alert">
        {(result.error as Error).message}
        <div>
          <button className="button secondary small" onClick={() => result.refetch()}>
            Retry
          </button>
        </div>
      </div>
    );
  return <>{children}</>;
}
