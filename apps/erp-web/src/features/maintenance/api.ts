import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useWorkspace } from '../../app/workspace';
import { request } from '../../lib/api';
import type { Row, Value } from '../../lib/api';
import { fmt, num, text } from '../../lib/format';

/** Every maintenance query shares this prefix, so one invalidation refreshes the whole feature. */
export const MAINTENANCE = 'maintenance';

/** Window event fired after a maintenance command posted from a form opener (page heading, machine drawer). */
const CHANGED = 'dex:maintenance-changed';

export function maintenanceChanged() {
  window.dispatchEvent(new Event(CHANGED));
}

/** Invalidates the maintenance queries whenever a maintenance command succeeded anywhere in the app. */
export function useMaintenanceSync() {
  const qc = useQueryClient();
  useEffect(() => {
    const listener = () => qc.invalidateQueries({ queryKey: [MAINTENANCE] });
    window.addEventListener(CHANGED, listener);
    return () => window.removeEventListener(CHANGED, listener);
  }, [qc]);
}

// ------------------------------------------------------------------ roles (ADMIN is implied by `can`)

/** Report and close downtime, request corrective work. */
export const REPORTERS = ['OPERATOR', 'PLANNER', 'MAINTENANCE'];
/** Approve, schedule and cancel work orders; plans, generation, downtime corrections, machine status. */
export const PLANNERS = ['PLANNER'];
/** Start, record, log labour, issue parts and complete. */
export const TECHNICIANS = ['MAINTENANCE'];
/** Record run-hour meter readings. */
export const METER_READERS = ['MAINTENANCE', 'PLANNER', 'OPERATOR'];

// ------------------------------------------------------------------ codes

export const REASONS = [
  'BREAKDOWN',
  'PLANNED_MAINTENANCE',
  'CHANGEOVER',
  'MATERIAL_SHORTAGE',
  'NO_OPERATOR',
  'QUALITY_HOLD',
  'UTILITIES',
  'OTHER',
] as const;

export const REASON_LABELS: Record<string, string> = {
  BREAKDOWN: 'Breakdown',
  PLANNED_MAINTENANCE: 'Planned maintenance',
  CHANGEOVER: 'Changeover',
  MATERIAL_SHORTAGE: 'Material shortage',
  NO_OPERATOR: 'No operator',
  QUALITY_HOLD: 'Quality hold',
  UTILITIES: 'Utilities',
  OTHER: 'Other',
};

export const reasonLabel = (code: unknown) => REASON_LABELS[text(code)] || text(code);

export const PRIORITIES = ['NORMAL', 'HIGH', 'URGENT'] as const;

export const STATUS_LABELS: Record<string, string> = {
  REQUESTED: 'Requested',
  APPROVED: 'Approved',
  SCHEDULED: 'Scheduled',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
};

const TONES: Record<string, string> = {
  REQUESTED: 'amber',
  APPROVED: 'blue',
  SCHEDULED: 'violet',
  IN_PROGRESS: 'blue',
  COMPLETED: 'green',
  CANCELLED: 'red',
  OPEN: 'red',
  CLOSED: 'green',
  SUPERSEDED: 'gray',
  VOID: 'gray',
  OVERDUE: 'red',
  DUE: 'amber',
  UPCOMING: 'green',
  INACTIVE: 'gray',
  RUNNING: 'green',
  IDLE: 'gray',
  MAINTENANCE: 'amber',
};

const DOWNTIME_LABELS: Record<string, string> = {
  OPEN: 'Open',
  CLOSED: 'Closed',
  SUPERSEDED: 'Corrected',
  VOID: 'Withdrawn',
  OVERDUE: 'Overdue',
  DUE: 'Due today',
  UPCOMING: 'Upcoming',
  INACTIVE: 'Inactive',
  RUNNING: 'Running',
  IDLE: 'Idle',
  MAINTENANCE: 'Maintenance',
};

/** Status chip for work orders, downtime events, plan due states and machines (shared `.badge` tones). */
export function statusTone(code: unknown) {
  return TONES[text(code)] || 'gray';
}

export function statusText(code: unknown) {
  const value = text(code);
  return STATUS_LABELS[value] || DOWNTIME_LABELS[value] || value.charAt(0) + value.slice(1).toLowerCase();
}

// ------------------------------------------------------------------ time

