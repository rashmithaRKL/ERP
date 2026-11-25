import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useWorkspace } from '../../app/workspace';
import { request } from '../../lib/api';
import type { Row, Value } from '../../lib/api';

/**
 * V12 quality endpoints. Every query key starts with `quality`, so `workspace.refresh()` (which invalidates
 * `['quality']`) reloads lists and drawers after any transaction.
 */
export const QUALITY = 'quality';

/** Roles that decide quality matters (ADMIN is implied by `can`). */
export const DECIDERS = ['QUALITY'];

export type Rec = Record<string, Value>;
export type Characteristic = Row & {
  name: string;
  kind: 'VARIABLE' | 'ATTRIBUTE';
  sample_size: number;
  critical: boolean;
};
export type InspectionPlanContext = { inspection: Row; plan: Row | null; characteristics: Characteristic[] };
export type Measurement = Row & { readings: number[] };
export type NcrDetail = { ncr: Row; dispositions: Row[]; capas: Row[]; activity: Row[] };
export type ReworkDetail = { rework: Row; materials: Row[]; links: Row[]; activity: Row[] };
export type CapaDetail = { capa: Row; ncrs: Row[]; actions: Row[]; activity: Row[] };
export type PlanDetail = { plan: Row; characteristics: Characteristic[] };
export type LotQuality = { ncrs: Row[]; reworkOrigin: Row | null; reworkedInto: Row[] };
export type QualitySummary = {
  from: string;
  to: string;
  openBySeverity: Record<string, number>;
  raised: number;
  scrapValue: number;
  scrapDispositions: number;
  reworkCost: number;
  reworkInputValue: number;
  reworkCompleted: number;
  concessions: number;
  topDefects: Row[];
};
/** `/api/state` key `qualityAlerts` (V12): small attention counts. */
export type QualityAlerts = {
  open_ncrs: number;
  open_critical_ncrs: number;
  pending_concessions: number;
  awaiting_returns: number;
  open_rework: number;
  overdue_capa_actions: number;
  my_overdue_capa_actions: number;
};

export function useQualityQuery<T>(name: string, path: string | undefined) {
  return useQuery({
    queryKey: [QUALITY, name, path],
    queryFn: () => request<T>(path!),
    enabled: !!path,
  });
}

export const useDefectCodes = () =>
  useQualityQuery<{ items: Row[] }>('defect-codes', '/quality/defect-codes');
export const usePeople = () => useQualityQuery<{ items: Row[] }>('people', '/quality/people');

/**
 * Posts a quality command, then refreshes quality queries and the workspace (state, lists). Errors are thrown for
 * the caller to show next to the control that caused them.
 */
export function useQualityCommand() {
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
      await Promise.all([
        qc.invalidateQueries({ queryKey: [QUALITY] }),
        qc.invalidateQueries({ queryKey: ['inventory'] }),
        refresh(),
      ]);
      setBusy(false);
    }
  }
  return { run, busy };
}

/** `?a=1&b=x` from the defined, non-empty values. */
export function params(values: Record<string, string | number | boolean | undefined | null>) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(values))
    if (value !== undefined && value !== null && value !== '' && value !== false)
      search.set(key, String(value));
  const out = search.toString();
  return out ? `?${out}` : '';
}

/** Characteristic specification as text: `0.40–0.52 mm (nominal 0.45)`, `≤ 0.05 %`, `pass/fail · 20 units`. */
export function specText(c: Row) {
  if (c.kind === 'ATTRIBUTE')
    return `Pass/fail · ${c.sample_size} unit${Number(c.sample_size) === 1 ? '' : 's'}`;
  const unit = c.unit ? ` ${c.unit}` : '';
  const lower = c.lower_limit === null || c.lower_limit === undefined ? null : trim(c.lower_limit);
  const upper = c.upper_limit === null || c.upper_limit === undefined ? null : trim(c.upper_limit);
  const range =
    lower !== null && upper !== null ? `${lower}–${upper}` : lower !== null ? `≥ ${lower}` : `≤ ${upper}`;
  const nominal = c.nominal === null || c.nominal === undefined ? '' : ` (nominal ${trim(c.nominal)})`;
  return `${range}${unit}${nominal}`;
}

/** A decimal without trailing zeros: `0.400000` → `0.4`. */
export function trim(value: Value | undefined) {
  if (value === null || value === undefined || value === '') return '';
  const n = Number(value);
  return Number.isFinite(n) ? String(Number(n.toFixed(6))) : String(value);
}

/** Whether a reading lies within the characteristic's limits (inclusive). */
export function inSpec(c: Row, value: number) {
  const lower = c.lower_limit === null || c.lower_limit === undefined ? null : Number(c.lower_limit);
  const upper = c.upper_limit === null || c.upper_limit === undefined ? null : Number(c.upper_limit);
  return (lower === null || value >= lower) && (upper === null || value <= upper);
}

export const SEVERITIES = ['CRITICAL', 'MAJOR', 'MINOR'];
export const NCR_SOURCES = [
  'INSPECTION',
  'PRODUCTION',
  'SUPPLIER',
  'CUSTOMER',
  'INTERNAL',
  'AUDIT',
  'RECALL',
];
export const RAISE_SOURCES = ['PRODUCTION', 'SUPPLIER', 'CUSTOMER', 'INTERNAL', 'AUDIT'];
export const ROOT_CAUSES = ['MAN', 'MACHINE', 'METHOD', 'MATERIAL', 'MEASUREMENT', 'ENVIRONMENT'];
export const DISPOSITIONS: Record<string, { label: string; hint: string }> = {
  USE_AS_IS: {
    label: 'Use as is (concession)',
    hint: 'Accept the quantity unchanged. A different quality approver must approve it before the lot is released.',
  },
  SCRAP: {
    label: 'Scrap',
    hint: 'Write the quantity off the held lot now: stock leaves through the ledger and its value moves to quality loss.',
  },
  RETURN_TO_SUPPLIER: {
    label: 'Return to supplier',
    hint: 'Purchased lots only. The warehouse then posts the supplier return (debit note) from this NCR.',
  },
  REWORK: {
    label: 'Rework',
    hint: 'Moves the quantity into a rework order; its output becomes a new quarantined lot with its own inspection.',
  },
};
