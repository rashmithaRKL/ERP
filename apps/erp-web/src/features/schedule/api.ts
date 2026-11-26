import { useEffect } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../../lib/api';
import type { Row, Value } from '../../lib/api';
import { num, text } from '../../lib/format';

/** Every schedule query shares this prefix, so one invalidation refreshes the whole feature. */
export const SCHEDULE = 'schedule';

/** Window event fired after a schedule command posted from outside the page (heading button). */
const CHANGED = 'dex:schedule-changed';

export function scheduleChanged(runId?: string) {
  window.dispatchEvent(new CustomEvent(CHANGED, { detail: runId }));
}

/** Invalidates the schedule queries whenever a schedule command succeeded anywhere; `onRun` gets a new run's id. */
export function useScheduleSync(onRun?: (id: string) => void) {
  const qc = useQueryClient();
  useEffect(() => {
    const listener = (e: Event) => {
      void qc.invalidateQueries({ queryKey: [SCHEDULE] });
      const id = (e as CustomEvent<string | undefined>).detail;
      if (id && onRun) onRun(id);
    };
    window.addEventListener(CHANGED, listener);
    return () => window.removeEventListener(CHANGED, listener);
  }, [qc, onRun]);
}

/** Writes are for planners (ADMIN always). */
export const PLANNERS = ['PLANNER'];

// ------------------------------------------------------------------ shapes

export type Segment = { start: string; end: string };
export type Bar = Row & { segments: Segment[] };
export type Interval = { start: string; end: string; kind: string; label: string };
export type GanttMachine = Row & {
  working: Interval[];
  closed: { date: string; reason: string }[];
  blocks: Row[];
  bars: Bar[];
};
export type GanttData = { run: Row; from: string; to: string; now: string; machines: GanttMachine[] };
export type CapacityDay = {
  date: string;
  calendar_minutes: number;
  blocked_minutes: number;
  available_minutes: number;
  scheduled_minutes: number;
  load_pct: number;
  overloaded: boolean;
  closed: boolean;
  closed_reason: string | null;
};
export type CapacityData = {
  run: Row;
  from: string;
  to: string;
  machines: (Row & { days: CapacityDay[] })[];
};
export type Overview = {
  published: Row | null;
  /** V16: changes a new run would see since the published run was created (null without a published run). */
  stale?: { changes: number; last_change_at: string | null } | null;
  draft?: Row | null;
  pins?: number;
  defaults: { horizonDays: number; breakdownHours: number; useAlternates: boolean; includeDrafts: boolean };
  now: string;
};
export type RunDetail = { run: Row; orders: Row[]; pins: Row[] };
export type Compare = {
  run: Row;
  base: Row | null;
  summary: Record<string, number>;
  orders: Row[];
  operations: Row[];
};
export type Projection = Row & { operations: Row[] };

// ------------------------------------------------------------------ queries

export const useOverview = () =>
  useQuery({ queryKey: [SCHEDULE, 'overview'], queryFn: () => request<Overview>('/schedule') });

export const useRuns = (enabled: boolean) =>
  useQuery({
    queryKey: [SCHEDULE, 'runs'],
    queryFn: () => request<{ items: Row[]; next: string }>('/schedule/runs?limit=20'),
    enabled,
  });

export const useRunDetail = (id?: string) =>
  useQuery({
    queryKey: [SCHEDULE, 'run', id],
    queryFn: () => request<RunDetail>(`/schedule/runs/${id}`),
    enabled: !!id,
  });

export const useGantt = (id: string | undefined, from: string, to: string) =>
  useQuery({
    queryKey: [SCHEDULE, 'gantt', id, from, to],
    queryFn: () => request<GanttData>(`/schedule/runs/${id}/gantt?from=${from}&to=${to}`),
    enabled: !!id && !!from,
    placeholderData: (previous) => (previous?.run.id === id ? previous : undefined),
  });

export const useCapacity = (id: string | undefined, from: string, to: string) =>
  useQuery({
    queryKey: [SCHEDULE, 'capacity', id, from, to],
    queryFn: () => request<CapacityData>(`/schedule/runs/${id}/capacity?from=${from}&to=${to}`),
    enabled: !!id && !!from,
  });

export const useCompare = (id: string | undefined, enabled: boolean) =>
  useQuery({
    queryKey: [SCHEDULE, 'compare', id],
    queryFn: () => request<Compare>(`/schedule/runs/${id}/compare`),
    enabled: !!id && enabled,
  });

