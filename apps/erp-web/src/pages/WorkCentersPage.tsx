import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AnimatePresence } from 'motion/react';
import { Activity, ArrowRight, CalendarClock, Cpu, Siren, Wrench } from 'lucide-react';
import { useStateData, useWorkspace } from '../app/workspace';
import { Badge, Panel } from '../components';
import { MachineDrawer } from '../features/machines/MachineDrawer';
import {
  duration,
  minutesSince,
  reasonLabel,
  useMachines,
  useMaintenanceSync,
} from '../features/maintenance/api';
import { WorkOrderDrawer } from '../features/maintenance/WorkOrderDrawer';
import { date, fmt, money, num, text } from '../lib/format';

/** Route `/machines`: registered work centers, their recorded states and maintenance situation. */
export default function WorkCentersPage() {
  useMaintenanceSync();
  const navigate = useNavigate();
  const { setSearch } = useWorkspace();
  const data = useStateData();
  const maintenance = useMachines();
  const [open, setOpen] = useState<string>();
  const [order, setOrder] = useState<string>();
  const byId = new Map((maintenance.data?.items || []).map((m) => [m.id, m]));
  return (
    <>
      <div className="info-banner">
        <Activity size={20} />
        <div>
          <strong>Recorded machine states</strong>
          <p>
            States are recorded by people and by maintenance work orders: starting maintenance takes a machine
            out of production and completing it returns the machine to idle. Open a work center for its
            downtime, maintenance and MTBF/MTTR. Automated telemetry and validated OEE are scheduled for a
            later phase.
          </p>
        </div>
      </div>
      <div className="machine-grid">
        {data.machines.map((m) => {
          const info = byId.get(m.id);
          const down = !!info?.open_downtime;
          return (
            <Panel key={m.id} className="machine-card">
              <div className="machine-card-top">
                <span className={`large-machine-icon ${down ? 'mnt-down-icon' : ''}`}>
                  {down ? <Siren size={25} /> : <Cpu size={25} />}
                </span>
                <Badge value={m.status} />
              </div>
              <h2>{text(m.code)}</h2>
              <p>{text(m.name)}</p>
              {down && (
                <p className="mnt-card-alert" role="status">
                  Down {duration(minutesSince(info?.down_since))} · {reasonLabel(info?.open_downtime_reason)}
                </p>
              )}
              <div className="machine-card-meta">
                <span>
                  Process<strong>{text(m.kind)}</strong>
                </span>
                <span>
                  Recorded output<strong>{fmt(m.output)} pcs</strong>
                </span>
                <span>
                  Machine rate<strong>{money(m.hourly_rate)} / hr</strong>
                </span>
                <span>
                  <span className="mnt-meta-label">
                    <Wrench size={12} /> Maintenance
                  </span>
                  <strong>
                    {info?.in_progress_order
                      ? `${text(info.in_progress_order)} in progress`
                      : num(info?.open_orders)
                        ? `${fmt(info?.open_orders)} open ${num(info?.open_orders) === 1 ? 'order' : 'orders'}`
                        : 'None open'}
                  </strong>
                </span>
                <span>
                  <span className="mnt-meta-label">
                    <CalendarClock size={12} /> Next preventive
                  </span>
                  <strong className={num(info?.overdue_plans) ? 'mnt-overdue' : undefined}>
                    {info?.next_pm_due
                      ? `${date(info.next_pm_due)}${num(info.overdue_plans) ? ' · overdue' : ''}`
                      : 'No plan'}
                  </strong>
                </span>
              </div>
              <div className="mnt-card-actions">
                <button className="text-button" onClick={() => setOpen(m.id)}>
                  Maintenance & history
                  <ArrowRight size={14} />
                </button>
                <button
                  className="text-button"
                  onClick={() => {
                    navigate('/manufacturing');
                    setSearch(text(m.code));
                  }}
                >
                  View assigned work
                  <ArrowRight size={14} />
                </button>
              </div>
            </Panel>
          );
        })}
      </div>
      <AnimatePresence>
        {open && (
          <MachineDrawer
            key={open}
            id={open}
            onClose={() => setOpen(undefined)}
            onOpenOrder={(id) => setOrder(id)}
          />
        )}
        {order && <WorkOrderDrawer key={`wo-${order}`} id={order} onClose={() => setOrder(undefined)} />}
      </AnimatePresence>
    </>
  );
}
