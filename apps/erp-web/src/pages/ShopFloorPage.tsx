import { ArrowUpRight, CalendarDays, Cpu, Pause, Play, Plus } from 'lucide-react';
import { useWorkspace } from '../app/workspace';
import { Badge, Empty, Panel, Progress } from '../components';
import { recordOutput } from '../features/manufacturing/forms';
import { date, fmt, num, text } from '../lib/format';

/** Route `/shopfloor`: operator job cards for released and running orders. */
export default function ShopFloorPage() {
  const workspace = useWorkspace();
  const { busy, can, mutate, openOrder, summary, user } = workspace;
  const { active } = summary;
  return (
    <>
      <div className="shopfloor-header">
        <div>
          <span className="green-dot" />
          <strong>Operator workspace</strong>
          <span>Large controls. Clear next steps.</span>
        </div>
        <Badge value={user.role} />
      </div>
      <div className="job-grid">
        {active
          .filter((r) => !['DRAFT', 'APPROVED', 'MATERIALS_RESERVED'].includes(text(r.status)))
          .map((r) => (
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
      {!active.length && <Empty title="No jobs on the floor" />}
    </>
  );
}
