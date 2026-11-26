import { useEffect } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import type { Workspace } from '../../app/workspace';
import { request } from '../../lib/api';
import { businessDay } from './format';
import type { OeeStatus, WaterfallStep } from './format';

/** Every OEE query shares this prefix. */
export const PERFORMANCE = 'performance';
/** Closing and recalculating days (ADMIN is implied by `can`). */
export const CLOSERS = ['PLANNER'];

const CHANGED = 'dex:performance-changed';

export function performanceChanged() {
  window.dispatchEvent(new Event(CHANGED));
}

/** Refreshes the OEE queries after a close or recalculation posted anywhere in the app. */
export function usePerformanceSync() {
  const qc = useQueryClient();
  useEffect(() => {
    const listener = () => qc.invalidateQueries({ queryKey: [PERFORMANCE] });
    window.addEventListener(CHANGED, listener);
    return () => window.removeEventListener(CHANGED, listener);
  }, [qc]);
}

export type Warning = {
  code: string;
  severity: 'warning' | 'info';
  message: string;
  minutes?: number;
  quantity?: number;
  machineId?: string;
  machine?: string;
  day?: string;
};

/** Figures of one work-center day, a roll-up (several days or work centers), or a trend point. */
export type Figures = {
  status: OeeStatus | null;
  oee: number | null;
  availability: number | null;
  performance: number | null;
  quality: number | null;
  performanceRaw?: number | null;
  utilization?: number | null;
  capacityUtilization?: number | null;
  outputCoverage?: number | null;
  timeCoverage?: number | null;
  calendarMinutes?: number;
  scheduledMinutes?: number;
  plannedStopMinutes?: number;
  plannedMinutes?: number;
  plannedMinutesAll?: number;
  runMinutes?: number;
  netRunMinutes?: number | null;
  fullyProductiveMinutes?: number | null;
  setupMinutes?: number;
  changeoverMinutes?: number;
  breakdownMinutes?: number;
  otherStopMinutes?: number;
  unrecordedMinutes?: number;
  derivedRunMinutes?: number;
  runOutsideMinutes?: number;
  busyMinutes?: number;
  busyInShiftMinutes?: number;
  goodCount?: number;
  scrapCount?: number;
  totalCount?: number;
  untimedGood?: number;
  untimedScrap?: number;
  goodRate?: number | null;
  days?: number;
  measuredDays?: number;
  closedDays?: number;
  warningCount?: number;
  statusCounts?: Record<string, number>;
  closed?: boolean;
  version?: number;
  partial?: boolean;
  period?: string;
  warnings?: Warning[];
};

export type Period = { key: string; from: string; to: string; label: string; partial: boolean };
export type WorkCenterOee = {
  id: string;
  code: string;
  name: string;
  kind: string;
  status: string;
  calendarSources: string[];
  totals: Figures;
  cells: Figures[];
};
export type OeeResponse = {
  from: string;
  to: string;
  today: string;
  bucket: 'day' | 'week';
  periods: Period[];
  plant: Figures;
  series: (Figures & Period)[];
  workCenters: WorkCenterOee[];
  coverage: {
    timeCoverage: number | null;
    outputCoverage: number | null;
    plannedMinutes: number;
    measuredPlannedMinutes: number;
    untimedOutput: number;
    workCenterDays: number;
    closedDays: number;
    statusCounts: Record<string, number>;
    warningCounts: Record<string, number>;
  };
  warningTotal: number;
  warnings: Warning[];
};

export type Loss = {
  key: string;
  label: string;
  category: 'AVAILABILITY' | 'PERFORMANCE' | 'QUALITY';
  measured: boolean;
  minutes: number | null;
  source: string;
};
export type ParetoRow = {
  reason: string;
  minutes: number;
  plannedMinutes: number;
  events: number;
  share: number | null;
  cumulative: number | null;
};
export type QualityRow = {
  id: string;
  code: string;
  name: string;
  goodCount: number;
  scrapCount: number;
  totalCount: number;
  goodRate: number | null;
  untimedGood: number;
  untimedScrap: number;
  postProductionScrap: number;
  postProductionScrapValue: number;
  reworkInput: number;
};
export type LossesResponse = {
  from: string;
  to: string;
  totals: Figures;
  waterfall: WaterfallStep[];
  sixLosses: Loss[];
  pareto: ParetoRow[];
  quality: {
    inProcess: { goodCount: number; scrapCount: number; goodRate: number | null };
    untimed: { goodCount: number; scrapCount: number };
    postProduction: { dispositions: number; quantity: number; value: number; note: string };
    rework: {
      orders: number;
      completed: number;
      inputQuantity: number;
      outputQuantity: number;
      yield: number | null;
    };
    byWorkCenter: QualityRow[];
  };
};

