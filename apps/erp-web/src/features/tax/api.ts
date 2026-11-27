import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { fmt, num, text } from '../../lib/format';

/** Every tax query starts with `['tax']`, so one invalidation refreshes codes, settings, profiles and estimates. */
export const TAX = 'tax';

/** `GET /api/tax/codes` component: its rate history (newest first, `effective_to` derived) and today's rate. */
export type TaxComponent = Row & { rates: Row[] };
export type TaxCode = Row & { components: TaxComponent[] };
export type TaxCodes = { items: TaxCode[]; today: string };

export type TaxTemplate = {
  code: string;
  country: string;
  name: string;
  rounding: string;
  prices_include_tax: boolean;
  notes: string[];
  disclaimer: string;
  applied: boolean;
  codes: { code: string; name: string; applies_to: string; kind: string; rates: Row[] }[];
};
export type TaxSettings = {
  settings: Row;
  currency: string;
  scale: number;
  templates: TaxTemplate[];
  today: string;
};

/** `TaxService.Result` as JSON (one line's components and the document totals per code). */
export type TaxComponentAmount = {
  componentId: string;
  seq: number;
  name: string;
  compound: boolean;
  rateVersionId: string;
  rate: number;
  base: number;
  tax: number;
};
export type TaxCodeTotal = {
  taxCodeId: string;
  code: string;
  name: string;
  kind: string;
  accountCode: string;
  base: number;
  tax: number;
  components: TaxComponentAmount[];
};
export type TaxLine = {
  lineNo: number;
  itemId: string | null;
  amount: number;
  taxCodeId: string | null;
  code: string | null;
  codeName: string | null;
  kind: string | null;
  source: string;
  base: number;
  tax: number;
  gross: number;
  components: TaxComponentAmount[];
};
export type TaxResult = {
  currency: string;
  scale: number;
  date: string;
  direction: 'OUTPUT' | 'INPUT';
  pricesIncludeTax: boolean;
  rounding: string;
  lines: TaxLine[];
  codes: TaxCodeTotal[];
  net: number;
  tax: number;
  gross: number;
  warnings: string[];
};
/** `GET /api/tax/estimates/{sales-orders|purchase-orders}/{id}`. */
export type TaxEstimateResponse = {
  order_id: string;
  number: string;
  order_total: number;
  date: string;
  configured: boolean;
  available: boolean;
  message?: string;
  estimate?: TaxResult;
};

export const KIND_LABELS: Record<string, string> = {
  STANDARD: 'Standard',
  ZERO_RATED: 'Zero-rated',
  EXEMPT: 'Exempt',
};
export const APPLIES_LABELS: Record<string, string> = {
  SALES: 'Sales',
  PURCHASES: 'Purchases',
  BOTH: 'Sales and purchases',
};
export const SOURCE_LABELS: Record<string, string> = {
  LINE: 'line code',
  EXEMPTION: 'partner exemption',
  ITEM: 'item default',
  PARTNER: 'partner default',
  TENANT: 'workspace default',
  NONE: 'no tax code',
};

export function useTaxCodes(enabled = true) {
  return useQuery({
    queryKey: [TAX, 'codes'],
    queryFn: () => request<TaxCodes>('/tax/codes'),
    enabled,
  });
}

export function useTaxSettings(enabled = true) {
  return useQuery({
    queryKey: [TAX, 'settings'],
    queryFn: () => request<TaxSettings>('/tax/settings'),
    enabled,
  });
}

export const invalidateTax = (qc: QueryClient) => qc.invalidateQueries({ queryKey: [TAX] });
export function useInvalidateTax() {
  const qc = useQueryClient();
  return () => invalidateTax(qc);
}

/** `18 %`, `12.5 %`, `18.1234 %`, or an em dash. */
export function pct(x: unknown) {
  if (x === null || x === undefined || x === '') return '—';
  return `${fmt(num(x), 4)} %`;
}

/** Select options for the codes usable on one side (`SALES` or `PURCHASES`): active codes applying to it. */
export function codeOptions(codes: TaxCode[], side: 'SALES' | 'PURCHASES', keep?: unknown) {
  return codes
    .filter(
      (c) =>
        (c.active && (c.applies_to === 'BOTH' || c.applies_to === side)) || (keep && c.id === text(keep)),
    )
    .map((c) => ({
      value: c.id,
      label: `${text(c.code)} · ${text(c.name)}${c.current_rate != null ? ` (${pct(c.current_rate)})` : ''}`,
    }));
}

/** `yyyy-MM-dd` of the first and last day of the month before `todayIso`. */
export function previousMonth(todayIso: string) {
  const [y, m] = todayIso.split('-').map(Number);
  const first = new Date(Date.UTC(y, m - 2, 1));
  const last = new Date(Date.UTC(y, m - 1, 0));
  return { from: first.toISOString().substring(0, 10), to: last.toISOString().substring(0, 10) };
}
