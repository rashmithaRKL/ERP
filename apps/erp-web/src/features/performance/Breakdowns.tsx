import { AlertTriangle, ArrowRight, Info } from 'lucide-react';
import { Table } from '../../components';
import type { Row } from '../../lib/api';
import { date, fmt, money } from '../../lib/format';
import { reasonLabel } from '../maintenance/api';
import type { LossesResponse, OeeResponse, Warning } from './api';
import { hours, pct, warningLabel } from './format';
import './performance.css';

/** Downtime in shift time by reason (V11 effective downtime), largest first, with cumulative share. */
export function DowntimePareto({ rows }: { rows: LossesResponse['pareto'] }) {
  if (!rows.length) return <p className="perf-note">No downtime was recorded in shift time in this range.</p>;
  const top = Math.max(...rows.map((r) => r.minutes), 1);
  return (
    <ol className="perf-bars" aria-label="Downtime by reason">
      {rows.map((r) => {
        const planned = r.plannedMinutes >= r.minutes - 0.01;
        return (
          <li key={r.reason}>
            <span className="label">
              {reasonLabel(r.reason)}
              <small>
                {fmt(r.events)} {r.events === 1 ? 'event' : 'events'} ·{' '}
                {planned ? 'planned' : r.plannedMinutes > 0 ? 'partly planned' : 'unplanned'}
              </small>
            </span>
            <span className="perf-bar" aria-hidden="true">
              <i
                className={planned ? 'planned' : 'unplanned'}
                style={{ width: `${(r.minutes / top) * 100}%` }}
              />
            </span>
            <span className="value">
              {hours(r.minutes)}
              <small>
                {pct(r.share, 0)} · cum. {pct(r.cumulative, 0)}
              </small>
            </span>
          </li>
        );
      })}
    </ol>
  );
}

const CATEGORY: Record<string, string> = {
  AVAILABILITY: 'Availability',
  PERFORMANCE: 'Performance',
  QUALITY: 'Quality',
};

/** The six big losses mapped to what the data measures; unmeasured losses are labelled, never shown as zero. */
export function SixLosses({ losses }: { losses: LossesResponse['sixLosses'] }) {
  const top = Math.max(...losses.map((l) => l.minutes || 0), 1);
  return (
    <ol className="perf-bars" aria-label="Six big losses">
      {losses.map((l) => (
        <li key={l.key} title={l.source}>
          <span className="label">
            {l.label}
            <small>
              <span className="perf-cat">{CATEGORY[l.category]}</span>
            </small>
          </span>
          <span className="perf-bar" aria-hidden="true">
            <i className={l.category} style={{ width: `${((l.minutes || 0) / top) * 100}%` }} />
          </span>
          <span className={`value${l.measured ? '' : ' perf-not-measured'}`}>
            {l.measured ? hours(l.minutes) : 'Not measured'}
          </span>
        </li>
      ))}
    </ol>
  );
}

/** In-process scrap (OEE quality), untimed output, and the post-production losses reported apart from the OEE. */
export function QualityLosses({ quality }: { quality: LossesResponse['quality'] }) {
  const { inProcess, untimed, postProduction, rework } = quality;
  return (
    <>
      <dl className="perf-facts">
        <div>
          <dt>Good rate (operation reports)</dt>
          <dd>
            {pct(inProcess.goodRate)}
            <small>
              {fmt(inProcess.scrapCount)} scrap of {fmt(inProcess.goodCount + inProcess.scrapCount)}
            </small>
          </dd>
        </div>
        <div>
          <dt>Untimed output (no routing)</dt>
          <dd>
            {fmt(untimed.goodCount + untimed.scrapCount)}
            <small>{fmt(untimed.scrapCount)} scrap</small>
          </dd>
        </div>
        <div>
          <dt>Scrapped after production (NCR)</dt>
          <dd>
            {fmt(postProduction.quantity)}
            <small>
              {money(postProduction.value)} · {fmt(postProduction.dispositions)} dispositions
            </small>
          </dd>
        </div>
        <div>
          <dt>Reworked</dt>
          <dd>
            {fmt(rework.inputQuantity)}
            <small>
              {fmt(rework.orders)} orders · yield {pct(rework.yield)}
            </small>
          </dd>
        </div>
      </dl>
      <p className="perf-note">
        The OEE quality rate uses the good and scrap quantities reported on operations. Scrap and rework
        decided later on nonconformance reports are post-production losses and are shown here, not in the OEE.
      </p>
    </>
  );
}

