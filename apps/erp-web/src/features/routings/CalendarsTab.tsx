import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import {
  CalendarClock,
  CalendarX,
  ChevronLeft,
  ChevronRight,
  LoaderCircle,
  Moon,
  Pencil,
  Plus,
} from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Panel, Table } from '../../components';
import type { Row } from '../../lib/api';
import { date, fmt, text, today } from '../../lib/format';
import {
  WEEKDAYS,
  addDays,
  hhmm,
  hoursText,
  listOf,
  minutesText,
  mondayOf,
  useAvailability,
  useCalendars,
  useRoutingRequest,
  weekdayText,
} from './api';
import type { AvailabilityDay } from './api';
import { CalendarDialog, ExceptionsDialog, PatternDialog } from './CalendarDialogs';

type Dialog =
  | { kind: 'pattern'; row?: Row }
  | { kind: 'calendar'; row?: Row }
  | { kind: 'exceptions'; row: Row }
  | undefined;

/**
 * Shift calendars tab: available hours per work center for a week (from `GET /api/calendars/available-minutes`, the same
 * service V13 scheduling and V15 OEE use), work-center calendar assignment, calendars with their holidays and overtime,
 * and shift patterns.
 */
export function CalendarsTab() {
  const { can } = useWorkspace();
  const overview = useCalendars();
  const [dialog, setDialog] = useState<Dialog>();
  const planner = can('PLANNER');
  const o = overview.data;
  return (
    <>
      <WeekView calendars={o?.calendars || []} />
      <div className="calendar-columns">
        <Panel
          title="Calendars"
          subtitle="Sets of shifts with holidays and overtime"
          action={
            planner && (
              <button className="button secondary small" onClick={() => setDialog({ kind: 'calendar' })}>
                <Plus size={14} />
                New calendar
              </button>
            )
          }
        >
          {overview.isPending ? (
            <div className="server-loading">
              <div className="skeleton" />
            </div>
          ) : overview.isError ? (
            <div className="form-error server-error" role="alert">
              {(overview.error as Error).message}
            </div>
          ) : !o?.calendars.length ? (
            <Empty
              title="No shift calendars yet"
              body="Without a calendar every work center counts as available around the clock. Create shift patterns, then a plant default calendar."
            />
          ) : (
            <div className="calendar-list">
              {o.calendars.map((c) => (
                <div key={c.id} className="calendar-row">
                  <span className="calendar-icon">
                    <CalendarClock size={18} />
                  </span>
                  <div>
                    <strong>
                      {text(c.name)}
                      {c.plant_default && <span className="bom-chip level inline-chip">plant default</span>}
                    </strong>
                    <span>
                      {text(c.code)} · shifts {text(c.pattern_codes) || 'none'} · {fmt(c.assigned)} assigned
                      work centers · {fmt(c.upcoming_exceptions)} upcoming exceptions
                    </span>
                  </div>
                  <button
                    className="button secondary small"
                    onClick={() => setDialog({ kind: 'exceptions', row: c })}
                    aria-label={`Holidays and overtime of ${text(c.code)}`}
                  >
                    <CalendarX size={14} />
                    Exceptions
                  </button>
                  {planner && (
                    <button
                      className="icon-button"
                      aria-label={`Edit calendar ${text(c.code)}`}
                      onClick={() => setDialog({ kind: 'calendar', row: c })}
                    >
                      <Pencil size={15} />
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
        </Panel>
        <Panel
          title="Shift patterns"
          subtitle="One shift, its break and the weekdays it runs"
          action={
            planner && (
              <button className="button secondary small" onClick={() => setDialog({ kind: 'pattern' })}>
                <Plus size={14} />
                New shift
              </button>
            )
          }
        >
          <Table
            label="Shift patterns"
            compact
            rows={o?.patterns || []}
            onRow={planner ? (p) => setDialog({ kind: 'pattern', row: p }) : undefined}
            columns={[
              {
                label: 'Shift',
                render: (p) => (
                  <span className="cell-stack">
                    <strong>{text(p.code)}</strong>
                    <small>{text(p.name)}</small>
                  </span>
                ),
              },
              {
                label: 'Hours',
                render: (p) => (
                  <span>
                    {hhmm(p.start_time)}–{hhmm(p.end_time)}
                    {p.overnight && <Moon size={12} className="inline-icon" aria-label="overnight" />}
                  </span>
                ),
              },
              {
                label: 'Break',
                render: (p) =>
                  Number(p.break_minutes)
                    ? `${text(p.break_minutes)} min${p.break_start ? ` at ${hhmm(p.break_start)}` : ' (centred)'}`
                    : 'None',
              },
              { label: 'Days', render: (p) => weekdayText(listOf<number>(p, 'weekday_list')) },
              { label: 'Working', render: (p) => minutesText(p.paid_minutes) },
            ]}
          />
        </Panel>
      </div>
      <AnimatePresence>
        {dialog?.kind === 'pattern' && (
          <PatternDialog pattern={dialog.row} onClose={() => setDialog(undefined)} />
        )}
        {dialog?.kind === 'calendar' && (
          <CalendarDialog calendar={dialog.row} onClose={() => setDialog(undefined)} />
        )}
        {dialog?.kind === 'exceptions' && (
          <ExceptionsDialog calendar={dialog.row} onClose={() => setDialog(undefined)} />
        )}
      </AnimatePresence>
    </>
  );
}

/** Available hours per work center and day for one week, with the calendar assignment (planners can change it). */
function WeekView({ calendars }: { calendars: Row[] }) {
  const { can, notify } = useWorkspace();
  const save = useRoutingRequest();
  const [monday, setMonday] = useState(() => mondayOf(today()));
  const sunday = addDays(monday, 6);
  const availability = useAvailability(monday, sunday);
  const [assigning, setAssigning] = useState('');
  const [error, setError] = useState('');
  const planner = can('PLANNER');
  const rows = availability.data?.workCenters || [];
  const dates = Array.from({ length: 7 }, (_, i) => addDays(monday, i));

  async function assign(machineId: string, calendarId: string) {
    setAssigning(machineId);
    setError('');
    try {
      await save('/calendars/assignments', { machineId, calendarId });
      notify('Work center calendar assigned successfully.');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setAssigning('');
    }
  }

  return (
    <Panel
      title="Available hours by work center"
      subtitle={`${date(monday)} – ${date(sunday)} · shifts minus breaks and holidays, plus overtime`}
      className="week-panel"
      action={
        <div className="week-nav">
          <button
            className="icon-button"
            aria-label="Previous week"
            onClick={() => setMonday(addDays(monday, -7))}
          >
            <ChevronLeft size={16} />
          </button>
          <button className="button secondary small" onClick={() => setMonday(mondayOf(today()))}>
            This week
          </button>
          <button
            className="icon-button"
            aria-label="Next week"
            onClick={() => setMonday(addDays(monday, 7))}
          >
            <ChevronRight size={16} />
          </button>
        </div>
      }
    >
      {error && (
        <div className="form-error week-error" role="alert">
          {error}
        </div>
      )}
      {availability.isPending ? (
        <div className="server-loading">
          <div className="skeleton" />
        </div>
      ) : availability.isError ? (
        <div className="form-error server-error" role="alert">
          {(availability.error as Error).message}
        </div>
      ) : (
        <div
          className="week-scroll"
          tabIndex={0}
          role="region"
          aria-label="Available hours per work center and day"
        >
          <table className="week-table">
            <thead>
              <tr>
                <th scope="col">Work center</th>
                {dates.map((d, i) => (
                  <th scope="col" key={d} className={d === today() ? 'today' : ''}>
                    {WEEKDAYS[i]} <small>{date(d)}</small>
                  </th>
                ))}
                <th scope="col">Week</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((w) => (
                <tr key={text(w.machine_id)}>
                  <th scope="row">
                    <span className="cell-stack">
                      <strong>{text(w.code)}</strong>
                      {planner ? (
                        <select
                          aria-label={`Calendar of ${text(w.code)}`}
                          value={w.source === 'ASSIGNED' ? text(w.calendar_id) : ''}
                          disabled={assigning === w.machine_id}
                          onChange={(e) => assign(text(w.machine_id), e.target.value)}
                        >
                          <option value="">Plant default{w.source === 'NONE' ? ' (none: 24 h)' : ''}</option>
                          {calendars.map((c) => (
                            <option key={c.id} value={c.id}>
                              {text(c.code)}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <small>
                          {w.source === 'NONE'
                            ? 'No calendar (24 h)'
                            : `${text(w.calendar_code)}${w.source === 'PLANT_DEFAULT' ? ' (plant default)' : ''}`}
                        </small>
                      )}
                    </span>
                    {assigning === w.machine_id && <LoaderCircle className="spin" size={13} />}
                  </th>
                  {listOf<AvailabilityDay>(w, 'days').map((d) => (
                    <td
                      key={d.date}
                      className={`${d.closed ? 'closed' : ''} ${d.date === today() ? 'today' : ''}`}
                      title={
                        d.intervals
                          .map((i) => `${i.start.slice(11, 16)}–${i.end.slice(11, 16)} ${i.label}`)
                          .join('\n') || 'No working time'
                      }
                    >
                      <span className="week-hours">{d.minutes ? hoursText(d.minutes) : '—'}</span>
                      <span className="week-bar" aria-hidden="true">
                        <span style={{ width: `${Math.min(100, (d.minutes / 1440) * 100)}%` }} />
                      </span>
                      {d.closed && <small className="holiday">{d.closedReason || 'holiday'}</small>}
                    </td>
                  ))}
                  <td className="week-total">{hoursText(Number(w.minutes))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}
