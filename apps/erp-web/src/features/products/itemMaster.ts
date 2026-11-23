import { useEffect, useRef } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { useWorkspace } from '../../app/workspace';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';

/** Item type codes in display order (V4 adds `SEMI_FINISHED`). */
export const itemTypes = ['RAW_MATERIAL', 'SEMI_FINISHED', 'FINISHED_GOOD', 'PACKAGING'] as const;
export type ItemType = (typeof itemTypes)[number];

export const itemTypeLabels: Record<string, string> = {
  RAW_MATERIAL: 'Raw material',
  SEMI_FINISHED: 'Semi-finished',
  FINISHED_GOOD: 'Finished good',
  PACKAGING: 'Packaging',
};
export const itemTypePlural: Record<string, string> = {
  RAW_MATERIAL: 'Raw materials',
  SEMI_FINISHED: 'Semi-finished',
  FINISHED_GOOD: 'Finished goods',
  PACKAGING: 'Packaging',
};

export const unitKinds = ['COUNT', 'MASS', 'VOLUME', 'LENGTH', 'AREA', 'PACKAGE'] as const;

/** `GET /api/items` */
export type ItemPage = {
  items: Row[];
  total: number;
  next: string;
  facets: Record<string, number>;
};
export type ItemFilters = { q: string; type: string; category: string; active: string };

export type UnitChoice = {
  code: string;
  name: string;
  kind: string;
  decimals: number;
  factor: number | string;
  source: 'BASE' | 'ITEM' | 'STANDARD';
  purchase: boolean;
  sales: boolean;
};
export type Attribute = { name: string; values: string[] };
export type VariantRow = Row & { values?: Record<string, string> };
export type Family = {
  templateId: string;
  code: string;
  name: string;
  attributes: Attribute[];
  items: VariantRow[];
};

/** `GET /api/items/{id}` */
export type ItemDetail = {
  item: Row;
  usage: {
    movements: boolean;
    referenced: boolean;
    skuLocked: boolean;
    unitLocked: boolean;
    typeLocked: boolean;
  };
  categoryPath: Row[];
  units: UnitChoice[];
  conversions: Row[];
  barcodes: Row[];
  variants: Family | null;
  stock: { warehouses: Row[]; lots: Row[] };
  whereUsed: Row[];
  producedBy: Row[];
  movements: Row[];
  activity: Row[];
};

/** `GET /api/item-templates/{id}` */
export type FamilyDetail = { template: Row & { attributes: Attribute[] }; variants: VariantRow[] };
export type FamilyRow = Row & { attributes: Attribute[] };

export const itemKeys = {
  all: ['items'] as const,
  page: (filters: ItemFilters, cursor: string) => ['items', 'page', filters, cursor] as const,
  detail: (id: string) => ['items', 'detail', id] as const,
  movements: (id: string, cursor: string) => ['items', 'movements', id, cursor] as const,
  units: (id: string) => ['items', 'units', id] as const,
};

const query = (params: Record<string, string>) => {
  const search = new URLSearchParams(Object.entries(params).filter(([, v]) => v !== ''));
  const text = search.toString();
  return text ? `?${text}` : '';
};

export function useItemPage(filters: ItemFilters, cursor: string, limit = 25) {
  return useQuery({
    queryKey: itemKeys.page(filters, cursor),
    queryFn: () => request<ItemPage>(`/items${query({ ...filters, after: cursor, limit: String(limit) })}`),
    placeholderData: keepPreviousData,
  });
}

export function useItemDetail(id: string | undefined) {
  return useQuery({
    queryKey: itemKeys.detail(id || ''),
    queryFn: () => request<ItemDetail>(`/items/${id}`),
    enabled: !!id,
  });
}

export function useItemMovements(id: string, cursor: string) {
  return useQuery({
    queryKey: itemKeys.movements(id, cursor),
    queryFn: () =>
      request<{ items: Row[]; next: string }>(`/items/${id}/movements${query({ before: cursor })}`),
    placeholderData: keepPreviousData,
  });
}

export function useUnits() {
  return useQuery({ queryKey: ['units'], queryFn: () => request<{ items: Row[] }>('/units') });
}

export function useCategories() {
  return useQuery({
    queryKey: ['item-categories'],
    queryFn: () => request<{ items: Row[] }>('/item-categories'),
  });
}

export function useFamilies() {
  return useQuery({
    queryKey: ['item-templates'],
    queryFn: () => request<{ items: FamilyRow[] }>('/item-templates'),
  });
}

export function useFamily(id: string | undefined) {
  return useQuery({
    queryKey: ['item-templates', id],
    queryFn: () => request<FamilyDetail>(`/item-templates/${id}`),
    enabled: !!id,
  });
}

/**
 * Re-fetches the item master queries whenever the shared workspace data is refreshed (every successful form,
 * `mutate()` or `refresh()` call refreshes `['state']`), so dialogs opened from anywhere keep this page current.
 */
export function useItemMasterSync() {
  const qc = useQueryClient();
  const { stateQuery } = useWorkspace();
  const updated = stateQuery.dataUpdatedAt;
  const first = useRef(updated);
  useEffect(() => {
    if (updated === first.current) return;
    first.current = updated;
    for (const key of [['items'], ['units'], ['item-categories'], ['item-templates']])
      void qc.invalidateQueries({ queryKey: key });
  }, [qc, updated]);
}

/** Drops empty optional form values so the API receives `null` instead of an empty string. */
export const compact = (values: Record<string, string>) =>
  Object.fromEntries(Object.entries(values).filter(([, v]) => v !== ''));

/** Parses `Size: 500ml, 1L` lines into family attributes. */
export function parseAttributes(textValue: string): Attribute[] {
  return textValue
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [name, ...rest] = line.split(':');
      return {
        name: name.trim(),
        values: rest
          .join(':')
          .split(',')
          .map((v) => v.trim())
          .filter(Boolean),
      };
    });
}

export const formatAttributes = (attributes: Attribute[]) =>
  attributes.map((a) => `${a.name}: ${a.values.join(', ')}`).join('\n');