export const useNextOperations = (machineId?: string) =>
  useQuery({
    queryKey: [SCHEDULE, 'next', machineId],
    queryFn: () =>
      request<{ run: Row | null; machine: Row; items: Row[] }>(
        `/schedule/next?machineId=${machineId}&limit=6`,
      ),
    enabled: !!machineId,
  });

/** An order's projection in the published run (and the newest draft for planners); refreshed with the order drawer. */
export const useOrderProjection = (orderId: string) =>
  useQuery({
    queryKey: ['order', orderId, 'schedule'],
    queryFn: () =>
      request<{ published: Projection | null; draft?: Projection | null }>(`/schedule/orders/${orderId}`),
  });

/** The work centers an operation may run on (`GET /api/operations/{id}`, V10). */
export const useOperationMachines = (operationId?: string) =>
  useQuery({
    queryKey: ['operations', 'detail', operationId],
    queryFn: () => request<{ machines: Row[] }>(`/operations/${operationId}`),
    enabled: !!operationId,
  });

// ------------------------------------------------------------------ time helpers (Asia/Colombo)

export const ZONE = 'Asia/Colombo';
const MINUTE = 60_000;

const parts = (iso: string) => {
  const p = Object.fromEntries(
    new Intl.DateTimeFormat('en-GB', {
      timeZone: ZONE,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(new Date(iso))
      .map((x) => [x.type, x.value]),
  );
  return p as Record<string, string>;
};

/** Local business date `yyyy-MM-dd` of an instant. */
export function localDate(iso: string | number) {
  const p = parts(typeof iso === 'number' ? new Date(iso).toISOString() : iso);
  return `${p.year}-${p.month}-${p.day}`;
}

/** Local `yyyy-MM-ddTHH:mm` (the pin command's input format). */
export function localInput(iso: string) {
  const p = parts(iso);
  return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`;
}

/** Start of a local business date as epoch milliseconds (Sri Lanka has no daylight saving: UTC+05:30). */
export function dayStart(day: string) {
  return Date.parse(`${day}T00:00:00+05:30`);
}

export function addDays(day: string, days: number) {
  return localDate(dayStart(day) + days * 86_400_000 + 12 * 3_600_000);
}

/** "Tue 26 Nov 14:30" in business time. */
export function when(iso: Value) {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: ZONE,
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(text(iso)));
}

/** "14:30" in business time. */
export function clock(iso: Value) {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: ZONE,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(text(iso)));
}

/** "Tue 26" for a local date. */
export function dayLabel(day: string) {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: ZONE,
    weekday: 'short',
    day: 'numeric',
    month: 'short',
  }).format(new Date(dayStart(day) + 12 * 3_600_000));
}

/** Minutes as "45 min", "5 h 20 min" or "3 d 4 h". */
export function duration(minutes: unknown) {
  const m = Math.round(num(minutes));
  if (m < 60) return `${m} min`;
  if (m < 48 * 60) {
    const h = Math.floor(m / 60);
    const rest = m % 60;
    return rest ? `${h} h ${rest} min` : `${h} h`;
  }
  const d = Math.floor(m / 1440);
  const h = Math.round((m % 1440) / 60);
  return h ? `${d} d ${h} h` : `${d} d`;
}

export const minutesBetween = (a: string, b: string) => (Date.parse(b) - Date.parse(a)) / MINUTE;

// ------------------------------------------------------------------ labels

export const RUN_STATUS: Record<string, string> = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  SUPERSEDED: 'Superseded',
  DISCARDED: 'Discarded',
};

export const ORDER_STATUS: Record<string, string> = {
  SCHEDULED: 'Scheduled',
  NO_ROUTING: 'Unscheduled: no routing',
  BEYOND_HORIZON: 'Beyond the horizon',
  NO_OPEN_WORK: 'No open operations',
};

export const CHANGE_LABELS: Record<string, string> = {
  NEWLY_LATE: 'Newly late',
  NO_LONGER_LATE: 'No longer late',
  LATER: 'Later',
  EARLIER: 'Earlier',
  SAME: 'Unchanged',
  NEW: 'New',
  DROPPED: 'Dropped',
  MOVED: 'Moved',
};

/** An operation the planner may pin or move: not started, still open. */
export const pinnable = (bar: Row) =>
  !bar.running &&
  (bar.operation_id
    ? ['PENDING', 'READY'].includes(text(bar.current_status))
    : bar.operation_status === 'READY' &&
      !['IN_PROGRESS', 'PARTIALLY_COMPLETED'].includes(text(bar.order_status)));

export const runLabel = (run: Row | null | undefined) =>
  run ? `${text(run.number)} · ${RUN_STATUS[text(run.status)] || text(run.status)}` : '';