export type DayFigures = Figures & {
  machineId: string;
  day: string;
  calendarSource: string;
  calendarCode: string | null;
  calendarName: string | null;
  closedAt?: string;
  closedBy?: string;
  recalculationReason?: string;
  stops: { reason: string; minutes: number; plannedMinutes: number; events: string[] }[];
  operations: {
    operationId: string;
    orderId: string;
    orderNumber: string;
    seq: number;
    name: string;
    idealMinutesPerUnit: number;
    runMinutes: number;
    setupMinutes: number;
    goodCount: number;
    scrapCount: number;
    performance: number | null;
  }[];
  intervals: { start: string; end: string; kind: string; label: string }[];
};
export type DayResponse = {
  machine: { id: string; code: string; name: string; kind: string; status: string };
  date: string;
  today: string;
  live: DayFigures;
  snapshot: DayFigures | null;
  calendarChanged?: boolean;
  differs?: boolean;
  history: {
    id: string;
    version: number;
    status: string;
    oee_status: string;
    oee: number | null;
    availability: number | null;
    performance: number | null;
    quality: number | null;
    planned_minutes: number;
    reason: string;
    closed_at: string;
    closed_by: string;
    superseded_at: string | null;
  }[];
};

export type Filters = { from: string; to: string; machine: string; bucket: 'day' | 'week' };

function query(params: Record<string, string | undefined>) {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v) q.set(k, v);
  return q.toString();
}

export function useOee(f: Filters, enabled = true) {
  return useQuery({
    queryKey: [PERFORMANCE, 'oee', f],
    queryFn: () => request<OeeResponse>(`/performance/oee?${query(f)}`),
    placeholderData: keepPreviousData,
    enabled,
  });
}

export function useLosses(f: Filters) {
  return useQuery({
    queryKey: [PERFORMANCE, 'losses', f.from, f.to, f.machine],
    queryFn: () =>
      request<LossesResponse>(`/performance/losses?${query({ from: f.from, to: f.to, machine: f.machine })}`),
    placeholderData: keepPreviousData,
  });
}

export function useDay(machineId: string, date: string) {
  return useQuery({
    queryKey: [PERFORMANCE, 'day', machineId, date],
    queryFn: () => request<DayResponse>(`/performance/days/${encodeURIComponent(machineId)}/${date}`),
  });
}

/**
 * Opens the close-day form: freezes the OEE of a completed day for one or every work center, or (with a reason)
 * recalculates a closed day as a new version that keeps the earlier one.
 */
export function closeDay(
  workspace: Workspace,
  preset: { date?: string; machineId?: string; recalculate?: boolean } = {},
) {
  const machines = workspace.data?.machines || [];
  workspace.openForm({
    title: preset.recalculate ? 'Recalculate a closed day' : 'Close a day',
    description: preset.recalculate
      ? 'The current shift calendar and records are applied; the previous version stays in the history.'
      : 'Freezes the OEE of a completed day, so later calendar changes do not rewrite it. Days already closed stay unchanged.',
    submit: preset.recalculate ? 'Recalculate' : 'Close day',
    fields: [
      { name: 'date', label: 'Business day', type: 'date', defaultValue: preset.date || businessDay(-1) },
      {
        name: 'machineId',
        label: 'Work center',
        options: [
          { value: '', label: 'All work centers' },
          ...machines.map((m) => ({ value: m.id, label: `${String(m.code)} · ${String(m.name)}` })),
        ],
        defaultValue: preset.machineId || '',
        optional: true,
      },
      ...(preset.recalculate
        ? [
            {
              name: 'reason',
              label: 'Reason for the recalculation',
              type: 'textarea',
              hint: 'At least 5 characters, e.g. "Late breakdown report corrected".',
            },
          ]
        : []),
    ],
    // The shell refreshes the workspace and confirms with a toast; the OEE queries refresh through the event.
    run: async (values, key) => {
      await request(
        '/performance/close',
        {
          date: values.date,
          machineIds: values.machineId ? [values.machineId] : null,
          recalculate: !!preset.recalculate,
          reason: values.reason || null,
        },
        key,
      );
      performanceChanged();
    },
  });
}
