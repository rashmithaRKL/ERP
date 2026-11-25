import { Link } from 'react-router-dom';
import { CalendarRange, Pin } from 'lucide-react';
import { text } from '../../lib/format';
import { duration, useNextOperations, when } from './api';
import './schedule.css';

/**
 * Shop floor: the next operations of a work center by the published schedule (scheduled start and length), so the
 * operator sees what comes next and when. Renders nothing until a schedule has been published.
 */
export function NextOperations({ machineId }: { machineId: string }) {
  const next = useNextOperations(machineId);
  const d = next.data;
  if (next.isPending || next.isError || !d?.run) return null;
  return (
    <section className="sched-next" aria-label="Published schedule for this work center">
      <div className="sched-next-head">
        <CalendarRange size={15} />
        <strong>Published schedule</strong>
        <span className="muted">
          {text(d.run.number)} · published {when(d.run.published_at)}
        </span>
        <Link to="/schedule" className="sched-next-link">
          Open schedule
        </Link>
      </div>
      {!d.items.length ? (
        <p className="muted">Nothing is scheduled on {text(d.machine.code)} from now on.</p>
      ) : (
        <ol className="sched-next-list">
          {d.items.map((x) => (
            <li key={x.id} className={x.late ? 'late' : undefined}>
              <span className="sched-next-time">{when(x.start_at)}</span>
              <span className="sched-next-what">
                <strong>
                  {text(x.order_number)}
                  {x.operation_id ? ` · ${text(x.seq)} ${text(x.name)}` : ''}
                </strong>
                <small>
                  {text(x.product)} · {duration(x.minutes)}
                  {x.running ? ' · running' : ''}
                  {x.late ? ' · late' : ''}
                </small>
              </span>
              {x.pin_status === 'HONOURED' && <Pin size={13} aria-label="Pinned" />}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
