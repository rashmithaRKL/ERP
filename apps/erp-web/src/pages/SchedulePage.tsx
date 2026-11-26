import { useCallback, useState } from 'react';
import { AnimatePresence } from 'motion/react';
import {
  CalendarRange,
  ChevronLeft,
  ChevronRight,
  GitCompare,
  LoaderCircle,
  Play,
  Send,
  Trash2,
} from 'lucide-react';
import { useWorkspace } from '../app/workspace';
import { Badge, Empty, Panel } from '../components';
import { request } from '../lib/api';
import type { Row } from '../lib/api';
import { fmt, num, text, today } from '../lib/format';
import { Tabs } from '../motion/motion';
import {
  PLANNERS,
  RUN_STATUS,
  addDays,
  localDate,
  scheduleChanged,
  useCapacity,
  useGantt,
  useOverview,
  useRunDetail,
  useRuns,
  useScheduleSync,
  when,
} from '../features/schedule/api';
import type { Bar, GanttMachine } from '../features/schedule/api';
import { BarDetail } from '../features/schedule/BarDetail';
import { CapacityHeatmap } from '../features/schedule/CapacityHeatmap';
import { CompareDialog } from '../features/schedule/CompareDialog';
import { runScheduler } from '../features/schedule/forms';
import { Gantt } from '../features/schedule/Gantt';
import type { ColourBy } from '../features/schedule/Gantt';
import { OrdersPanel } from '../features/schedule/OrdersPanel';
import { PinDialog } from '../features/schedule/PinDialog';
import '../features/schedule/schedule.css';

const ZOOMS = [
  { value: 'day', label: 'Day', days: 2, pxPerHour: 44 },
  { value: 'week', label: 'Week', days: 7, pxPerHour: 7 },
];

/**
 * Route `/schedule`: finite-capacity production schedule (V13). Planners run the scheduler into drafts, compare them
 * with the published schedule, publish, and pin or move operations (drag on the Gantt or the "Pin / move" dialog).
 * Other roles read the published schedule.
 */
