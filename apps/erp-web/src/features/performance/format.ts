import { fmt } from '../../lib/format';

/** OEE status of a work-center day (or the most telling status of a roll-up). */
export type OeeStatus = 'MEASURED' | 'NOT_SCHEDULED' | 'NO_DATA' | 'UNTIMED';

const STATUS_TEXT: Record<string, string> = {
  MEASURED: 'Measured',
  NOT_SCHEDULED: 'Not scheduled',
  NO_DATA: 'Not measured',
  UNTIMED: 'Untimed output',
};

/** Why a day has no OEE, in a few words (shown instead of a percentage, never as 0 %). */
const STATUS_REASON: Record<string, string> = {
  NOT_SCHEDULED: 'No planned production time (holiday, no shift or a planned stop all day)',
  NO_DATA: 'Planned time, but no time log, quantity report or downtime was recorded',
  UNTIMED: 'Only output of orders without a routing: no time records, no ideal cycle time',
  MEASURED: 'Run time without an ideal cycle time: performance cannot be measured',
};

export const statusText = (status: unknown) => STATUS_TEXT[String(status)] || String(status ?? '—');
export const statusReason = (status: unknown) => STATUS_REASON[String(status)] || '';

/** A 0..1 ratio as a percentage (`—` when not measurable). */
export function pct(ratio: unknown, digits = 1) {
  if (ratio === null || ratio === undefined || ratio === '') return '—';
  const n = Number(ratio);
  return Number.isFinite(n) ? `${fmt(n * 100, digits)}%` : '—';
}

/** Minutes as hours (`12.5 h`), or minutes below an hour (`45 min`). */
export function hours(minutes: unknown) {
  const n = Number(minutes);
  if (minutes === null || minutes === undefined || !Number.isFinite(n)) return '—';
  if (Math.abs(n) < 60) return `${fmt(Math.round(n))} min`;
  return `${fmt(n / 60, 1)} h`;
}

/**
 * Colour band of an OEE value for the heatmap and chips: `excellent` ≥ 85 % (world class), `good` ≥ 65 %, `fair` ≥
 * 45 %, `poor` below; `none` when not measurable.
 */
export function oeeBand(oee: unknown): 'excellent' | 'good' | 'fair' | 'poor' | 'none' {
  if (oee === null || oee === undefined || oee === '') return 'none';
  const n = Number(oee);
  if (!Number.isFinite(n)) return 'none';
  if (n >= 0.85) return 'excellent';
  if (n >= 0.65) return 'good';
  if (n >= 0.45) return 'fair';
  return 'poor';
}

/** Stat-card tone for an OEE value. */
export const oeeTone = (oee: unknown) =>
  ({ excellent: 'green', good: 'green', fair: 'amber', poor: 'amber', none: 'neutral' })[oeeBand(oee)];

/** The short text of a heatmap cell: the OEE, or why there is none. */
export function cellText(cell: { status?: unknown; oee?: unknown }) {
  if (cell.oee !== null && cell.oee !== undefined) return pct(cell.oee, 0);
  switch (cell.status) {
    case 'NOT_SCHEDULED':
      return '·';
    case 'UNTIMED':
      return 'untimed';
    case 'NO_DATA':
      return 'n/m';
    default:
      return '—';
  }
}

export type WaterfallStep = { key: string; label: string; kind: 'TOTAL' | 'LOSS'; minutes: number };
export type WaterfallBar = WaterfallStep & { base: number; value: number; hours: number };

/**
 * Floating bars for the loss waterfall (calendar → … → fully productive time): totals stand on zero, each loss floats
 * on the total that follows it, so the bars read as a staircase. Values are hours.
 */
export function waterfallBars(steps: WaterfallStep[]): WaterfallBar[] {
  return steps.map((step, i) => {
    const minutes = Math.max(0, Number(step.minutes) || 0);
    const next = steps[i + 1];
    const base = step.kind === 'LOSS' && next ? Math.max(0, Number(next.minutes) || 0) : 0;
    return { ...step, minutes, base: base / 60, value: minutes / 60, hours: minutes / 60 };
  });
}

/** Readable names of the data-coverage and data-quality warning codes. */
export const WARNING_LABELS: Record<string, string> = {
  NO_CALENDAR: 'No shift calendar',
  UNTIMED_OUTPUT: 'Output without a routing',
  NO_ACTIVITY: 'Nothing recorded',
  OPEN_TIME_LOG: 'Time log left open',
  OPEN_DOWNTIME: 'Downtime still open',
  RUN_OUTSIDE_PLANNED_TIME: 'Run outside planned time',
  LOG_DOWNTIME_OVERLAP: 'Time log overlaps downtime',
  PERFORMANCE_OVER_100: 'Performance above 100 %',
  NO_IDEAL_CYCLE_TIME: 'No ideal cycle time',
  UNRECORDED_TIME: 'Unrecorded idle time',
  RUN_WITHOUT_REPORT: 'Run without a quantity report',
  REPORT_WITHOUT_RUN: 'Report without run time',
};

export const warningLabel = (code: unknown) => WARNING_LABELS[String(code)] || String(code);

/** Today's date in the business time zone (Asia/Colombo), `yyyy-MM-dd`, shifted by `offset` days. */
export function businessDay(offset = 0, now = Date.now()) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Colombo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(now + offset * 86_400_000));
}
