import { AlertTriangle, CalendarClock, History, Lock, Timer } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import type { Row } from '../../lib/api';
import { date, fmt, text } from '../../lib/format';
import { QueryState } from '../inventory/shared';
import { reasonLabel } from '../maintenance/api';
import { closeDay, CLOSERS, useDay } from './api';
import type { DayFigures } from './api';
import { hours, pct, statusReason, statusText, warningLabel } from './format';
import './performance.css';

const clock = (iso: string) =>
  new Date(iso).toLocaleTimeString('en-GB', { timeZone: 'Asia/Colombo', hour: '2-digit', minute: '2-digit' });

/** Planned production time split into run, setup, breakdowns, other stops and unrecorded idle. */
function TimeBar({ f }: { f: DayFigures }) {
  const planned = f.plannedMinutes || 0;
  if (planned <= 0) return null;
  const parts = [
    { key: 'run', label: 'Run', minutes: f.runMinutes || 0 },
    {
      key: 'setup',
      label: 'Setup and changeover',
      minutes: (f.setupMinutes || 0) + (f.changeoverMinutes || 0),
    },
    { key: 'breakdown', label: 'Breakdowns', minutes: f.breakdownMinutes || 0 },
    { key: 'other', label: 'Other stops', minutes: f.otherStopMinutes || 0 },
    { key: 'idle', label: 'Unrecorded idle', minutes: f.unrecordedMinutes || 0 },
  ];
  return (
    <>
      <div
        className="perf-timebar"
        role="img"
        aria-label={`Planned production time ${hours(planned)}: ${parts.map((p) => `${p.label} ${hours(p.minutes)}`).join(', ')}`}
      >
        {parts.map((p) => (
          <i
            key={p.key}
            className={p.key}
            style={{ width: `${(p.minutes / planned) * 100}%` }}
            title={p.label}
          />
        ))}
      </div>
      <div className="perf-legend" aria-hidden="true">
        {parts.map((p) => (
          <span key={p.key}>
            <i className={`perf-timebar-key ${p.key}`} />
            {p.label} {hours(p.minutes)}
          </span>
        ))}
      </div>
    </>
  );
}

function Figures({ f }: { f: DayFigures }) {
  return (
    <>
      <div className="detail-stats">
        <div>
          <span>OEE</span>
          <strong>{f.oee == null ? '—' : pct(f.oee)}</strong>
        </div>
        <div>
          <span>Availability</span>
          <strong>{pct(f.availability)}</strong>
        </div>
        <div>
          <span>Performance</span>
          <strong>{pct(f.performance)}</strong>
        </div>
        <div>
          <span>Quality</span>
          <strong>{pct(f.quality)}</strong>
        </div>
      </div>
      {f.oee == null && <p className="perf-note">{statusReason(f.status)}.</p>}
      {f.performanceRaw != null && f.performanceRaw > 1 && (
        <p className="perf-note">
          Measured performance {pct(f.performanceRaw)} is capped at 100 % in the OEE.
        </p>
      )}
      <TimeBar f={f} />
      <dl className="perf-facts">
        <div>
          <dt>Shift time</dt>
          <dd>
            {hours(f.scheduledMinutes)}
            <small>
              {f.calendarSource === 'NONE'
                ? 'no calendar: 24 h'
                : `${text(f.calendarCode)} · ${f.calendarSource === 'ASSIGNED' ? 'assigned' : 'plant default'}`}
            </small>
          </dd>
        </div>
        <div>
          <dt>Planned stops</dt>
          <dd>{hours(f.plannedStopMinutes)}</dd>
        </div>
        <div>
          <dt>Planned production time</dt>
          <dd>{hours(f.plannedMinutes)}</dd>
        </div>
        <div>
          <dt>Run time (time logs)</dt>
          <dd>
            {hours(f.runMinutes)}
            <small>stops-based estimate {hours(f.derivedRunMinutes)}</small>
          </dd>
        </div>
        <div>
          <dt>Good / scrap reported</dt>
          <dd>
            {fmt(f.goodCount)} / {fmt(f.scrapCount)}
          </dd>
        </div>
        <div>
          <dt>Untimed output (no routing)</dt>
          <dd>{fmt((f.untimedGood || 0) + (f.untimedScrap || 0))}</dd>
        </div>
        <div>
          <dt>Utilization</dt>
          <dd>
            {pct(f.utilization)}
            <small>{pct(f.capacityUtilization)} of the day</small>
          </dd>
        </div>
        <div>
          <dt>Run outside planned time</dt>
          <dd>{hours(f.runOutsideMinutes)}</dd>
        </div>
      </dl>
    </>
  );
}