/** Utilization per work center: busy (setup + run + changeover) against shift time and against 24 hours. */
export function UtilizationTable({ data }: { data: OeeResponse }) {
  const rows: Row[] = data.workCenters.map((wc) => ({
    id: wc.id,
    code: wc.code,
    name: wc.name,
    scheduled: wc.totals.scheduledMinutes ?? 0,
    busy: wc.totals.busyInShiftMinutes ?? 0,
    utilization: wc.totals.utilization ?? null,
    capacity: wc.totals.capacityUtilization ?? null,
    oee: wc.totals.oee ?? null,
    availability: wc.totals.availability ?? null,
    performance: wc.totals.performance ?? null,
    quality: wc.totals.quality ?? null,
    measured: `${wc.totals.measuredDays ?? 0} / ${wc.totals.days ?? 0}`,
    coverage: wc.totals.outputCoverage ?? null,
    calendar: wc.calendarSources.includes('NONE')
      ? 'none (24 h)'
      : wc.calendarSources.join(', ').toLowerCase(),
  }));
  return (
    <Table
      compact
      label="Utilization and OEE per work center"
      pageSize={Math.max(rows.length, 1)}
      rows={rows}
      columns={[
        {
          label: 'Work center',
          key: 'code',
          render: (r) => (
            <div className="cell-stack">
              <strong>{String(r.code)}</strong>
              <span>{String(r.name)}</span>
            </div>
          ),
        },
        { label: 'Shift time', key: 'scheduled', render: (r) => hours(r.scheduled) },
        { label: 'Busy', key: 'busy', render: (r) => hours(r.busy) },
        { label: 'Utilization', key: 'utilization', render: (r) => pct(r.utilization) },
        { label: 'Of 24 h', key: 'capacity', render: (r) => pct(r.capacity) },
        { label: 'OEE', key: 'oee', render: (r) => <strong>{pct(r.oee)}</strong> },
        {
          label: 'A · P · Q',
          render: (r) => `${pct(r.availability, 0)} · ${pct(r.performance, 0)} · ${pct(r.quality, 0)}`,
        },
        { label: 'Days measured', key: 'measured' },
        { label: 'Output timed', key: 'coverage', render: (r) => pct(r.coverage, 0) },
        { label: 'Calendar', key: 'calendar' },
      ]}
    />
  );
}

/** Data-coverage and data-quality warnings, newest day first; each opens its work-center day. */
export function Warnings({
  data,
  onOpen,
  limit = 30,
}: {
  data: OeeResponse;
  onOpen: (machineId: string, day: string) => void;
  limit?: number;
}) {
  const counts = Object.entries(data.coverage.warningCounts);
  if (!data.warnings.length)
    return <p className="perf-note">No data-coverage or data-quality warnings in this range.</p>;
  const shown: Warning[] = data.warnings.slice(0, limit);
  return (
    <>
      <div className="perf-counts" aria-label="Warnings by kind">
        {counts.map(([code, n]) => (
          <span key={code}>
            {warningLabel(code)} · {fmt(n)}
          </span>
        ))}
      </div>
      <ul className="perf-warnings">
        {shown.map((w, i) => (
          <li key={`${w.machineId}-${w.day}-${w.code}-${i}`} className={w.severity}>
            {w.severity === 'warning' ? <AlertTriangle size={15} /> : <Info size={15} />}
            <div>
              <strong>
                {w.machine} · {date(w.day)} · {warningLabel(w.code)}
              </strong>
              <p>
                {w.message}
                {w.minutes != null ? ` (${hours(w.minutes)})` : ''}
                {w.quantity != null ? ` (${fmt(w.quantity)} units)` : ''}
              </p>
            </div>
            {w.machineId && w.day && (
              <button
                className="text-button"
                onClick={() => onOpen(w.machineId!, w.day!)}
                aria-label={`Open ${w.machine} on ${date(w.day)}`}
              >
                Open day
                <ArrowRight size={12} />
              </button>
            )}
          </li>
        ))}
      </ul>
      {data.warningTotal > shown.length && (
        <p className="perf-note">
          Showing {fmt(shown.length)} of {fmt(data.warningTotal)} warnings. Narrow the range or pick a work
          center to see the rest.
        </p>
      )}
    </>
  );
}
