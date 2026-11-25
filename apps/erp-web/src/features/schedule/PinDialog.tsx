import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { LoaderCircle, Pin } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { text } from '../../lib/format';
import { localInput, scheduleChanged, useOperationMachines, when } from './api';
import type { Bar } from './api';
import { barLabel } from './Gantt';

/**
 * Pin (or move) an operation: a work center it may run on and a start time in business time (Asia/Colombo). The server
 * validates the pin by scheduling with it (precedence, capacity, other pins) and moves the start to the next working
 * minute; "run again" also creates a new draft that honours it. Dragging a bar on the Gantt opens this dialog with the
 * dropped work center and time; it is also the keyboard way to move an operation.
 */
export function PinDialog({
  bar,
  machineCode,
  machineId,
  start,
  onClose,
}: {
  bar: Bar;
  machineCode: string;
  machineId?: string;
  start?: string;
  onClose: () => void;
}) {
  const { notify, refresh } = useWorkspace();
  const machines = useOperationMachines(bar.operation_id ? text(bar.operation_id) : undefined);
  const key = useRef(crypto.randomUUID());
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const options: Row[] = bar.operation_id
    ? machines.data?.machines || []
    : [{ id: text(bar.machine_id), code: machineCode }];
  const chosen = machineId || text(bar.machine_id);
  const allowed = !bar.operation_id || options.some((m) => m.id === chosen);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const v = Object.fromEntries(new FormData(e.currentTarget).entries()) as Record<string, string>;
    setPending(true);
    setError('');
    try {
      const out = await request<Row & { run: Row | null }>(
        '/schedule/pins',
        {
          orderId: text(bar.order_id),
          operationId: bar.operation_id || null,
          machineId: v.machineId,
          start: v.start,
          reason: v.reason || null,
          rerun: v.rerun === 'on',
        },
        key.current,
      );
      scheduleChanged(out.run ? text(out.run.id) : undefined);
      notify(
        out.moved
          ? `Pinned successfully at ${when(out.start)}, the next working time.`
          : `Pinned successfully at ${when(out.start)}.`,
      );
      void refresh();
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }

  return (
    <Overlay
      title={`Pin ${barLabel(bar)}`}
      subtitle={`Scheduled ${when(bar.start_at)} – ${when(bar.end_at)}. A pin fixes the work center and start for every later run.`}
      onClose={() => {
        if (!pending) onClose();
      }}
    >
      <form onSubmit={submit}>
        <div className="form-fields">
          <label className="field">
            <span>Work center</span>
            {machines.isPending && bar.operation_id ? (
              <div className="skeleton sched-field-skeleton" />
            ) : (
              <select name="machineId" required defaultValue={allowed ? chosen : ''}>
                {!allowed && (
                  <option value="" disabled>
                    Not allowed for this operation; choose one
                  </option>
                )}
                {options.map((m) => (
                  <option key={m.id} value={m.id}>
                    {text(m.code)}
                    {m.name ? ` · ${text(m.name)}` : ''}
                  </option>
                ))}
              </select>
            )}
            <em>Its planned work center, the routing&apos;s work center or an alternate.</em>
          </label>
          <label className="field">
            <span>Start (Sri Lanka time)</span>
            <input
              name="start"
              type="datetime-local"
              required
              step={60}
              defaultValue={localInput(start || text(bar.start_at))}
            />
            <em>Moves to the next working minute if the work center is not working then.</em>
          </label>
          <label className="field">
            <span>
              Reason<small>Optional</small>
            </span>
            <textarea name="reason" rows={2} maxLength={400} />
          </label>
          <label className="check-field sched-check">
            <input type="checkbox" name="rerun" defaultChecked />
            Run the scheduler again with this pin (new draft)
          </label>
          {error && (
            <div role="alert" className="form-error">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <button type="button" className="button secondary" onClick={onClose} disabled={pending}>
            Cancel
          </button>
          <button className="button primary" disabled={pending}>
            {pending ? <LoaderCircle className="spin" size={16} /> : <Pin size={15} />}
            Pin operation
          </button>
        </div>
      </form>
    </Overlay>
  );
}