/**
 * One work center on one business day: the figures (the frozen snapshot of a closed day, else live), working
 * intervals, downtime, operations, warnings and the snapshot history, with close / recalculate for planners.
 */
export function DayDrawer({
  machineId,
  day,
  onClose,
}: {
  machineId: string;
  day: string;
  onClose: () => void;
}) {
  const workspace = useWorkspace();
  const detail = useDay(machineId, day);
  const d = detail.data;
  const shown = d?.snapshot || d?.live;
  const canClose = workspace.can(...CLOSERS);
  return (
    <Overlay
      title={d ? `${d.machine.code} · ${date(day)}` : 'Work-center day'}
      subtitle="OEE, losses and data coverage of one business day (Asia/Colombo)"
      onClose={onClose}
      drawer
      wide
    >
      <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
        {d && shown && (
          <div className="drawer-body">
            <div className="detail-product">
              <div>
                <h2>{d.machine.name}</h2>
                <p>
                  {statusText(shown.status)}
                  {d.snapshot
                    ? ` · closed${d.snapshot.version && d.snapshot.version > 1 ? ` (version ${d.snapshot.version})` : ''} by ${text(d.snapshot.closedBy)}`
                    : d.date === d.today
                      ? ' · today so far, open'
                      : ' · open (live figures)'}
                </p>
              </div>
              <span className={`badge ${d.snapshot ? 'green' : 'blue'}`}>
                <i />
                {d.snapshot ? 'closed' : 'live'}
              </span>
            </div>
            {d.snapshot && d.calendarChanged && (
              <div className="perf-banner" role="status">
                <CalendarClock size={16} />
                <div>
                  <strong>The shift calendar has changed since this day was closed.</strong>
                  The frozen figures are shown (planned {hours(d.snapshot.plannedMinutes)}; the current
                  calendar gives {hours(d.live.plannedMinutes)}). Recalculate the day to apply it.
                </div>
              </div>
            )}
            {d.snapshot && !d.calendarChanged && d.differs && (
              <div className="perf-banner" role="status">
                <History size={16} />
                <div>
                  <strong>Records changed after closing.</strong>
                  Live figures now give OEE {pct(d.live.oee)} (closed: {pct(d.snapshot.oee)}), for example a
                  late downtime report or correction. Recalculate the day to record a new version.
                </div>
              </div>
            )}
            <Figures f={shown} />

            <h3 className="mnt-section">
              <Timer size={15} /> Working time
            </h3>
            {shown.intervals.length ? (
              <div className="perf-intervals">
                {shown.intervals.map((i) => (
                  <span key={i.start}>
                    {clock(i.start)}–{clock(i.end)} · {i.label}
                  </span>
                ))}
              </div>
            ) : (
              <p className="perf-note">No shift starts or runs into this day.</p>
            )}

            {shown.stops.length > 0 && (
              <>
                <h3 className="mnt-section">
                  <AlertTriangle size={15} /> Downtime in shift time
                </h3>
                <Table
                  compact
                  label="Downtime in shift time"
                  pageSize={Math.max(shown.stops.length, 1)}
                  rows={shown.stops.map((s): Row => ({
                    id: s.reason,
                    reason: reasonLabel(s.reason),
                    minutes: s.minutes,
                    planned: s.plannedMinutes > 0 ? 'planned' : 'unplanned',
                    events: s.events.join(', '),
                  }))}
                  columns={[
                    { label: 'Reason', key: 'reason' },
                    { label: 'Time', key: 'minutes', render: (r) => hours(r.minutes) },
                    { label: 'Kind', key: 'planned' },
                    { label: 'Events', key: 'events' },
                  ]}
                />
              </>
            )}

            {shown.operations.length > 0 && (
              <>
                <h3 className="mnt-section">Operations</h3>
                <Table
                  compact
                  label="Operations on this day"
                  pageSize={Math.max(shown.operations.length, 1)}
                  rows={shown.operations.map((o): Row => ({
                    id: o.operationId,
                    order: `${o.orderNumber} · op ${o.seq}`,
                    name: o.name,
                    ideal: o.idealMinutesPerUnit,
                    run: o.runMinutes,
                    setup: o.setupMinutes,
                    good: o.goodCount,
                    scrap: o.scrapCount,
                    performance: o.performance,
                  }))}
                  columns={[
                    {
                      label: 'Operation',
                      render: (r) => (
                        <div className="cell-stack">
                          <strong>{text(r.order)}</strong>
                          <span>{text(r.name)}</span>
                        </div>
                      ),
                    },
                    { label: 'Ideal min/unit', key: 'ideal', render: (r) => fmt(r.ideal, 4) },
                    { label: 'Run', key: 'run', render: (r) => hours(r.run) },
                    { label: 'Setup', key: 'setup', render: (r) => hours(r.setup) },
                    { label: 'Good', key: 'good', render: (r) => fmt(r.good) },
                    { label: 'Scrap', key: 'scrap', render: (r) => fmt(r.scrap) },
                    { label: 'Performance', key: 'performance', render: (r) => pct(r.performance) },
                  ]}
                />
              </>
            )}

            {(shown.warnings || []).length > 0 && (
              <>
                <h3 className="mnt-section">
                  <AlertTriangle size={15} /> Data coverage and quality
                </h3>
                <ul className="perf-warnings">
                  {(shown.warnings || []).map((w) => (
                    <li key={w.code} className={w.severity}>
                      <AlertTriangle size={15} />
                      <div>
                        <strong>{warningLabel(w.code)}</strong>
                        <p>
                          {w.message}
                          {w.minutes != null ? ` (${hours(w.minutes)})` : ''}
                          {w.quantity != null ? ` (${fmt(w.quantity)} units)` : ''}
                        </p>
                      </div>
                      <span />
                    </li>
                  ))}
                </ul>
              </>
            )}

            {d.history.length > 0 && (
              <>
                <h3 className="mnt-section">
                  <Lock size={15} /> Closing history
                </h3>
                <Table
                  compact
                  label="Snapshot versions"
                  pageSize={Math.max(d.history.length, 1)}
                  rows={d.history.map((h): Row => ({
                    id: h.id,
                    version: h.version,
                    status: h.status === 'CURRENT' ? 'current' : 'superseded',
                    oee: h.oee,
                    planned: h.planned_minutes,
                    by: h.closed_by,
                    at: h.closed_at,
                    reason: h.reason || '—',
                  }))}
                  columns={[
                    { label: 'Version', key: 'version' },
                    { label: 'Status', key: 'status' },
                    { label: 'OEE', key: 'oee', render: (r) => pct(r.oee) },
                    { label: 'Planned', key: 'planned', render: (r) => hours(r.planned) },
                    {
                      label: 'Closed',
                      render: (r) => (
                        <div className="cell-stack">
                          <strong>{text(r.by)}</strong>
                          <span>
                            {new Date(text(r.at)).toLocaleString('en-GB', { timeZone: 'Asia/Colombo' })}
                          </span>
                        </div>
                      ),
                    },
                    { label: 'Reason', key: 'reason' },
                  ]}
                />
              </>
            )}

            {canClose && d.date < d.today && (
              <div className="perf-actions">
                {d.snapshot ? (
                  <button
                    className="button secondary small"
                    onClick={() => closeDay(workspace, { date: d.date, machineId, recalculate: true })}
                  >
                    Recalculate this day
                  </button>
                ) : (
                  <button
                    className="button small"
                    onClick={() => closeDay(workspace, { date: d.date, machineId })}
                  >
                    Close this day
                  </button>
                )}
              </div>
            )}
          </div>
        )}
      </QueryState>
    </Overlay>
  );
}
