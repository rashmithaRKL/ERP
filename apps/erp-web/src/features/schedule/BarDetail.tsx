import { useState } from 'react';
import { ExternalLink, LoaderCircle, Pin, PinOff, X } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Badge } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, fmt, labelState, text } from '../../lib/format';
import { PLANNERS, duration, pinnable, scheduleChanged, when } from './api';
import type { Bar, GanttMachine } from './api';
import { barLabel } from './Gantt';

/** The selected operation bar: timing, work, lateness and pin state, with "open order", "pin / move" and "remove pin". */
export function BarDetail({
  bar,
  machine,
  onPin,
  onClose,
}: {
  bar: Bar;
  machine: GanttMachine;
  onPin: () => void;
  onClose: () => void;
}) {
  const { can, openOrder, notify, refresh } = useWorkspace();
  const [busy, setBusy] = useState(false);
  const planner = can(...PLANNERS);

  async function unpin() {
    setBusy(true);
    try {
      const out = await request<{ run: Row | null }>(
        `/schedule/pins/${text(bar.pin_id)}/remove`,
        { rerun: true },
        crypto.randomUUID(),
      );
      scheduleChanged(out.run ? text(out.run.id) : undefined);
      notify('Pin removed successfully; a new draft was scheduled.');
      void refresh();
    } catch (err) {
      notify((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="sched-detail" aria-label={`Selected operation ${barLabel(bar)}`}>
      <div className="sched-detail-head">
        <div>
          <h3>{barLabel(bar)}</h3>
          <p>
            {text(bar.product)} · due {date(bar.due_date)} · <Badge value={bar.priority} />
          </p>
        </div>
        <button className="icon-button" aria-label="Close operation details" onClick={onClose}>
          <X size={16} />
        </button>
      </div>
      <div className="sched-facts">
        <div>
          <span>Work center</span>
          <strong>
            {text(machine.code)}
            {bar.alternate ? <small> alternate of {text(bar.planned_machine_code)}</small> : null}
          </strong>
        </div>
        <div>
          <span>Scheduled</span>
          <strong>
            {when(bar.start_at)} – {when(bar.end_at)}
          </strong>
        </div>
        <div>
          <span>Work</span>
          <strong>
            {duration(bar.minutes)}
            {bar.segments.length > 1 ? <small> in {fmt(bar.segments.length)} parts</small> : null}
          </strong>
        </div>
        <div>
          <span>Status now</span>
          <strong>
            {labelState(bar.current_status)}
            {bar.running ? <small> running</small> : null}
          </strong>
        </div>
        <div>
          <span>Due date</span>
          <strong className={bar.late ? 'late-flag' : undefined}>{bar.late ? 'Late' : 'On time'}</strong>
        </div>
        <div>
          <span>Pin</span>
          <strong>
            {bar.pin_status === 'HONOURED'
              ? 'Pinned'
              : bar.pin_status === 'CONFLICT'
                ? 'Not honoured'
                : 'None'}
          </strong>
        </div>
      </div>
      {bar.pin_status === 'CONFLICT' && <p className="sched-note">Pin not honoured: {text(bar.pin_note)}</p>}
      <div className="sched-detail-actions">
        <button className="button secondary small" onClick={() => openOrder(text(bar.order_id))}>
          <ExternalLink size={14} />
          Open order
        </button>
        {planner && pinnable(bar) && (
          <button className="button secondary small" onClick={onPin}>
            <Pin size={14} />
            {bar.pin_status ? 'Move pin' : 'Pin / move'}
          </button>
        )}
        {planner && bar.pin_id && (
          <button className="button secondary small" disabled={busy} onClick={unpin}>
            {busy ? <LoaderCircle size={14} className="spin" /> : <PinOff size={14} />}
            Remove pin
          </button>
        )}
      </div>
    </section>
  );
}
