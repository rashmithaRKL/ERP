import { Empty } from '../../components';
import { text } from '../../lib/format';
import { dayLabel, duration } from './api';
import type { CapacityData, CapacityDay } from './api';

function level(d: CapacityDay) {
  if (d.overloaded) return 'over';
  if (!d.available_minutes) return d.closed ? 'closed' : 'none';
  if (d.load_pct >= 90) return 'full';
  if (d.load_pct >= 60) return 'busy';
  if (d.load_pct > 0) return 'light';
  return 'idle';
}

const describe = (code: string, d: CapacityDay) =>
  `${code} ${dayLabel(d.date)}: ${duration(d.scheduled_minutes)} scheduled of ${duration(d.available_minutes)} available` +
  (d.blocked_minutes ? `, ${duration(d.blocked_minutes)} blocked` : '') +
  (d.closed ? ` (closed: ${d.closed_reason})` : '') +
  (d.overloaded ? ', overloaded' : '');

/**
 * Capacity load per work center and day: scheduled minutes of the run against available minutes (calendar minus
 * maintenance and downtime the run avoided). A day turns red when the scheduled work no longer fits, which happens
 * only when working time was removed after the run (re-run to fix it).
 */
export function CapacityHeatmap({ data }: { data: CapacityData }) {
  const days = data.machines[0]?.days || [];
  if (!data.machines.length)
    return <Empty title="No work centers" body="Register work centers to plan capacity." />;
  return (
    <>
      <div
        className="sched-heat-scroll"
        tabIndex={0}
        role="region"
        aria-label="Capacity load by work center and day"
      >
        <table className="sched-heat">
          <thead>
            <tr>
              <th scope="col">Work center</th>
              {days.map((d) => (
                <th scope="col" key={d.date} className={d.closed ? 'closed' : undefined}>
                  {dayLabel(d.date)}
                </th>
              ))}
              <th scope="col">Load</th>
            </tr>
          </thead>
          <tbody>
            {data.machines.map((m) => (
              <tr key={m.id}>
                <th scope="row">{text(m.code)}</th>
                {m.days.map((d) => (
                  <td key={d.date} className={`heat-${level(d)}`} title={describe(text(m.code), d)}>
                    <span className="sr-only">{describe(text(m.code), d)}</span>
                    <span aria-hidden="true">
                      {d.available_minutes || d.scheduled_minutes ? `${Math.min(d.load_pct, 999)}%` : '–'}
                    </span>
                  </td>
                ))}
                <td className="heat-total">{`${text(m.load_pct)}%`}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="sched-legend" aria-hidden="true">
        <span>
          <i className="heat-light" />
          Under 60%
        </span>
        <span>
          <i className="heat-busy" />
          60–89%
        </span>
        <span>
          <i className="heat-full" />
          90% and more
        </span>
        <span>
          <i className="heat-over" />
          Overloaded
        </span>
        <span>
          <i className="heat-closed" />
          Not working
        </span>
      </div>
    </>
  );
}
