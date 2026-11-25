import { useState } from 'react';
import { Plus } from 'lucide-react';
import { useStateData, useWorkspace } from '../../app/workspace';
import { Table } from '../../components';
import { date, fmt, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { QueryState } from '../inventory/shared';
import {
  colomboDay,
  duration,
  MAINTENANCE,
  minutesSince,
  PLANNERS,
  REASON_LABELS,
  REASONS,
  reasonLabel,
  REPORTERS,
  useMetrics,
  when,
} from './api';
import { closeDowntime, correctDowntime, reportDowntime } from './forms';
import { ReasonChip, StatusChip } from './shared';

const PERIODS = [
  { value: '7', label: '7 days' },
  { value: '30', label: '30 days' },
  { value: '90', label: '90 days' },
];

/** Downtime: reason Pareto and machine reliability (MTBF/MTTR) for a period, and the paged downtime log. */
export function DowntimeTab({ onOpenOrder }: { onOpenOrder: (id: string) => void }) {
  const workspace = useWorkspace();
  const data = useStateData();
  const [period, setPeriod] = useState('30');
  const [machine, setMachine] = useState('');
  const [reason, setReason] = useState('');
  const [status, setStatus] = useState('');
  const [history, setHistory] = useState(false);
  const from = colomboDay(1 - Number(period));
  const to = colomboDay();
  const metrics = useMetrics(from, to, machine);
  const paged = useCursorPage(
    [MAINTENANCE, 'downtime'],
    '/maintenance/downtime',
    {
      machine,
      reason,
      status,
      from: status === 'OPEN' ? undefined : from,
      to: status === 'OPEN' ? undefined : to,
      history: history ? 'true' : undefined,
    },
    25,
  );
  const pareto = metrics.data?.pareto || [];
  const top = Math.max(...pareto.map((p) => num(p.minutes)), 1);
  const totals = metrics.data?.totals;
  return (
    <>
      <Tabs compact label="Downtime period" items={PERIODS} value={period} onChange={setPeriod}>
        <span className="grow" />
        <select
          className="mnt-select"
          aria-label="Work center"
          value={machine}
          onChange={(e) => setMachine(e.target.value)}
        >
          <option value="">All work centers</option>
          {data.machines.map((m) => (
            <option key={m.id} value={m.id}>
              {text(m.code)}
            </option>
          ))}
        </select>
      </Tabs>
      <QueryState pending={metrics.isPending} error={metrics.error} retry={() => metrics.refetch()}>
        <div className="mnt-analysis">
          <section className="mnt-pareto" aria-labelledby="mnt-pareto-title">
            <header>
              <h3 id="mnt-pareto-title">Downtime by reason</h3>
              <p>
                {fmt(totals?.downtimeMinutes)} minutes · {fmt(totals?.events)} events · {date(from)} – {date(to)}
              </p>
            </header>
            {pareto.length ? (
              <ol>
                {pareto.map((p) => (
                  <li key={p.reason}>
                    <span className="mnt-pareto-label">
                      <ReasonChip reason={p.reason} />
                    </span>
                    <span className="mnt-bar" aria-hidden="true">
                      <i className={text(p.reason).toLowerCase()} style={{ width: `${(num(p.minutes) / top) * 100}%` }} />
                    </span>
                    <span className="mnt-pareto-value">
                      {duration(p.minutes)}
                      <small>
                        {fmt(p.share, 1)}% · cum. {fmt(p.cumulative, 1)}%
                      </small>
                    </span>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="muted inv-small mnt-pad">No downtime recorded in this period.</p>
            )}
          </section>
          <section className="mnt-reliability" aria-labelledby="mnt-reliability-title">
            <header>
              <h3 id="mnt-reliability-title">Reliability</h3>
              <p>
                MTBF {totals?.mtbfHours == null ? '—' : `${fmt(totals.mtbfHours, 1)} h`} · MTTR{' '}
                {totals?.mttrMinutes == null ? '—' : duration(totals.mttrMinutes)} · {fmt(totals?.failures)} failures
              </p>
            </header>
            <Table
              compact
              label="Reliability per work center"
              pageSize={Math.max(metrics.data?.machines.length || 1, 1)}
              rows={metrics.data?.machines || []}
              columns={[
                { label: 'Work center', key: 'code' },
                { label: 'Failures', key: 'failures' },
                { label: 'MTBF', render: (r) => (r.mtbfHours == null ? '—' : `${fmt(r.mtbfHours, 1)} h`) },
                { label: 'MTTR', render: (r) => (r.mttrMinutes == null ? '—' : duration(r.mttrMinutes)) },
                {
                  label: 'Downtime',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>{duration(r.downtimeMinutes)}</strong>
                      <span>
                        {duration(r.unplannedMinutes)} unplanned
                      </span>
                    </div>
                  ),
                },
              ]}
            />
            <p className="muted inv-small mnt-pad">
              Failures are breakdowns that started in the period. MTBF uses calendar time less recorded downtime (no
              shift calendar yet); MTTR is the mean length of closed breakdowns.
            </p>
          </section>
        </div>
      </QueryState>
      <div className="inv-toolbar">
        <div className="inv-filters">
          <select aria-label="Reason" value={reason} onChange={(e) => setReason(e.target.value)}>
            <option value="">Any reason</option>
            {REASONS.map((r) => (
              <option key={r} value={r}>
                {REASON_LABELS[r]}
              </option>
            ))}
          </select>
          <select aria-label="Downtime status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Open and closed</option>
            <option value="OPEN">Open now</option>
            <option value="CLOSED">Closed</option>
          </select>
          <label className="inv-check">
            <input type="checkbox" checked={history} onChange={(e) => setHistory(e.target.checked)} />
            Show corrected events
          </label>
        </div>
        {workspace.can(...REPORTERS) && (
          <div className="inv-actions">
            <button className="button primary small" onClick={() => reportDowntime(workspace, machine || undefined)}>
              <Plus size={14} />
              Report downtime
            </button>
          </div>
        )}
      </div>
      <PageState paged={paged}>
        <Table
          rows={paged.rows}
          compact
          label="Downtime log"
          pageSize={Math.max(paged.rows.length, 1)}
          columns={[
            {
              label: 'Event',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.number)}</strong>
                  <span>
                    {text(r.machine)} · {text(r.reported_by_name)}
                  </span>
                </div>
              ),
            },
            { label: 'Reason', render: (r) => <ReasonChip reason={r.reason} planned={r.planned} /> },
            {
              label: 'Time',
              render: (r) => (
                <div className="cell-stack">
                  <span>{when(r.started_at)}</span>
                  <span>{r.ended_at ? `to ${when(r.ended_at)}` : 'still down'}</span>
                </div>
              ),
            },
            {
              label: 'Duration',
              render: (r) => duration(r.status === 'OPEN' ? minutesSince(r.started_at) : r.minutes),
            },
            {
              label: 'Status',
              render: (r) => (
                <div className="cell-stack">
                  <StatusChip value={r.status} />
                  {r.corrects && <span>corrects {text(r.corrects)}</span>}
                  {r.corrected_by && <span>see {text(r.corrected_by)}</span>}
                </div>
              ),
            },
            {
              label: 'Link',
              render: (r) =>
                r.work_order_id ? (
                  <button className="text-button" onClick={() => onOpenOrder(text(r.work_order_id))}>
                    {text(r.work_order)}
                  </button>
                ) : (
                  text(r.manufacturing_order) || text(r.notes) || '—'
                ),
            },
            {
              label: '',
              render: (r) =>
                r.status === 'OPEN' && workspace.can(...REPORTERS) && r.work_order_status !== 'IN_PROGRESS' ? (
                  <button className="button secondary small" onClick={() => closeDowntime(workspace, r)}>
                    Close
                  </button>
                ) : r.status === 'CLOSED' && workspace.can(...PLANNERS) ? (
                  <button
                    className="text-button"
                    aria-label={`Correct ${text(r.number)} (${reasonLabel(r.reason)})`}
                    onClick={() => correctDowntime(workspace, r)}
                  >
                    Correct
                  </button>
                ) : null,
            },
          ]}
        />
        <PageFooter paged={paged} label="downtime events" />
      </PageState>
    </>
  );
}
