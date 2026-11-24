import { useEffect, useState } from 'react';
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { useWorkspace } from '../../app/workspace';
import { request } from '../../lib/api';
import type { Row, Value } from '../../lib/api';

/** One server page of `GET /api/inventory/...` lists (keyset pagination: pass `next` as `before`). */
export type Page = { items: Row[]; next: string };
export type Rec = Record<string, Value>;

export type LotDetail = { lot: Row; movements: Row[]; transferredTo: Row[]; inspections: Row[] };
export type AdjustmentDetail = {
  adjustment: Row;
  lines: Row[];
  ledger: Row[];
  journal: Row[];
  events: Row[];
};
export type TransferDetail = { transfer: Row; lines: Row[]; ledger: Row[]; events: Row[] };
export type CountDetail = {
  count: Row;
  hidden: boolean;
  lines: Row[];
  moved: string[];
  summary: Rec;
  events: Row[];
};
export type Valuation = { warehouses: Row[]; accounts: Rec[]; total: number };
/** `GET /inventory/settings`: the STOCK_ADJUSTMENT approval policy (threshold 0 when every adjustment needs approval). */
export type Settings = {
  approvalThreshold: number;
  updatedAt: string | null;
  updatedBy: string | null;
  /** False when the policy lets every adjustment post directly. */
  approvalRequired?: boolean;
  /** Roles besides ADMIN that approve adjustments above the threshold. */
  approverRoles?: string[];
};

/** All inventory queries share this prefix, so one invalidation refreshes every tab. */
export const INVENTORY = 'inventory';

/** `?a=1&b=x` from the defined, non-empty values. */
export function params(values: Record<string, string | number | boolean | undefined | null>) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(values))
    if (value !== undefined && value !== null && value !== '' && value !== false)
      search.set(key, String(value));
  const text = search.toString();
  return text ? `?${text}` : '';
}

/** A server-paged list; `rows` accumulates the loaded pages and `fetchNextPage()` loads the next one. */
export function usePaged(
  name: string,
  path: string,
  filters: Record<string, string | number | boolean | undefined>,
  enabled = true,
) {
  const query = useInfiniteQuery({
    queryKey: [INVENTORY, name, filters],
    queryFn: ({ pageParam }) => request<Page>(`${path}${params({ ...filters, before: pageParam })}`),
    initialPageParam: '',
    getNextPageParam: (last: Page) => last.next || undefined,
    enabled,
  });
  return { query, rows: query.data?.pages.flatMap((p) => p.items) ?? [] };
}

export function useDetail<T>(name: string, path: string | undefined) {
  return useQuery({
    queryKey: [INVENTORY, name, path],
    queryFn: () => request<T>(path!),
    enabled: !!path,
  });
}

export function useSettings() {
  return useQuery({
    queryKey: [INVENTORY, 'settings'],
    queryFn: () => request<Settings>('/inventory/settings'),
  });
}

export function useWarehouses() {
  return useQuery({
    queryKey: [INVENTORY, 'warehouses'],
    queryFn: () => request<{ items: Row[] }>('/inventory/warehouses'),
  });
}

/**
 * Runs an inventory command, then refreshes the inventory queries and the shared workspace snapshot
 * (lots and items are shown on other pages too). `run` resolves to the response, or throws.
 */
export function useInventoryCommand() {
  const qc = useQueryClient();
  const { refresh, notify } = useWorkspace();
  const [busy, setBusy] = useState(false);
  async function run<T = Row>(url: string, body: unknown = {}, key?: string, success?: string): Promise<T> {
    setBusy(true);
    try {
      const result = await request<T>(url, body, key);
      if (success) notify(success);
      return result;
    } finally {
      await Promise.all([qc.invalidateQueries({ queryKey: [INVENTORY] }), refresh()]);
      setBusy(false);
    }
  }
  async function invalidate() {
    await Promise.all([qc.invalidateQueries({ queryKey: [INVENTORY] }), refresh()]);
  }
  return { run, busy, invalidate };
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

export const REASONS = ['DAMAGE', 'EXPIRY', 'FOUND', 'COUNT_VARIANCE', 'SAMPLE', 'OTHER'] as const;
export type LineKind = 'DECREASE' | 'INCREASE' | 'FOUND';
/** Line kinds each reason allows (mirrors the API rule). */
export const KINDS_BY_REASON: Record<string, LineKind[]> = {
  DAMAGE: ['DECREASE'],
  EXPIRY: ['DECREASE'],
  SAMPLE: ['DECREASE'],
  FOUND: ['INCREASE', 'FOUND'],
  COUNT_VARIANCE: ['DECREASE', 'INCREASE', 'FOUND'],
  OTHER: ['DECREASE', 'INCREASE', 'FOUND'],
};
export const WAREHOUSE_KINDS = ['RAW', 'FINISHED', 'WIP', 'QUARANTINE', 'OTHER'] as const;
export const WAREHOUSE_KIND_LABELS: Record<string, string> = {
  RAW: 'Raw materials',
  FINISHED: 'Finished goods',
  WIP: 'Work in progress',
  QUARANTINE: 'Quarantine',
  OTHER: 'Other',
};
/** Roles that may create, post and cancel inventory documents and approve adjustments (ADMIN implied). */
export const OPERATORS = ['WAREHOUSE', 'PLANNER'];
