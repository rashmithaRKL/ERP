import { useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { useWorkspace } from '../../app/workspace';
import { request } from '../../lib/api';
import type { Row, Value } from '../../lib/api';

/** `GET /api/sales/orders`: one keyset page, newest first. `next` is the cursor for the following page. */
export type SalesOrderPage = { items: Row[]; next: string; total: number; counts: Record<string, number> };

/** `GET /api/sales/orders/{id}`. */
export type SalesOrderDetail = {
  order: Row;
  lines: Row[];
  deliveries: Row[];
  returns: Row[];
  manufacturing: Row[];
  /** Released, unreserved lots of the products on open lines (FIFO order). */
  lots: Row[];
  activity: Row[];
  credit: { limit: Value; receivables: Value; exceeded: boolean };
};

export type SalesListParams = { status: string; q: string; before?: string; limit: number };

/** Every sales query starts with `['sales']`, so one invalidation refreshes lists and open drawers. */
export const salesKeys = {
  all: ['sales'] as const,
  list: (p: SalesListParams) => ['sales', 'orders', p] as const,
  detail: (id: string) => ['sales', 'order', id] as const,
};

export function useSalesOrders(params: SalesListParams) {
  const search = new URLSearchParams({ status: params.status, limit: String(params.limit) });
  if (params.q) search.set('q', params.q);
  if (params.before) search.set('before', params.before);
  return useQuery({
    queryKey: salesKeys.list(params),
    queryFn: () => request<SalesOrderPage>(`/sales/orders?${search}`),
    placeholderData: keepPreviousData,
  });
}

export function useSalesOrder(id: string) {
  return useQuery({
    queryKey: salesKeys.detail(id),
    queryFn: () => request<SalesOrderDetail>(`/sales/orders/${id}`),
  });
}

/** Refresh sales documents and partner figures (receivables, open documents) after a sales transaction. */
export const invalidateSales = (qc: QueryClient) =>
  Promise.all([
    qc.invalidateQueries({ queryKey: salesKeys.all }),
    qc.invalidateQueries({ queryKey: ['partners'] }),
  ]);

/**
 * Posts a sales command from a button (confirm, …): refreshes sales, partner and workspace data and shows the
 * outcome as a toast. Server warnings (for example an exceeded credit limit) are shown instead of the success text.
 */
export function useSalesCommand() {
  const qc = useQueryClient();
  const { refresh, notify } = useWorkspace();
  const [pending, setPending] = useState('');
  const [error, setError] = useState('');
  async function run(action: string, url: string, body: unknown = {}) {
    if (pending) return;
    setPending(action);
    setError('');
    try {
      const result = await request<{ warnings?: string[] }>(url, body);
      await Promise.all([invalidateSales(qc), refresh()]);
      notify(result?.warnings?.length ? result.warnings.join(' ') : 'Transaction posted successfully.');
    } catch (e) {
      setError((e as Error).message);
      notify((e as Error).message);
    } finally {
      setPending('');
    }
  }
  return { run, pending, error };
}
