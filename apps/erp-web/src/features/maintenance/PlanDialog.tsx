import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { LoaderCircle, Plus, Trash2 } from 'lucide-react';
import { useStateData } from '../../app/workspace';
import { Overlay } from '../../components';
import { request } from '../../lib/api';
import type { Value } from '../../lib/api';
import { text } from '../../lib/format';
import { colomboDay, PRIORITIES } from './api';

type Plan = Record<string, Value | string[]>;

/** Create or edit a preventive maintenance plan, with its checklist items. */
export function PlanDialog({
  plan,
  onClose,
  onDone,
}: {
  plan?: Plan;
  onClose: () => void;
  onDone: (id: string) => void;
}) {
  const data = useStateData();
  const key = useRef(crypto.randomUUID());
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [tasks, setTasks] = useState<string[]>(
    plan && Array.isArray(plan.checklist) && plan.checklist.length ? plan.checklist : [''],
  );
  const editing = !!plan;

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const v = Object.fromEntries(new FormData(e.currentTarget).entries()) as Record<string, string>;
    const body = {
      ...(editing ? {} : { machineId: v.machineId }),
      title: v.title,
      description: v.description,
      checklist: tasks.map((t) => t.trim()).filter(Boolean),
      intervalDays: Number(v.intervalDays),
      intervalHours: v.intervalHours || undefined,
      nextDueDate: v.nextDueDate,
      estimatedHours: v.estimatedHours || undefined,
      priority: v.priority,
      ...(editing ? { active: v.active === 'true' } : {}),
    };
    setPending(true);
    setError('');
    try {
      const result = await request<{ id: string }>(
        editing ? `/maintenance/plans/${plan.id}` : '/maintenance/plans',
        body,
        key.current,
      );
      onDone(result.id);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }

  return (
    <Overlay
      title={editing ? `Edit ${text(plan.number)}` : 'New maintenance plan'}
      subtitle="Calendar interval, with an optional run-hour interval: whichever comes first."
      onClose={() => {
        if (!pending) onClose();
      }}
      wide
    >
      <form onSubmit={submit}>
        <div className="form-fields">
          {editing ? (
            <div className="field">
              <span>Work center</span>
              <p className="mnt-static">
                {text(plan.machine)} · {text(plan.machine_name)}
              </p>
            </div>
          ) : (
            <label className="field">
              <span>Work center</span>
              <select name="machineId" required defaultValue="">
                <option value="" disabled>
                  Select work center
                </option>
                {data.machines.map((m) => (
                  <option key={m.id} value={m.id}>
                    {text(m.code)} · {text(m.name)}
                  </option>
                ))}
              </select>
            </label>
          )}
          <label className="field">
            <span>Title</span>
            <input name="title" required maxLength={160} defaultValue={text(plan?.title)} />
          </label>
          <label className="field">
            <span>Every (days)</span>
            <input
              name="intervalDays"
              type="number"
              required
              min="1"
              max="3650"
              step="1"
              defaultValue={text(plan?.interval_days ?? 30)}
            />
          </label>
          <label className="field">
            <span>
              Or every (run hours)
              <small>Optional</small>
            </span>
            <input
              name="intervalHours"
              type="number"
              min="0.01"
              step="0.01"
              defaultValue={plan?.interval_hours == null ? '' : text(plan.interval_hours)}
            />
          </label>
          <label className="field">
            <span>{editing ? 'Next due date' : 'First due date'}</span>
            <input
              name="nextDueDate"
              type="date"
              required
              defaultValue={plan ? text(plan.next_due_date).substring(0, 10) : colomboDay(7)}
            />
          </label>
          <label className="field">
            <span>Estimated duration (hours)</span>
            <input
              name="estimatedHours"
              type="number"
              min="0.25"
              max="720"
              step="0.25"
              defaultValue={text(plan?.estimated_hours ?? 2)}
            />
          </label>
          <label className="field">
            <span>Priority</span>
            <select name="priority" defaultValue={text(plan?.priority ?? 'NORMAL')}>
              {PRIORITIES.map((p) => (
                <option key={p} value={p}>
                  {p.charAt(0) + p.slice(1).toLowerCase()}
                </option>
              ))}
            </select>
          </label>
          {editing && (
            <label className="field">
              <span>Status</span>
              <select name="active" defaultValue={plan.active ? 'true' : 'false'}>
                <option value="true">Active</option>
                <option value="false">Inactive (no orders generated)</option>
              </select>
            </label>
          )}
          <label className="field">
            <span>
              Description
              <small>Optional</small>
            </span>
            <textarea name="description" rows={2} maxLength={2000} defaultValue={text(plan?.description)} />
          </label>
          <fieldset className="mnt-tasks">
            <legend>Checklist</legend>
            {tasks.map((task, index) => (
              <div key={index} className="mnt-task-row">
                <span aria-hidden="true">{index + 1}.</span>
                <input
                  aria-label={`Checklist item ${index + 1}`}
                  value={task}
                  maxLength={200}
                  placeholder="For example “Replace hydraulic oil filter”"
                  onChange={(e) => setTasks((list) => list.map((t, i) => (i === index ? e.target.value : t)))}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      if (tasks.length < 40) setTasks((list) => [...list.slice(0, index + 1), '', ...list.slice(index + 1)]);
                    }
                  }}
                />
                <button
                  type="button"
                  className="icon-button"
                  aria-label={`Remove checklist item ${index + 1}`}
                  disabled={tasks.length === 1}
                  onClick={() => setTasks((list) => list.filter((_, i) => i !== index))}
                >
                  <Trash2 size={14} />
                </button>
              </div>
            ))}
            <button
              type="button"
              className="text-button"
              disabled={tasks.length >= 40}
              onClick={() => setTasks((list) => [...list, ''])}
            >
              <Plus size={13} />
              Add item
            </button>
          </fieldset>
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
            {pending && <LoaderCircle className="spin" size={16} />}
            {editing ? 'Save plan' : 'Create plan'}
          </button>
        </div>
      </form>
    </Overlay>
  );
}
