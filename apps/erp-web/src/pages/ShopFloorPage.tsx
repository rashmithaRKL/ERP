import { useState } from 'react';
import { Cpu } from 'lucide-react';
import { useWorkspace } from '../app/workspace';
import { Badge, Empty, Panel } from '../components';
import { hoursText, useWorkCenterSummary } from '../features/routings/api';
import { FloorOrderCards } from '../features/routings/FloorOrderCards';
import { OperationQueue } from '../features/routings/OperationQueue';
import { NextOperations } from '../features/schedule/NextOperations';
import type { Row } from '../lib/api';
import { fmt, num, text } from '../lib/format';

const STORAGE = 'dex-shopfloor-work-center';

/**
 * Work on a work center: routed operations plus its open orders without a routing (each one implicit operation: in
 * progress = running, on hold = paused, released = ready), so the chip never says "0 running" while such an order runs.
 */
const load = (m: Row) => ({
  running: num(m.running) + num(m.legacy_running),
  paused: num(m.paused) + num(m.legacy_paused),
  ready: num(m.ready) + num(m.legacy_ready),
  unrouted: num(m.legacy_orders),
});

/** Why the registered status and the work disagree, in words (status is shown as a badge next to it). */
function statusNote(m: Row) {
  const w = load(m);
  if (m.status === 'MAINTENANCE') return 'under maintenance: operations cannot start here';
  if (m.status === 'RUNNING' && !w.running)
    return 'marked running, but no operation or order is running here (a planner can correct the status in Work centers)';
  if (m.status === 'IDLE' && w.running)
    return 'work is in progress although the work center is registered idle';
  return '';
}

function remembered() {
  try {
    return localStorage.getItem(STORAGE) || '';
  } catch {
    return '';
  }
}

/**
 * Route `/shopfloor` (V10: operation-centric): pick a work center, then work its queue of ready, running and paused
 * routing operations (`GET /api/operations/queue`). Orders without a routing keep their order cards below.
 */
export default function ShopFloorPage() {
  const { user } = useWorkspace();
  const centers = useWorkCenterSummary();
  const [picked, setPicked] = useState(remembered);
  const items = centers.data?.items || [];
  const busy = (m: Row) => load(m).running + load(m).paused + load(m).ready > 0;
  const machine =
    items.find((m) => m.id === picked) ||
    items.find((m) => load(m).running > 0) ||
    items.find(busy) ||
    items[0];

  function pick(id: string) {
    setPicked(id);
    try {
      localStorage.setItem(STORAGE, id);
    } catch {
      /* Storage can be disabled. */
    }
  }

  return (
    <>
      <div className="shopfloor-header">
        <div>
          <span className="green-dot" />
          <strong>Operator workspace</strong>
          <span>Pick your work center. Work the next operation.</span>
        </div>
        <Badge value={user.role} />
      </div>
      {centers.isPending ? (
        <div className="server-loading">
          <div className="skeleton" />
        </div>
      ) : centers.isError ? (
        <div className="form-error server-error" role="alert">
          {(centers.error as Error).message}
          <div>
            <button className="button secondary small" onClick={() => centers.refetch()}>
              Retry
            </button>
          </div>
        </div>
      ) : !items.length ? (
        <Panel>
          <Empty title="No work centers yet" body="Register work centers to plan operations on them." />
        </Panel>
      ) : (
        <>
          <div className="wc-picker" role="group" aria-label="Work centers">
            {items.map((m) => (
              <button
                key={m.id}
                className={`wc-option ${machine?.id === m.id ? 'active' : ''}`}
                aria-pressed={machine?.id === m.id}
                onClick={() => pick(m.id)}
              >
                <span className="wc-option-top">
                  <Cpu size={15} />
                  <strong>{text(m.code)}</strong>
                  <Badge value={m.status} />
                </span>
                <span className="wc-option-counts">
                  {fmt(load(m).running)} running · {fmt(load(m).paused)} paused · {fmt(load(m).ready)} ready
                </span>
                <small>
                  {num(m.available_minutes_today)
                    ? `${hoursText(num(m.available_minutes_today))} today`
                    : 'Not working today'}
                  {m.calendar_code ? ` · ${text(m.calendar_code)}` : ''}
                  {load(m).unrouted
                    ? ` · incl. ${fmt(load(m).unrouted)} ${load(m).unrouted === 1 ? 'order' : 'orders'} without a routing`
                    : ''}
                </small>
              </button>
            ))}
          </div>
          {machine && (
            <section aria-label={`Operation queue of ${text(machine.code)}`}>
              <div className="floor-heading">
                <h2>
                  {text(machine.code)} · {text(machine.name)}
                </h2>
                <span className="muted">
                  {text(machine.kind)}
                  {statusNote(machine) ? ` · ${statusNote(machine)}` : ''}
                </span>
              </div>
              <OperationQueue key={machine.id} machine={machine} />
              {/* V13: what the published schedule plans next on this work center. */}
              <NextOperations machineId={machine.id} />
            </section>
          )}
        </>
      )}
      <section aria-label="Orders without a routing">
        <div className="floor-heading">
          <h2>Orders without a routing</h2>
          <span className="muted">
            One implicit operation per order, as before routings; counted in the work-center chips above.
          </span>
        </div>
        <FloorOrderCards />
      </section>
    </>
  );
}
