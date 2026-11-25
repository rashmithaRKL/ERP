import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { request } from '../../lib/api';
import type { Row, Value } from '../../lib/api';

/**
 * V10 routings, shift calendars, skills and manufacturing-order operations: response types, query hooks and
 * invalidation. Query keys: `['routings', …]`, `['calendars', …]`, `['skills', …]`, `['operations', …]`; the order
 * drawer's operations use `['order', id, 'operations']` so `workspace.refresh()` updates them.
 */

export type MachineRef = { id: string; code: string; name: string; status?: string };

/** A routing operation (`routing_operations` row with work center and skill); read `alternates` with {@link listOf}. */
export type RoutingOperation = Row;

/** An array-valued field of a row (`alternates`, `weekday_list`, `days`, `skills`), or an empty list. */
export function listOf<T>(row: Row | null | undefined, key: string): T[] {
  return (row?.[key] as unknown as T[] | undefined) ?? [];
}

export type RoutingDetail = {
  routing: Row;
  operations: RoutingOperation[];
  revisions: Row[];
  orders: number;
  events: Row[];
};

export type RoutingComparison = {
  from: Row;
  to: Row;
  operations: {
    seq: number;
    status: 'ADDED' | 'REMOVED' | 'CHANGED' | 'SAME';
    from: RoutingOperation | null;
    to: RoutingOperation | null;
    changes: string[];
  }[];
  summary: { added: number; removed: number; changed: number };
};

export type CalendarOverview = {
  calendars: Row[];
  /** Rows carry `weekday_list` (ISO numbers, read with {@link listOf}), `paid_minutes`, `overnight`. */
  patterns: Row[];
  workCenters: Row[];
};

export type CalendarDetail = { calendar: Row; patterns: Row[]; exceptions: Row[]; workCenters: Row[] };

export type Interval = { start: string; end: string; kind: string; label: string };
export type AvailabilityDay = {
  date: string;
  minutes: number;
  closed: boolean;
  closedReason: string | null;
  intervals: Interval[];
};
/** A work-center row with `days` ({@link AvailabilityDay}, read with {@link listOf}). */
export type WorkCenterAvailability = Row;
export type Availability = { from: string; to: string; workCenters: WorkCenterAvailability[] };

/** A user row with `skills` (read with {@link listOf}). */
export type UserSkills = Row;

export type OrderOperations = { routing: Row | null; operations: Row[]; logs: Row[] };

export type OperationDetail = {
  operation: Row;
  order: Row;
  logs: Row[];
  reports: Row[];
  machines: Row[];
};

export const useRoutingDetail = (id: string | undefined) =>
  useQuery({
    queryKey: ['routings', 'detail', id],
    queryFn: () => request<RoutingDetail>(`/routings/${id}`),
    enabled: !!id,
  });

export const useRoutingComparison = (from: string, to: string) =>
  useQuery({
    queryKey: ['routings', 'compare', from, to],
    queryFn: () => request<RoutingComparison>(`/routings/compare?from=${from}&to=${to}`),
    enabled: !!from && !!to && from !== to,
  });

export const useCalendars = () =>
  useQuery({ queryKey: ['calendars', 'overview'], queryFn: () => request<CalendarOverview>('/calendars') });

export const useCalendarDetail = (id: string | undefined) =>
  useQuery({
    queryKey: ['calendars', 'detail', id],
    queryFn: () => request<CalendarDetail>(`/calendars/${id}`),
    enabled: !!id,
  });

export const useAvailability = (from: string, to: string, machineId?: string) =>
  useQuery({
    queryKey: ['calendars', 'availability', from, to, machineId ?? ''],
    queryFn: () =>
      request<Availability>(
        `/calendars/available-minutes?from=${from}&to=${to}${machineId ? `&machineId=${machineId}` : ''}`,
      ),
  });

export const useSkills = () =>
  useQuery({ queryKey: ['skills', 'catalogue'], queryFn: () => request<{ skills: Row[] }>('/skills') });

export const useOrderOperations = (orderId: string) =>
  useQuery({
    queryKey: ['order', orderId, 'operations'],
    queryFn: () => request<OrderOperations>(`/manufacturing/${orderId}/operations`),
    enabled: !!orderId,
  });

export const useWorkCenterSummary = () =>
  useQuery({
    queryKey: ['operations', 'work-centers'],
    queryFn: () => request<{ items: Row[]; date: string }>('/operations/work-centers'),
  });

/** Refreshes every V10 read model (after a routing, calendar, skill or operation command). */
export async function invalidateRoutingData(qc: QueryClient) {
  await Promise.all(
    ['routings', 'calendars', 'skills', 'operations', 'order', 'manufacturing'].map((key) =>
      qc.invalidateQueries({ queryKey: [key] }),
    ),
  );
}

/** `request` followed by {@link invalidateRoutingData}. */
export function useRoutingRequest() {
  const qc = useQueryClient();
  return async <T>(url: string, body: unknown, key?: string) => {
    const result = await request<T>(url, body, key);
    await invalidateRoutingData(qc);
    return result;
  };
}

/** ISO weekday numbers (1 = Monday) to short names. */
export const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export function weekdayText(days: number[] | Value | undefined) {
  const list = Array.isArray(days) ? days : [];
  if (list.length === 7) return 'Every day';
  if (list.join() === '1,2,3,4,5') return 'Mon–Fri';
  if (list.join() === '1,2,3,4,5,6') return 'Mon–Sat';
  return list.map((d) => WEEKDAYS[d - 1]).join(' ');
}

/** `HH:mm` of a `HH:mm:ss` time value. */
export const hhmm = (value: Value | undefined) => (value ? String(value).slice(0, 5) : '');

/** Minutes as "1 h 20 min" / "45 min". */
export function minutesText(value: Value | number | undefined, digits = 0) {
  const minutes = Number(value || 0);
  if (Math.abs(minutes) < 60)
    return `${minutes.toLocaleString('en-LK', { maximumFractionDigits: digits })} min`;
  const hours = Math.floor(minutes / 60);
  const rest = Math.round(minutes - hours * 60);
  return rest ? `${hours} h ${rest} min` : `${hours} h`;
}

/** Hours with at most one decimal ("15 h", "7.5 h"). */
export const hoursText = (minutes: number) =>
  `${(minutes / 60).toLocaleString('en-LK', { maximumFractionDigits: 1 })} h`;

/** Run minutes per unit to a rate ("1,200 / h"). */
export function rateText(runMinutesPerUnit: Value | undefined, uom = 'pcs') {
  const perUnit = Number(runMinutesPerUnit || 0);
  if (!perUnit) return '—';
  return `${(60 / perUnit).toLocaleString('en-LK', { maximumFractionDigits: 1 })} ${uom}/h`;
}

/** Monday of the week containing `date` (yyyy-MM-dd). */
export function mondayOf(date: string) {
  const d = new Date(`${date}T12:00:00`);
  const day = (d.getDay() + 6) % 7;
  d.setDate(d.getDate() - day);
  return d.toISOString().slice(0, 10);
}

/** `date` (yyyy-MM-dd) plus `days`. */
export function addDays(date: string, days: number) {
  const d = new Date(`${date}T12:00:00`);
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
}
