import { useState } from 'react';
import { Cpu } from 'lucide-react';
import { useWorkspace } from '../app/workspace';
import { Badge, Empty, Panel } from '../components';
import { hoursText, useWorkCenterSummary } from '../features/routings/api';
import { FloorOrderCards } from '../features/routings/FloorOrderCards';
import { OperationQueue } from '../features/routings/OperationQueue';
import type { Row } from '../lib/api';
import { fmt, num, text } from '../lib/format';

const STORAGE = 'dex-shopfloor-work-center';

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
  const busy = (m: Row) => num(m.running) + num(m.paused) + num(m.ready) > 0;
  const machine =
    items.find((m) => m.id === picked) ||
    items.find((m) => num(m.running) > 0) ||
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
                  {fmt(m.running)} running · {fmt(m.paused)} paused · {fmt(m.ready)} ready
                </span>
                <small>
                  {num(m.available_minutes_today)
                    ? `${hoursText(num(m.available_minutes_today))} today`
                    : 'Not working today'}
                  {m.calendar_code ? ` · ${text(m.calendar_code)}` : ''}
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
                  {machine.status === 'MAINTENANCE'
                    ? ' · under maintenance: operations cannot start here'
                    : ''}
                </span>
              </div>
              <OperationQueue key={machine.id} machine={machine} />
            </section>
          )}
        </>
      )}
      <section aria-label="Orders without a routing">
        <div className="floor-heading">
          <h2>Orders without a routing</h2>
          <span className="muted">One implicit operation per order, as before routings.</span>
        </div>
        <FloorOrderCards />
      </section>
    </>
  );
}
