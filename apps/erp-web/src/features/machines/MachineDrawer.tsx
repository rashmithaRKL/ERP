import { useQuery } from '@tanstack/react-query';
import { CircleAlert, Gauge, History, ListChecks, Siren, Timer, Wrench } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, fmt, humanizeText, text } from '../../lib/format';
import { QueryState } from '../inventory/shared';
import {
  duration,
  MAINTENANCE,
  METER_READERS,
  minutesSince,
  PLANNERS,
  reasonLabel,
  REPORTERS,
  statusText,
  when,
} from '../maintenance/api';
import type { MachineDetail } from '../maintenance/api';
import {
  closeDowntime,
  correctMachineStatus,
  recordMeter,
  reportDowntime,
  requestMaintenance,
} from '../maintenance/forms';
import { ReasonChip, StatusChip, TypeChip } from '../maintenance/shared';

const KIND_ICON = { DOWNTIME: Timer, WORK_ORDER: Wrench, STATUS: Gauge, METER: History } as const;

function historyTitle(r: Row) {
  switch (r.kind) {
    case 'DOWNTIME':
      return `${reasonLabel(r.code)} ${r.flag ? '(planned)' : '(unplanned)'} · ${text(r.reference)}`;
    case 'WORK_ORDER':
      return `${r.code === 'PREVENTIVE' ? 'Preventive' : 'Corrective'} ${text(r.reference)} · ${statusText(r.status).toLowerCase()}`;
    case 'STATUS':
      return `${statusText(r.status)} → ${statusText(r.code)}${r.flag ? ' (manual correction)' : ''}`;
    default:
      return `Meter ${fmt(r.value, 2)} run hours`;
  }
}

function historyDetail(r: Row) {
  if (r.kind === 'DOWNTIME')
    return `${r.status === 'OPEN' ? 'Open for' : 'Lasted'} ${duration(r.value)}${
      ['SUPERSEDED', 'VOID'].includes(text(r.status)) ? ` · ${statusText(r.status).toLowerCase()}` : ''
    }${text(r.detail) ? ` · ${text(r.detail)}` : ''}`;
  if (r.kind === 'STATUS') return [text(r.reference), text(r.detail)].filter(Boolean).join(' · ');
  return text(r.detail);
}

/**
 * A work center's maintenance view: status, open downtime, open and scheduled work orders, preventive plans, run hours,
 * MTBF/MTTR for the last 30 days and recent history, with report-breakdown, downtime, meter and status-correction
 * actions.
 */
