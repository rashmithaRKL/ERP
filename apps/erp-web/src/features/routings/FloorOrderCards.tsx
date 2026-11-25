import { ArrowUpRight, CalendarDays, Cpu, Pause, Play, Plus } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Badge, Empty, Panel, Progress } from '../../components';
import { date, fmt, num, text } from '../../lib/format';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { recordOutput } from '../manufacturing/forms';

const PAGE = 24;

/**
 * Order cards for released, running, paused and quality-review orders without a routing (single implicit operation),
 * exactly as the shop floor showed every order before V10 (`GET /api/manufacturing/orders?status=floor&routing=none`).
 */
export function FloorOrderCards() {
  const workspace = useWorkspace();
  const { busy, can, mutate, openOrder } = workspace;
  const paged = useCursorPage(
    ['manufacturing', 'floor', 'unrouted'],
    '/manufacturing/orders',
    { status: 'floor', routing: 'none' },
    PAGE,
  );
  const jobs = paged.rows;
  return (
    <>
      <PageState paged={paged}>
        <div className="job-grid">
          {jobs.map((r) => (
            <Panel key={r.id} className="job-card">
              <div className="job-top">
                <span>
                  <Cpu size={16} />
                  {text(r.machine)}
                </span>
                <Badge value={r.status} />
              </div>
              <h2>{text(r.product)}</h2>
              <button className="text-button" onClick={() => openOrder(r.id)}>
                {text(r.number)}
                <ArrowUpRight size={13} />
              </button>
              <div className="job-quantity">
                <strong>{fmt(r.good_qty)}</strong>
                <span>/ {fmt(r.quantity)} pcs</span>
              </div>
              <Progress value={((num(r.good_qty) + num(r.scrap_qty)) / num(r.quantity)) * 100} />
              <div className="job-footer">
                <span>
                  <CalendarDays size={14} />
                  Due {date(r.due_date)}
                </span>
                <span>{fmt(r.scrap_qty)} scrap</span>
              </div>
              <div className="job-actions">
                {r.status === 'RELEASED' && can('OPERATOR', 'PLANNER') ? (
                  <button
                    className="button primary"
                    disabled={busy}
                    onClick={() => mutate(`/manufacturing/${r.id}/actions/start`)}
                  >
                    <Play size={17} />
                    Start operation
                  </button>
                ) : r.status === 'ON_HOLD' && can('OPERATOR', 'PLANNER') ? (
                  <button
                    className="button primary"
                    disabled={busy}
                    onClick={() => mutate(`/manufacturing/${r.id}/actions/resume`)}
                  >
                    <Play size={17} />
                    Resume work
                  </button>
                ) : ['IN_PROGRESS', 'PARTIALLY_COMPLETED'].includes(text(r.status)) && can('OPERATOR') ? (
                  <>
                    <button className="button primary" onClick={() => recordOutput(workspace, r)}>
                      <Plus size={17} />
                      Record output
                    </button>
                    <button
                      className="button secondary"
                      aria-label={`Pause ${r.number}`}
                      disabled={busy}
                      onClick={() => mutate(`/manufacturing/${r.id}/actions/pause`)}
                    >
                      <Pause size={17} />
                    </button>
                  </>
                ) : (
                  <span className="muted">
                    {r.status === 'QUALITY_REVIEW' ? 'Awaiting quality release' : 'View-only for your role'}
                  </span>
                )}
              </div>
            </Panel>
          ))}
        </div>
        {!jobs.length && (
          <Panel>
            <Empty title="No orders without a routing on the floor" />
          </Panel>
        )}
      </PageState>
      {(paged.hasNext || paged.hasPrevious) && <PageFooter paged={paged} label="jobs" />}
    </>
  );
}
