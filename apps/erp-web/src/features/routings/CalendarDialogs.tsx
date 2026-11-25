import { useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { CalendarPlus, LoaderCircle, Trash2 } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import type { Row } from '../../lib/api';
import { date, text, today } from '../../lib/format';
import { WEEKDAYS, hhmm, listOf, useCalendarDetail, useCalendars, useRoutingRequest } from './api';

/** A form dialog with an error line and a pending submit button; `onSubmit` receives the form values and a stable key. */
function DialogForm({
  title,
  subtitle,
  submit,
  onClose,
  onSubmit,
  children,
}: {
  title: string;
  subtitle: string;
  submit: string;
  onClose: () => void;
  onSubmit: (values: Record<string, string>, key: string) => Promise<void>;
  children: ReactNode;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const key = useRef(crypto.randomUUID());
  async function handle(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setPending(true);
    setError('');
    try {
      await onSubmit(
        Object.fromEntries(new FormData(e.currentTarget)) as Record<string, string>,
        key.current,
      );
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }
  return (
    <Overlay title={title} subtitle={subtitle} onClose={() => !pending && onClose()}>
      <form onSubmit={handle}>
        <div className="form-fields">
          {children}
          {error && (
            <div role="alert" className="form-error">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <button type="button" className="button secondary" disabled={pending} onClick={onClose}>
            Cancel
          </button>
          <button className="button primary" disabled={pending}>
            {pending && <LoaderCircle className="spin" size={15} />}
            {submit}
          </button>
        </div>
      </form>
    </Overlay>
  );
}

/** Create (no `pattern`) or edit a shift pattern: times, one break and the weekdays it runs. */
export function PatternDialog({ pattern, onClose }: { pattern?: Row; onClose: () => void }) {
  const save = useRoutingRequest();
  const { notify } = useWorkspace();
  const [days, setDays] = useState<number[]>(() =>
    pattern ? listOf<number>(pattern, 'weekday_list') : [1, 2, 3, 4, 5],
  );
  return (
    <DialogForm
      title={pattern ? `Edit shift ${text(pattern.code)}` : 'New shift pattern'}
      subtitle="Local times (Asia/Colombo). An end time at or before the start runs past midnight into the next day."
      submit={pattern ? 'Save shift' : 'Create shift'}
      onClose={onClose}
      onSubmit={async (v, key) => {
        if (!days.length) throw new Error('Choose at least one weekday');
        const body = {
          name: v.name,
          startTime: v.startTime,
          endTime: v.endTime,
          breakStart: v.breakStart || null,
          breakMinutes: Number(v.breakMinutes || 0),
          weekdays: [...days].sort(),
        };
        if (pattern) await save(`/calendars/patterns/${pattern.id}`, body, key);
        else await save('/calendars/patterns', { code: v.code, ...body }, key);
        notify('Shift pattern saved successfully.');
      }}
    >
      {!pattern && (
        <label className="field">
          <span>Code</span>
          <input
            name="code"
            required
            maxLength={20}
            pattern="[A-Za-z0-9][A-Za-z0-9-]{0,19}"
            placeholder="DAY"
          />
        </label>
      )}
      <label className="field">
        <span>Name</span>
        <input name="name" required maxLength={120} defaultValue={text(pattern?.name) || 'Day shift'} />
      </label>
      <label className="field">
        <span>Starts</span>
        <input name="startTime" type="time" required defaultValue={hhmm(pattern?.start_time) || '06:00'} />
      </label>
      <label className="field">
        <span>Ends</span>
        <input name="endTime" type="time" required defaultValue={hhmm(pattern?.end_time) || '14:00'} />
      </label>
      <label className="field">
        <span>Break (minutes)</span>
        <input
          name="breakMinutes"
          type="number"
          min="0"
          max="720"
          step="1"
          required
          defaultValue={text(pattern?.break_minutes ?? 30)}
        />
      </label>
      <label className="field">
        <span>
          Break starts<small>Optional</small>
        </span>
        <input name="breakStart" type="time" defaultValue={hhmm(pattern?.break_start)} />
        <em>Empty: the break is centred in the shift.</em>
      </label>
      <fieldset className="field full weekday-picker">
        <legend>Weekdays</legend>
        <div className="check-row">
          {WEEKDAYS.map((d, i) => (
            <label key={d} className="check-field">
              <input
                type="checkbox"
                checked={days.includes(i + 1)}
                onChange={(e) =>
                  setDays(e.target.checked ? [...days, i + 1] : days.filter((x) => x !== i + 1))
                }
              />
              {d}
            </label>
          ))}
        </div>
      </fieldset>
    </DialogForm>
  );
}

/** Create (no `calendar`) or edit a calendar: its shift patterns and whether it is the plant default. */
export function CalendarDialog({ calendar, onClose }: { calendar?: Row; onClose: () => void }) {
  const save = useRoutingRequest();
  const { notify } = useWorkspace();
  const patterns = useCalendars().data?.patterns || [];
  const [chosen, setChosen] = useState<string[]>(() =>
    calendar ? text(calendar.pattern_ids).split(',').filter(Boolean) : [],
  );
  return (
    <DialogForm
      title={calendar ? `Edit calendar ${text(calendar.code)}` : 'New shift calendar'}
      subtitle="A calendar is a set of shift patterns. Work centers without their own calendar use the plant default."
      submit={calendar ? 'Save calendar' : 'Create calendar'}
      onClose={onClose}
      onSubmit={async (v, key) => {
        const body = { name: v.name, patternIds: chosen, plantDefault: v.plantDefault === 'on' };
        if (calendar) await save(`/calendars/${calendar.id}`, body, key);
        else await save('/calendars', { code: v.code, ...body }, key);
        notify('Shift calendar saved successfully.');
      }}
    >
      {!calendar && (
        <label className="field">
          <span>Code</span>
          <input
            name="code"
            required
            maxLength={20}
            pattern="[A-Za-z0-9][A-Za-z0-9-]{0,19}"
            placeholder="TWO-SHIFT"
          />
        </label>
      )}
      <label className="field">
        <span>Name</span>
        <input name="name" required maxLength={120} defaultValue={text(calendar?.name)} />
      </label>
      <fieldset className="field full weekday-picker">
        <legend>Shift patterns</legend>
        {patterns.length === 0 && <p className="muted">Create a shift pattern first.</p>}
        <div className="check-row">
          {patterns.map((p) => (
            <label key={p.id} className="check-field">
              <input
                type="checkbox"
                checked={chosen.includes(p.id)}
                onChange={(e) =>
                  setChosen(e.target.checked ? [...chosen, p.id] : chosen.filter((x) => x !== p.id))
                }
              />
              {text(p.code)} · {hhmm(p.start_time)}–{hhmm(p.end_time)}
            </label>
          ))}
        </div>
      </fieldset>
      <label className="check-field full">
        <input type="checkbox" name="plantDefault" defaultChecked={!!calendar?.plant_default} />
        Plant default calendar (replaces the current default)
      </label>
    </DialogForm>
  );
}

/** Holidays and overtime of one calendar: the list with remove buttons and an add form (planners). */
export function ExceptionsDialog({ calendar, onClose }: { calendar: Row; onClose: () => void }) {
  const { can, notify } = useWorkspace();
  const save = useRoutingRequest();
  const detail = useCalendarDetail(calendar.id);
  const [kind, setKind] = useState('CLOSED');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const key = useRef(crypto.randomUUID());
  const planner = can('PLANNER');

  async function add(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const v = Object.fromEntries(new FormData(form)) as Record<string, string>;
    setPending(true);
    setError('');
    try {
      await save(
        `/calendars/${calendar.id}/exceptions`,
        {
          date: v.date,
          kind,
          startTime: kind === 'OVERTIME' ? v.startTime : null,
          endTime: kind === 'OVERTIME' ? v.endTime : null,
          reason: v.reason,
        },
        key.current,
      );
      key.current = crypto.randomUUID();
      form.reset();
      notify(kind === 'CLOSED' ? 'Holiday added successfully.' : 'Overtime added successfully.');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }

  async function remove(row: Row) {
    setError('');
    try {
      await save(`/calendars/${calendar.id}/exceptions/${row.id}/remove`, {});
      notify('Calendar exception removed successfully.');
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <Overlay
      title={`${text(calendar.code)} · holidays and overtime`}
      subtitle="A holiday cancels the shifts that start on that date; overtime adds working time, even on a holiday."
      onClose={onClose}
      wide
    >
      <div className="drawer-body">
        {detail.isPending ? (
          <LoaderCircle className="spin" />
        ) : detail.isError ? (
          <div className="form-error" role="alert">
            {(detail.error as Error).message}
          </div>
        ) : (
          <Table
            label="Calendar exceptions"
            compact
            rows={detail.data?.exceptions || []}
            columns={[
              { label: 'Date', render: (r) => date(r.exception_date) },
              {
                label: 'Kind',
                render: (r) => (
                  <span className={`badge ${r.kind === 'CLOSED' ? 'red' : 'green'}`}>
                    <i />
                    {r.kind === 'CLOSED' ? 'holiday' : `overtime ${hhmm(r.start_time)}–${hhmm(r.end_time)}`}
                  </span>
                ),
              },
              { label: 'Reason', render: (r) => <span className="notes-cell">{text(r.reason)}</span> },
              { label: 'Added by', render: (r) => text(r.created_by_name) },
              {
                label: '',
                render: (r) =>
                  planner && (
                    <button
                      className="icon-button"
                      aria-label={`Remove ${text(r.kind).toLowerCase()} on ${text(r.exception_date)}`}
                      onClick={() => remove(r)}
                    >
                      <Trash2 size={15} />
                    </button>
                  ),
              },
            ]}
          />
        )}
        {planner && (
          <form className="exception-form" onSubmit={add}>
            <h3>
              <CalendarPlus size={15} />
              Add a holiday or overtime
            </h3>
            <div className="exception-grid">
              <label className="field">
                <span>Date</span>
                <input name="date" type="date" required defaultValue={today()} />
              </label>
              <label className="field">
                <span>Kind</span>
                <select value={kind} onChange={(e) => setKind(e.target.value)}>
                  <option value="CLOSED">Holiday (closed)</option>
                  <option value="OVERTIME">Overtime</option>
                </select>
              </label>
              {kind === 'OVERTIME' && (
                <>
                  <label className="field">
                    <span>From</span>
                    <input name="startTime" type="time" required defaultValue="08:00" />
                  </label>
                  <label className="field">
                    <span>To</span>
                    <input name="endTime" type="time" required defaultValue="12:00" />
                  </label>
                </>
              )}
              <label className="field wide">
                <span>Reason</span>
                <input name="reason" required maxLength={200} placeholder="e.g. Poya holiday" />
              </label>
            </div>
            {error && (
              <div role="alert" className="form-error">
                {error}
              </div>
            )}
            <button className="button primary small" disabled={pending}>
              {pending && <LoaderCircle className="spin" size={14} />}
              Add to calendar
            </button>
          </form>
        )}
      </div>
    </Overlay>
  );
}
