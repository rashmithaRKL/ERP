import { useState } from 'react';
import { keepPreviousData, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { useWorkspace } from '../../app/workspace';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';

/** `GET /api/purchases/orders`: one keyset page, newest first. `next` is the cursor for the following page. */
export type PurchaseOrderPage = { items: Row[]; next: string; total: number; counts: Record<string, number> };

/** `GET /api/purchases/orders/{id}`. Quantities on lines/receipts are in the item's base unit unless noted. */
export type PurchaseOrderDetail = {
  order: Row;
  lines: Row[];
  /** One row per received line and lot, with its QC decision and what can still be invoiced/returned. */
  receipts: Row[];
  invoices: Row[];
  /** Lines of every invoice of the order (`invoice_id` groups them). */
  invoiceLines: Row[];
  returns: Row[];
  activity: Row[];
  /** Active raw-material warehouses (goods are received only there). */
  warehouses: Row[];
};

/** `GET /api/purchases/receipts|invoices|returns`. */
export type DocumentPage = { items: Row[]; next: string };

/** One unit a document quantity can be entered in (`GET /api/items/{id}/units`). */
export type ItemUnit = {
  code: string;
  name: string;
  decimals: number;
  factor: number | string;
  source: 'BASE' | 'ITEM' | 'STANDARD';
  purchase: boolean;
  sales: boolean;
};
export type ItemUnits = { itemId: string; baseUnit: string; baseDecimals: number; units: ItemUnit[] };

export type PurchaseListParams = { status: string; q: string; before?: string; limit: number };

/** Every purchasing query starts with `['purchasing']`, so one invalidation refreshes lists and open drawers. */
export const purchasingKeys = {
  all: ['purchasing'] as const,
  list: (p: PurchaseListParams) => ['purchasing', 'orders', p] as const,
  detail: (id: string) => ['purchasing', 'order', id] as const,
  documents: (kind: string, p: PurchaseListParams) => ['purchasing', kind, p] as const,
  sources: (items: string[]) => ['purchasing', 'sources', items] as const,
};

/** Display label of a purchase-order status (`SUBMITTED` reads as "awaiting approval"). */
export const statusLabel = (status: unknown) =>
  status === 'SUBMITTED'
    ? 'awaiting approval'
    : status === 'PARTIAL'
      ? 'partially received'
      : String(status ?? '')
          .toLowerCase()
          .replaceAll('_', ' ');

function params(p: PurchaseListParams) {
  const search = new URLSearchParams({ status: p.status, limit: String(p.limit) });
  if (p.q) search.set('q', p.q);
  if (p.before) search.set('before', p.before);
  return search;
}

export function usePurchaseOrders(p: PurchaseListParams) {
  return useQuery({
    queryKey: purchasingKeys.list(p),
    queryFn: () => request<PurchaseOrderPage>(`/purchases/orders?${params(p)}`),
    placeholderData: keepPreviousData,
  });
}

export function usePurchaseOrder(id: string) {
  return useQuery({
    queryKey: purchasingKeys.detail(id),
    queryFn: () => request<PurchaseOrderDetail>(`/purchases/orders/${id}`),
  });
}

/** Paged goods receipts, supplier invoices or supplier returns. */
export function usePurchaseDocuments(kind: 'receipts' | 'invoices' | 'returns', p: PurchaseListParams) {
  return useQuery({
    queryKey: purchasingKeys.documents(kind, p),
    queryFn: () => request<DocumentPage>(`/purchases/${kind}?${params(p)}`),
    placeholderData: keepPreviousData,
  });
}

/** Preferred supplier and last purchase price per item (`GET /api/purchases/sources`). */
export function usePurchaseSources(items: string[]) {
  return useQuery({
    queryKey: purchasingKeys.sources(items),
    queryFn: () => request<{ items: Row[] }>(`/purchases/sources?items=${items.join(',')}`),
    enabled: items.length > 0,
  });
}

/** Purchase units of several items (shares the `['items', 'units', id]` cache with `UnitQuantityInput`). */
export function useItemUnits(itemIds: string[]) {
  const ids = [...new Set(itemIds.filter(Boolean))];
  const results = useQueries({
    queries: ids.map((id) => ({
      queryKey: ['items', 'units', id],
      queryFn: () => request<ItemUnits>(`/items/${id}/units`),
      staleTime: 60_000,
    })),
  });
  const byItem = new Map<string, ItemUnits>();
  results.forEach((r, i) => {
    if (r.data) byItem.set(ids[i], r.data);
  });
  return byItem;
}

/** Units an item may be purchased in: its base unit, conversions flagged for purchase and same-kind standard units. */
export const purchaseUnits = (units?: ItemUnits) =>
  (units?.units || []).filter((u) => u.source !== 'ITEM' || u.purchase);

/** Units an item may be sold in: its base unit, conversions flagged for sales and same-kind standard units (V9). */
export const salesUnits = (units?: ItemUnits) =>
  (units?.units || []).filter((u) => u.source !== 'ITEM' || u.sales);

/** Base units in one `unit` of the item (1 for the base unit or while the units load). */
export const unitFactor = (units: ItemUnits | undefined, unit: string) =>
  Number(units?.units.find((u) => u.code === unit)?.factor ?? 1) || 1;

/** Refresh purchasing documents, partner figures, MRP and item stock after a procurement transaction. */
export const invalidatePurchasing = (qc: QueryClient) =>
  Promise.all([
    qc.invalidateQueries({ queryKey: purchasingKeys.all }),
    qc.invalidateQueries({ queryKey: ['partners'] }),
    qc.invalidateQueries({ queryKey: ['inventory'] }),
  ]);

/** Posts a procurement command from a button (submit, approve, …), refreshes data and toasts the outcome. */
export function usePurchaseCommand() {
  const qc = useQueryClient();
  const { refresh, notify } = useWorkspace();
  const [pending, setPending] = useState('');
  const [error, setError] = useState('');
  async function run(action: string, url: string, body: unknown = {}) {
    if (pending) return;
    setPending(action);
    setError('');
    try {
      await request(url, body);
      await Promise.all([invalidatePurchasing(qc), refresh()]);
      notify('Transaction posted successfully.');
    } catch (e) {
      setError((e as Error).message);
      notify((e as Error).message);
    } finally {
      setPending('');
    }
  }
  return { run, pending, error };
}