export function MachineDrawer({
  id,
  onClose,
  onOpenOrder,
}: {
  id: string;
  onClose: () => void;
  onOpenOrder: (id: string) => void;
}) {
  const workspace = useWorkspace();
  const { can } = workspace;
  const detail = useQuery({
    queryKey: [MAINTENANCE, 'machine', id],
    queryFn: () => request<MachineDetail>(`/maintenance/machines/${id}`),
  });
  const d = detail.data;
  const machine = d?.machine;
  const down = d?.openDowntime;
  const inProgress = d?.orders.find((o) => o.status === 'IN_PROGRESS');
  const metrics = d?.metrics || {};
  return (
    <Overlay
      title={machine ? `${text(machine.code)} · ${text(machine.name)}` : 'Work center'}
      subtitle="Status, downtime, maintenance and reliability"
      onClose={onClose}
      drawer
      wide
    >
      <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
        {d && machine && (
          <>
            <div className="drawer-body">
              <div className="detail-product">
                <div>
                  <h2>{text(machine.name)}</h2>
                  <p>
                    {text(machine.kind)} · Rs. {fmt(machine.hourly_rate, 2)} / h
                    {d.meter ? ` · ${fmt(d.meter.reading, 2)} run hours` : ''}
                  </p>
                </div>
                <StatusChip value={machine.status} />
              </div>
              {down && (
                <div className="mnt-down-banner" role="status">
                  <Siren size={18} />
                  <div>
                    <strong>
                      Down for {duration(minutesSince(down.started_at))} · {reasonLabel(down.reason)}
                    </strong>
                    <p>
                      {text(down.number)} since {when(down.started_at)}
                      {down.work_order
                        ? ` · ${text(down.work_order)} (${statusText(down.work_order_status).toLowerCase()})`
                        : ''}
                      {text(down.notes) ? ` · ${text(down.notes)}` : ''}
                    </p>
                  </div>
                  {can(...REPORTERS) && down.work_order_status !== 'IN_PROGRESS' && (
                    <button className="button secondary small" onClick={() => closeDowntime(workspace, down)}>
                      Close downtime
                    </button>
                  )}
                </div>
              )}
              {d.runningOrders.length > 0 && (
                <p className="inv-small muted mnt-inline-note">
                  <CircleAlert size={14} /> Production running: {d.runningOrders.join(', ')}. Maintenance can
                  start only after a planner pauses it.
                </p>
              )}
              <div className="detail-stats">
                <div>
                  <span>MTBF · 30 days</span>
                  <strong>{metrics.mtbfHours == null ? '—' : `${fmt(metrics.mtbfHours, 1)} h`}</strong>
                </div>
                <div>
                  <span>MTTR · 30 days</span>
                  <strong>{metrics.mttrMinutes == null ? '—' : duration(metrics.mttrMinutes)}</strong>
                </div>
                <div>
                  <span>Failures</span>
                  <strong>{fmt(metrics.failures)}</strong>
                </div>
                <div>
                  <span>Downtime</span>
                  <strong>{duration(metrics.downtimeMinutes)}</strong>
                </div>
              </div>

              <h3 className="mnt-section">
                <Wrench size={15} /> Open and scheduled work
              </h3>
              {d.orders.length ? (
                <Table
                  compact
                  label="Open maintenance work orders"
                  pageSize={Math.max(d.orders.length, 1)}
                  rows={d.orders}
                  onRow={(r) => onOpenOrder(r.id)}
                  columns={[
                    {
                      label: 'Work order',
                      render: (r) => (
                        <div className="cell-stack">
                          <strong>{text(r.number)}</strong>
                          <span>{text(r.title)}</span>
                        </div>
                      ),
                    },
                    { label: 'Type', render: (r) => <TypeChip type={r.type} /> },
                    { label: 'Status', render: (r) => <StatusChip value={r.status} /> },
                    {
                      label: 'Window',
                      render: (r) =>
                        r.scheduled_start ? `${when(r.scheduled_start)} – ${when(r.scheduled_end)}` : '—',
                    },
                  ]}
                />
              ) : (
                <p className="muted inv-small">No open maintenance work.</p>
              )}

              <h3 className="mnt-section">
                <ListChecks size={15} /> Preventive plans
              </h3>
              {d.plans.length ? (
                <ul className="mnt-plan-list">
                  {d.plans.map((p) => (
                    <li key={text(p.id)}>
                      <div>
                        <strong>{text(p.title)}</strong>
                        <span>
                          {text(p.number)} · every {fmt(p.interval_days)} days
                          {p.interval_hours ? ` or ${fmt(p.interval_hours, 2)} run h` : ''}
                        </span>
                      </div>
                      <div className="mnt-plan-due">
                        <StatusChip value={p.due_state} />
                        <span>{date(p.next_due_date)}</span>
                      </div>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="muted inv-small">No preventive plan for this work center.</p>
              )}

              <h3 className="mnt-section">
                <History size={15} /> Recent history
              </h3>
              {d.history.length ? (
                <ol className="mnt-history">
                  {d.history.map((r) => {
                    const Icon = KIND_ICON[text(r.kind) as keyof typeof KIND_ICON] || History;
                    return (
                      <li key={r.id}>
                        <span className={`mnt-history-icon ${text(r.kind).toLowerCase()}`} aria-hidden="true">
                          <Icon size={14} />
                        </span>
                        <div>
                          <strong>{historyTitle(r)}</strong>
                          {historyDetail(r) && <p>{humanizeText(historyDetail(r))}</p>}
                          <small>
                            {when(r.at)}
                            {r.actor ? ` · ${text(r.actor)}` : ''}
                          </small>
                        </div>
                        {r.kind === 'DOWNTIME' && <ReasonChip reason={r.code} />}
                      </li>
                    );
                  })}
                </ol>
              ) : (
                <p className="muted inv-small">No maintenance history yet.</p>
              )}
            </div>
            <div className="dialog-footer detail-footer mnt-footer">
              {can(...PLANNERS) && !inProgress && (
                <button className="button secondary" onClick={() => correctMachineStatus(workspace, machine)}>
                  Correct status
                </button>
              )}
              {can(...METER_READERS) && (
                <button
                  className="button secondary"
                  onClick={() => recordMeter(workspace, machine, d.meter?.reading)}
                >
                  Meter reading
                </button>
              )}
              <span className="grow" />
              {can(...REPORTERS) && (
                <>
                  <button className="button secondary" onClick={() => reportDowntime(workspace, id)}>
                    Report downtime
                  </button>
                  {!down && (
                    <button
                      className="button primary"
                      onClick={() => requestMaintenance(workspace, id, true)}
                    >
                      <Siren size={15} />
                      Report breakdown
                    </button>
                  )}
                  {down && !down.work_order && (
                    <button
                      className="button primary"
                      onClick={() => requestMaintenance(workspace, id, false, down)}
                    >
                      <Wrench size={15} />
                      Request repair
                    </button>
                  )}
                </>
              )}
            </div>
          </>
        )}
      </QueryState>
    </Overlay>
  );
}