/** `2026-11-25T08:30` for a `datetime-local` input, in the browser's time zone. */
export function localInput(value: Date | string | null | undefined) {
  if (!value) return '';
  const d = typeof value === 'string' ? new Date(value) : value;
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** A `datetime-local` value as an ISO instant (empty stays undefined, so the server uses "now"). */
export function isoFromLocal(value: string | undefined) {
  if (!value) return undefined;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

/** `25 Nov, 14:05`. */
export const when = (x: unknown) =>
  x
    ? new Date(text(x)).toLocaleString('en-GB', {
        day: 'numeric',
        month: 'short',
        hour: '2-digit',
        minute: '2-digit',
      })
    : '—';

/** `1 h 35 min`, `45 min`, `2 d 3 h`. */
export function duration(minutes: unknown) {
  const total = Math.round(num(minutes));
  if (total < 60) return `${fmt(total)} min`;
  const hours = Math.floor(total / 60);
  if (hours < 48) return `${hours} h${total % 60 ? ` ${total % 60} min` : ''}`;
  return `${Math.floor(hours / 24)} d${hours % 24 ? ` ${hours % 24} h` : ''}`;
}

/** Minutes since an ISO time (open downtime). */
export function minutesSince(x: unknown) {
  return x ? Math.max(0, (Date.now() - new Date(text(x)).getTime()) / 60000) : 0;
}

// ------------------------------------------------------------------ payloads

export type ChecklistItem = { task: string; result: 'PASS' | 'FAIL' | 'NA' | null; note: string };
export type WorkOrderDetail = {
  order: Row;
  checklist: ChecklistItem[];
  parts: Row[];
  labour: Row[];
  downtime: Row[];
  journal: Row[];
  events: Row[];
};
export type PlanRow = Row & { checklist?: never };
export type PlanDetail = { plan: Record<string, Value | string[]>; orders: Row[]; events: Row[] };
export type MachineMetrics = Record<string, Value>;
export type MachineDetail = {
  machine: Row;
  openDowntime: Row | null;
  orders: Row[];
  plans: Record<string, Value | string[]>[];
  meter: { reading: number; recorded_at: string } | null;
  runningOrders: string[];
  metrics: MachineMetrics;
  history: Row[];
};
export type Metrics = {
  from: string;
  to: string;
  periodMinutes: number;
  machines: Row[];
  pareto: { reason: string; minutes: number; events: number; share: number; cumulative: number }[];
  totals: Record<string, Value>;
};

// ------------------------------------------------------------------ queries

export function useMachines() {
  return useQuery({
    queryKey: [MAINTENANCE, 'machines'],
    queryFn: () => request<{ items: Row[] }>('/maintenance/machines'),
  });
}

export function useTechnicians(enabled = true) {
  return useQuery({
    queryKey: [MAINTENANCE, 'technicians'],
    queryFn: () => request<{ items: Row[] }>('/maintenance/technicians'),
    staleTime: 60_000,
    enabled,
  });
}

export function useSpareParts(enabled = true) {
  return useQuery({
    queryKey: [MAINTENANCE, 'spare-parts'],
    queryFn: () => request<{ items: Row[] }>('/maintenance/spare-parts'),
    enabled,
  });
}

export function useMetrics(from: string, to: string, machine = '') {
  const params = new URLSearchParams({ from, to });
  if (machine) params.set('machine', machine);
  return useQuery({
    queryKey: [MAINTENANCE, 'metrics', from, to, machine],
    queryFn: () => request<Metrics>(`/maintenance/metrics?${params}`),
  });
}

/**
 * Runs a maintenance command, then refreshes the maintenance queries and the shared workspace snapshot (machine
 * status, attention counts). `run` resolves to the response, or throws.
 */
export function useMaintenanceCommand() {
  const qc = useQueryClient();
  const { refresh, notify } = useWorkspace();
  const [busy, setBusy] = useState(false);
  async function run<T = Row>(url: string, body: unknown = {}, success?: string, key?: string): Promise<T> {
    setBusy(true);
    try {
      const result = await request<T>(url, body, key);
      if (success) notify(success);
      return result;
    } finally {
      await Promise.all([qc.invalidateQueries({ queryKey: [MAINTENANCE] }), refresh()]);
      setBusy(false);
    }
  }
  return { run, busy };
}

/** `yyyy-mm-dd` of the Asia/Colombo calendar day `offset` days from today. */
export function colomboDay(offset = 0) {
  const now = new Date(Date.now() + offset * 86_400_000);
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Colombo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}
