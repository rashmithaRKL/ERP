import { useEffect, useState } from 'react';
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { request, token } from '../../lib/api';
import type { Row, Value } from '../../lib/api';
import { today } from '../../lib/format';

/** All administration queries share this prefix, so one invalidation refreshes every tab. */
export const ADMIN = 'admin';

/** One server page of a keyset-paged list: pass `next` as `before` for the following page. */
export type Page = { items: Row[]; next: string; summary?: Record<string, Value> };

export const ROLES = [
  'ADMIN',
  'PLANNER',
  'WAREHOUSE',
  'OPERATOR',
  'QUALITY',
  'SALES',
  'VIEWER',
  'MAINTENANCE',
  'FINANCE',
] as const;
/** Roles that can be named approvers in an approval policy (ADMIN always approves). */
export const APPROVER_ROLES = ['PLANNER', 'WAREHOUSE', 'QUALITY', 'SALES', 'OPERATOR'] as const;
export const ROLE_LABELS: Record<string, string> = {
  ADMIN: 'Administrator',
  PLANNER: 'Planner',
  WAREHOUSE: 'Warehouse',
  OPERATOR: 'Operator',
  QUALITY: 'Quality',
  SALES: 'Sales',
  VIEWER: 'Viewer',
  MAINTENANCE: 'Maintenance',
  FINANCE: 'Finance',
};
export const PASSWORD_MIN = 16;
export const PASSWORD_MAX = 128;

/** `?a=1&b=x` from the defined, non-empty values. */
export function params(values: Record<string, string | number | boolean | undefined | null>) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(values))
    if (value !== undefined && value !== null && value !== '' && value !== false)
      search.set(key, String(value));
  const text = search.toString();
  return text ? `?${text}` : '';
}

/** A server-paged administration list; `rows` accumulates the loaded pages. */
export function useAdminPaged(
  name: string,
  path: string,
  filters: Record<string, string | number | boolean | undefined>,
  enabled = true,
) {
  const query = useInfiniteQuery({
    queryKey: [ADMIN, name, filters],
    queryFn: ({ pageParam }) => request<Page>(`${path}${params({ ...filters, before: pageParam })}`),
    initialPageParam: '',
    getNextPageParam: (last: Page) => last.next || undefined,
    enabled,
  });
  return {
    query,
    rows: query.data?.pages.flatMap((p) => p.items) ?? [],
    summary: query.data?.pages[0]?.summary,
  };
}

export function useInvalidateAdmin() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: [ADMIN] });
}

/** `value` after it has stopped changing for `delay` ms (search boxes). */
export function useDebounced<T>(value: T, delay = 300) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timeout = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timeout);
  }, [value, delay]);
  return debounced;
}

/**
 * Downloads a server-generated file (`GET /api{url}`) through the session cookie. Returns the response headers so a
 * caller can read e.g. `X-Export-Truncated`. Throws with the server's message on failure.
 */
export async function download(url: string, fallbackName: string): Promise<Headers> {
  const res = await fetch(`/api${url}`, { credentials: 'same-origin' });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message || err.detail || `Download failed (${res.status})`);
  }
  const blob = await res.blob();
  const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') || '')?.[1] || fallbackName;
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(href), 1000);
  return res.headers;
}

/** Re-reads the CSRF token after the session id changed (password change rotates the session). */
export const refreshToken = () => token();

/** `24 Nov 2026, 14:05` in the browser's locale, or an em dash. */
export const dateTime = (x: unknown) =>
  x
    ? new Date(String(x)).toLocaleString('en-GB', {
        day: 'numeric',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      })
    : '—';

/** `24 Nov 2026` for a `yyyy-MM-dd` value. */
export const longDate = (x: unknown) =>
  x
    ? new Date(String(x).substring(0, 10) + 'T12:00:00').toLocaleDateString('en-GB', {
        day: 'numeric',
        month: 'short',
        year: 'numeric',
      })
    : '—';

/** The last day of the month before `todayIso` (yyyy-MM-dd), the usual period to close. */
export function previousMonthEnd(todayIso = today()) {
  const [y, m] = todayIso.split('-').map(Number);
  const end = new Date(Date.UTC(y, m - 1, 0));
  return end.toISOString().substring(0, 10);
}