export default function SchedulePage() {
  const workspace = useWorkspace();
  const { can, notify, refresh } = workspace;
  const planner = can(...PLANNERS);
  const overview = useOverview();
  const runs = useRuns(planner);
  const [picked, setPicked] = useState<string>();
  const [zoom, setZoom] = useState('week');
  const [from, setFrom] = useState<string>();
  const [colourBy, setColourBy] = useState<ColourBy>('priority');
  const [selected, setSelected] = useState<{ bar: Bar; machine: GanttMachine }>();
  const [pin, setPin] = useState<{ bar: Bar; machineCode: string; machineId?: string; start?: string }>();
  const [compare, setCompare] = useState(false);
  const [busy, setBusy] = useState('');
  useScheduleSync(
    useCallback((id: string) => {
      setPicked(id);
      setSelected(undefined);
    }, []),
  );

  const o = overview.data;
  const newest =
    o?.draft && (!o.published || text(o.draft.created_at) > text(o.published.created_at))
      ? o.draft
      : o?.published;
  const runId = picked || (planner ? newest?.id : o?.published?.id) || undefined;
  const detail = useRunDetail(runId);
  const run = detail.data?.run;
  const z = ZOOMS.find((x) => x.value === zoom) || ZOOMS[1];
  const runStart = run ? localDate(text(run.horizon_start)) : '';
  const start = from || runStart;
  const to = start ? addDays(start, z.days - 1) : '';
  const gantt = useGantt(runId, start, to);
  const capacity = useCapacity(runId, runStart, runStart ? addDays(runStart, 13) : '');

  async function act(path: string, label: string, success: string) {
    setBusy(label);
    try {
      await request(path, {}, crypto.randomUUID());
      scheduleChanged();
      // A discarded draft is no longer worth showing: fall back to the newest run.
      if (label === 'discard') setPicked(undefined);
      notify(success);
      void refresh();
    } catch (err) {
      notify((err as Error).message);
    } finally {
      setBusy('');
    }
  }

  if (overview.isPending)
    return (
      <div className="server-loading">
        <div className="skeleton" />
      </div>
    );
  if (overview.isError)
    return (
      <div className="form-error server-error" role="alert">
        {(overview.error as Error).message}
        <div>
          <button className="button secondary small" onClick={() => overview.refetch()}>
            Retry
          </button>
        </div>
      </div>
    );
  if (!runId)
    return (
      <Panel>
        <Empty
          title={planner ? 'No schedule yet' : 'No published schedule yet'}
          body={
            planner
              ? 'Run the scheduler to place every open operation on its work center, within shift calendars, holidays, maintenance windows and downtime.'
              : 'A planner publishes the production schedule here. Until then, follow the Shop floor queue.'
          }
        />
        {planner && (
          <div className="sched-empty-action">
            <button className="button primary" onClick={() => runScheduler(workspace)}>
              <Play size={15} />
              Run scheduler
            </button>
          </div>
        )}
      </Panel>
    );

  const isDraft = run?.status === 'DRAFT';
  const stale = isDraft && o?.draft?.id !== run?.id;
  const published = o?.published;
  // V16: orders, operations, maintenance windows, downtime or calendars changed after the published run was created.
  const changes = num(o?.stale?.changes);
  const outdated =
    published && changes > 0
      ? `The published schedule ${text(published.number)} may be out of date: ${fmt(changes)} ${
          changes === 1 ? 'change' : 'changes'
        } to orders, operations, maintenance windows, downtime or calendars since it was run${
          o?.stale?.last_change_at ? ` (latest ${when(o.stale.last_change_at)})` : ''
        }. ${
          !planner
            ? 'A planner can run the scheduler again.'
            : isDraft &&
                !stale &&
                run &&
                Date.parse(text(run.created_at)) > Date.parse(text(o?.stale?.last_change_at))
              ? 'This draft already includes them; publish it to replace the published schedule.'
              : 'Run the scheduler again and publish the new run.'
        }`
      : '';
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Showing</span>
          <strong>{run ? text(run.number) : '…'}</strong>
        </div>
        <div>
          <span>Late orders</span>
          <strong className={num(run?.late_orders) ? 'mnt-alert' : undefined}>{fmt(run?.late_orders)}</strong>
        </div>
        <div>
          <span>Unscheduled orders</span>
          <strong className={num(run?.unscheduled_orders) ? 'inv-attention' : undefined}>
            {fmt(run?.unscheduled_orders)}
          </strong>
        </div>
        <div>
          <span>Pins honoured</span>
          <strong className={num(run?.pin_conflicts) ? 'inv-attention' : undefined}>
            {fmt(run?.pins_honoured)}
            {num(run?.pin_conflicts) ? ` · ${fmt(run?.pin_conflicts)} not` : ''}
          </strong>
        </div>
      </div>

      <Panel className="sched-toolbar-panel">
        <div className="sched-toolbar">
          <div className="sched-run">
            {planner && runs.data?.items.length ? (
              <label className="field compact-field">
                <span>Schedule run</span>
                <select
                  value={runId}
                  onChange={(e) => {
                    setPicked(e.target.value);
                    setSelected(undefined);
                  }}
                >
                  {!runs.data.items.some((r) => r.id === runId) && run && (
                    <option value={runId}>{text(run.number)}</option>
                  )}
                  {runs.data.items.map((r: Row) => (
                    <option key={r.id} value={r.id}>
                      {text(r.number)} · {RUN_STATUS[text(r.status)]} · {when(r.created_at)}
                    </option>
                  ))}
                </select>
              </label>
            ) : null}
            {run && (
              <p className="sched-run-meta">
                <Badge value={RUN_STATUS[text(run.status)] || run.status} />
                <span>
                  {run.status === 'PUBLISHED' || run.status === 'SUPERSEDED'
                    ? `Published ${when(run.published_at)} by ${text(run.published_by_name)}`
                    : `Run ${when(run.created_at)} by ${text(run.created_by_name)}`}
                  {` · ${fmt(run.operations_count)} operations · ${text(run.horizon_days)}-day horizon from ${when(run.horizon_start)}`}
                  {run.use_alternates ? ' · alternates on' : ' · alternates off'}
                </span>
              </p>
            )}
          </div>
          {planner && (
            <div className="sched-actions">
              <button className="button secondary" onClick={() => runScheduler(workspace, run)}>
                <Play size={15} />
                Run scheduler
              </button>
              {run && published && published.id !== run.id && run.status !== 'SUPERSEDED' && (
                <button className="button secondary" onClick={() => setCompare(true)}>
                  <GitCompare size={15} />
                  Compare with published
                </button>
              )}
              {isDraft && (
                <button
                  className="button secondary"
                  disabled={!!busy}
                  onClick={() =>
                    act(`/schedule/runs/${runId}/discard`, 'discard', 'Draft discarded successfully.')
                  }
                >
                  {busy === 'discard' ? <LoaderCircle size={15} className="spin" /> : <Trash2 size={15} />}
                  Discard
                </button>
              )}
              {isDraft && (
                <button
                  className="button primary"
                  disabled={!!busy || stale}
                  title={stale ? 'A newer run exists; publish that one or run again' : undefined}
                  onClick={() =>
                    act(`/schedule/runs/${runId}/publish`, 'publish', 'Schedule published successfully.')
                  }
                >
                  {busy === 'publish' ? <LoaderCircle size={15} className="spin" /> : <Send size={15} />}
                  Publish
                </button>
              )}
            </div>
          )}
        </div>
        {outdated && (
          <p className="sched-note" role="status">
            {outdated}
          </p>
        )}
        {stale && <p className="sched-note">A newer run exists. Only the newest run can be published.</p>}
        {run?.status === 'SUPERSEDED' && (
          <p className="sched-note">This run was superseded; it is kept unchanged for comparison.</p>
        )}
        {run?.notes ? <p className="sched-run-notes">{text(run.notes)}</p> : null}
      </Panel>

      <Panel
        title="Gantt"
        subtitle="Work centers over time (Sri Lanka time). Hatched: maintenance or downtime; grey: not working."
        className="sched-gantt-panel"
        action={
          <div className="sched-gantt-controls">
            <div className="week-nav">
              <button
                className="button secondary small"
                aria-label="Earlier"
                onClick={() => setFrom(addDays(start, -z.days))}
                disabled={!start}
              >
                <ChevronLeft size={15} />
              </button>
              <button className="button secondary small" onClick={() => setFrom(today())}>
                <CalendarRange size={14} />
                Now
              </button>
              <button
                className="button secondary small"
                aria-label="Later"
                onClick={() => setFrom(addDays(start, z.days))}
                disabled={!start}
              >
                <ChevronRight size={15} />
              </button>
            </div>
            <Tabs
              label="Zoom"
              compact
              items={ZOOMS.map((x) => ({ value: x.value, label: x.label }))}
              value={zoom}
              onChange={setZoom}
            />
            <label className="field compact-field sched-colour">
              <span className="sr-only">Colour bars by</span>
              <select value={colourBy} onChange={(e) => setColourBy(e.target.value as ColourBy)}>
                <option value="priority">Colour by priority</option>
                <option value="late">Colour by lateness</option>
                <option value="order">Colour by order</option>
              </select>
            </label>
          </div>
        }
      >
        {gantt.isPending ? (
          <div className="server-loading">
            <div className="skeleton" />
          </div>
        ) : gantt.isError ? (
          <div className="form-error server-error" role="alert">
            {(gantt.error as Error).message}
          </div>
        ) : gantt.data ? (
          <>
            <Gantt
              data={gantt.data}
              pxPerHour={z.pxPerHour}
              colourBy={colourBy}
              selected={selected?.bar.id}
              onSelect={(bar, machine) => setSelected({ bar, machine })}
              canMove={planner}
              onMove={(bar, machine, at, origin) =>
                setPin({ bar, machineCode: text(origin.code), machineId: machine.id, start: at })
              }
            />
            <GanttLegend colourBy={colourBy} />
            {planner && (
              <p className="sched-hint">
                Drag an operation that has not started to another time or work center to pin it there, or
                select it and use “Pin / move”.
              </p>
            )}
          </>
        ) : null}
        {selected && (
          <BarDetail
            key={selected.bar.id}
            bar={selected.bar}
            machine={selected.machine}
            onClose={() => setSelected(undefined)}
            onPin={() => setPin({ bar: selected.bar, machineCode: text(selected.machine.code) })}
          />
        )}
      </Panel>

      <div className="sched-columns">
        <Panel
          title="Orders"
          subtitle="Projected completion against the due date"
          className="sched-orders-panel"
        >
          {detail.isPending ? (
            <div className="server-loading">
              <div className="skeleton" />
            </div>
          ) : detail.isError ? (
            <div className="form-error server-error" role="alert">
              {(detail.error as Error).message}
            </div>
          ) : (
            <OrdersPanel key={runId} orders={detail.data?.orders || []} editable={planner} />
          )}
        </Panel>
        <Panel
          title="Capacity load"
          subtitle="Scheduled vs available minutes per work center and day (first two weeks)"
          className="sched-capacity-panel"
        >
          {capacity.isPending ? (
            <div className="server-loading">
              <div className="skeleton" />
            </div>
          ) : capacity.isError ? (
            <div className="form-error server-error" role="alert">
              {(capacity.error as Error).message}
            </div>
          ) : capacity.data ? (
            <CapacityHeatmap data={capacity.data} />
          ) : null}
        </Panel>
      </div>

      <AnimatePresence>
        {pin && (
          <PinDialog
            key="pin"
            bar={pin.bar}
            machineCode={pin.machineCode}
            machineId={pin.machineId}
            start={pin.start}
            onClose={() => setPin(undefined)}
          />
        )}
        {compare && run && <CompareDialog key="compare" run={run} onClose={() => setCompare(false)} />}
      </AnimatePresence>
    </>
  );
}

function GanttLegend({ colourBy }: { colourBy: ColourBy }) {
  const tones =
    colourBy === 'late'
      ? [
          ['late', 'Late'],
          ['ontime', 'On time'],
        ]
      : colourBy === 'priority'
        ? [
            ['p-urgent', 'Urgent'],
            ['p-high', 'High'],
            ['p-normal', 'Normal'],
          ]
        : [];
  return (
    <div className="sched-legend" aria-hidden="true">
      {tones.map(([tone, label]) => (
        <span key={tone}>
          <i className={`swatch-bar ${tone}`} />
          {label}
        </span>
      ))}
      <span>
        <i className="swatch-off" />
        Not working
      </span>
      <span>
        <i className="swatch-holiday" />
        Holiday
      </span>
      <span>
        <i className="swatch-block maintenance" />
        Maintenance
      </span>
      <span>
        <i className="swatch-block downtime" />
        Downtime
      </span>
      <span>
        <i className="swatch-pin" />
        Pinned
      </span>
      <span>
        <i className="swatch-now" />
        Now
      </span>
    </div>
  );
}
