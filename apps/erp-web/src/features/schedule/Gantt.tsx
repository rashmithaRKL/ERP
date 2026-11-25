import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { KeyboardEvent, PointerEvent } from 'react';
import { text } from '../../lib/format';
import { addDays, clock, dayLabel, dayStart, duration, pinnable, when } from './api';
import type { Bar, GanttData, GanttMachine } from './api';

export type ColourBy = 'priority' | 'late' | 'order';

const HEADER = 46;
const ROW = 48;
const HOUR = 3_600_000;
const SNAP = 15 * 60_000;

/** Stable palette index for an order id (colour by order). */
function orderTone(id: string) {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0;
  return h % 8;
}

function barClass(bar: Bar, colourBy: ColourBy) {
  const tone =
    colourBy === 'late'
      ? bar.late
        ? 'late'
        : 'ontime'
      : colourBy === 'order'
        ? `o-${orderTone(text(bar.order_id))}`
        : `p-${text(bar.priority).toLowerCase()}`;
  return `gantt-bar ${tone}${bar.pin_status === 'HONOURED' ? ' pinned' : ''}${bar.running ? ' running' : ''}`;
}

export function barLabel(bar: Bar) {
  return `${text(bar.order_number)}${bar.operation_id ? ` · ${text(bar.seq)} ${text(bar.name)}` : ' · order run'}`;
}

type Drag = { bar: Bar; row: number; x: number; y: number; dx: number; dy: number; moved: boolean };

/**
 * The schedule Gantt (plain SVG): one row per work center, a local-time axis with day and hour gridlines, non-working
 * time (calendar), holidays, blocked maintenance/downtime, operation bars split into their working segments, and a
 * "now" line. The timeline scrolls horizontally inside its frame; the work-center labels stay put. Bars are buttons
 * (Tab, Enter/Space selects); planners may drag a not-yet-started operation sideways (time, 15-minute steps) or to
 * another row (work center), which proposes a pin through `onMove`.
 */
export function Gantt({
  data,
  pxPerHour,
  colourBy,
  selected,
  onSelect,
  canMove,
  onMove,
}: {
  data: GanttData;
  pxPerHour: number;
  colourBy: ColourBy;
  selected?: string;
  onSelect: (bar: Bar, machine: GanttMachine) => void;
  canMove: boolean;
  onMove: (bar: Bar, machine: GanttMachine, start: string, origin: GanttMachine) => void;
}) {
  const id = useId().replaceAll(':', '');
  const scroller = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<Drag>();
  const t0 = dayStart(data.from);
  const t1 = dayStart(addDays(data.to, 1));
  const width = Math.round(((t1 - t0) / HOUR) * pxPerHour);
  const height = HEADER + data.machines.length * ROW;
  const x = (ms: number) => ((ms - t0) / HOUR) * pxPerHour;
  const xi = (iso: string) => x(Date.parse(iso));
  const clamp = (v: number) => Math.max(0, Math.min(width, v));

  const days = useMemo(() => {
    const out: string[] = [];
    for (let d = data.from; d <= data.to; d = addDays(d, 1)) out.push(d);
    return out;
  }, [data.from, data.to]);
  const tickHours = pxPerHour >= 30 ? 1 : pxPerHour >= 14 ? 3 : 6;
  const labelEvery = pxPerHour * tickHours >= 26 ? 1 : 2;

  // Scroll "now" (or the window start) into view when the window or zoom changes.
  const nowMs = Date.parse(data.now);
  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    el.scrollLeft = nowMs > t0 && nowMs < t1 ? Math.max(0, x(nowMs) - 60) : 0;
  }, [data.from, data.to, pxPerHour]);

  function offTime(m: GanttMachine) {
    const spans = [...m.working]
      .map((w) => [Date.parse(w.start), Date.parse(w.end)] as const)
      .sort((a, b) => a[0] - b[0]);
    const out: [number, number][] = [];
    let cursor = t0;
    for (const [s, e] of spans) {
      if (s > cursor) out.push([cursor, Math.min(s, t1)]);
      cursor = Math.max(cursor, e);
    }
    if (cursor < t1) out.push([cursor, t1]);
    return out.filter(([s, e]) => e > s);
  }

  function key(e: KeyboardEvent, bar: Bar, m: GanttMachine) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      onSelect(bar, m);
    }
  }

  function down(e: PointerEvent, bar: Bar, row: number) {
    if (!canMove || !pinnable(bar) || e.button !== 0) return;
    (e.currentTarget as Element).setPointerCapture(e.pointerId);
    setDrag({ bar, row, x: e.clientX, y: e.clientY, dx: 0, dy: 0, moved: false });
  }

  function move(e: PointerEvent) {
    if (!drag) return;
    const dx = e.clientX - drag.x;
    const dy = e.clientY - drag.y;
    setDrag({ ...drag, dx, dy, moved: drag.moved || Math.abs(dx) > 4 || Math.abs(dy) > 8 });
  }

  function target(d: Drag) {
    const rowShift = Math.round(d.dy / ROW);
    const row = Math.max(0, Math.min(data.machines.length - 1, d.row + rowShift));
    const shift = Math.round(((d.dx / pxPerHour) * HOUR) / SNAP) * SNAP;
    const start = Date.parse(text(d.bar.start_at)) + shift;
    return { row, start };
  }

  function up() {
    if (!drag) return;
    const d = drag;
    setDrag(undefined);
    if (!d.moved) return;
    const { row, start } = target(d);
    onMove(d.bar, data.machines[row], new Date(start).toISOString(), data.machines[d.row]);
  }

  const ghost = drag?.moved ? target(drag) : undefined;
  const now = nowMs >= t0 && nowMs <= t1 ? x(nowMs) : undefined;

  return (
    <div className="gantt">
      <div className="gantt-labels" aria-hidden="true">
        <div className="gantt-label-head">Work center</div>
        {data.machines.map((m) => (
          <div key={m.id} className="gantt-label">
            <strong>{text(m.code)}</strong>
            <small>
              {m.status === 'MAINTENANCE' ? 'Under maintenance' : text(m.calendar_code) || 'Around the clock'}
            </small>
          </div>
        ))}
      </div>
      <div
        className="gantt-scroll"
        ref={scroller}
        tabIndex={0}
        role="region"
        aria-label={`Schedule from ${dayLabel(data.from)} to ${dayLabel(data.to)}; scroll sideways for more time`}
      >
        <svg
          width={width}
          height={height}
          className={`gantt-svg${drag ? ' dragging' : ''}`}
          onPointerMove={move}
          onPointerUp={up}
          onPointerCancel={() => setDrag(undefined)}
        >
          <defs>
            <pattern
              id={`${id}-hatch`}
              width="6"
              height="6"
              patternUnits="userSpaceOnUse"
              patternTransform="rotate(45)"
            >
              <rect width="6" height="6" className="gantt-hatch-bg" />
              <line x1="0" y1="0" x2="0" y2="6" className="gantt-hatch-line" />
            </pattern>
            <pattern
              id={`${id}-stripe`}
              width="8"
              height="8"
              patternUnits="userSpaceOnUse"
              patternTransform="rotate(45)"
            >
              <line x1="0" y1="0" x2="0" y2="8" className="gantt-stripe-line" />
            </pattern>
          </defs>

          {/* rows */}
          {data.machines.map((m, i) => (
            <rect
              key={m.id}
              x={0}
              y={HEADER + i * ROW}
              width={width}
              height={ROW}
              className={`gantt-row ${i % 2 ? 'odd' : ''}`}
            />
          ))}

          {/* non-working time, holidays and blocked time */}
          {data.machines.map((m, i) => (
            <g key={`off-${m.id}`}>
              {offTime(m).map(([s, e]) => (
                <rect
                  key={s}
                  x={x(s)}
                  y={HEADER + i * ROW}
                  width={x(e) - x(s)}
                  height={ROW}
                  className="gantt-off"
                />
              ))}
              {m.closed.map((c) => {
                const s = x(dayStart(c.date));
                const w = x(dayStart(addDays(c.date, 1))) - s;
                return (
                  <g key={c.date}>
                    <rect x={s} y={HEADER + i * ROW} width={w} height={ROW} className="gantt-holiday" />
                    {w > 70 && (
                      <text x={s + 6} y={HEADER + i * ROW + ROW - 8} className="gantt-holiday-text">
                        {c.reason.length > 28 && w < 200 ? `${c.reason.substring(0, 26)}…` : c.reason}
                      </text>
                    )}
                    <title>{`${dayLabel(c.date)}: closed (${c.reason})`}</title>
                  </g>
                );
              })}
              {m.working.map((w, k) => (
                <line
                  key={k}
                  x1={xi(w.start)}
                  x2={xi(w.start)}
                  y1={HEADER + i * ROW}
                  y2={HEADER + (i + 1) * ROW}
                  className="gantt-shift-line"
                />
              ))}
              {m.blocks.map((b) => {
                const s = clamp(xi(text(b.start_at)));
                const e = clamp(xi(text(b.end_at)));
                return (
                  <g key={b.id}>
                    <rect
                      x={s}
                      y={HEADER + i * ROW + 3}
                      width={Math.max(2, e - s)}
                      height={ROW - 6}
                      className={`gantt-block ${text(b.kind).toLowerCase()}`}
                      fill={`url(#${id}-hatch)`}
                    />
                    {e - s > 60 && (
                      <text x={s + 5} y={HEADER + i * ROW + 16} className="gantt-block-text">
                        {text(b.reference)}
                      </text>
                    )}
                    <title>
                      {`${b.kind === 'MAINTENANCE' ? 'Maintenance' : 'Downtime'} ${text(b.reference)}: ${text(b.label)} · ${when(b.start_at)} – ${when(b.end_at)}${b.assumed_end ? ' (assumed end)' : ''}`}
                    </title>
                  </g>
                );
              })}
            </g>
          ))}

          {/* time axis */}
          <rect x={0} y={0} width={width} height={HEADER} className="gantt-head" />
          {days.map((d) => {
            const s = x(dayStart(d));
            const ticks = [];
            for (let h = tickHours, n = 1; h < 24; h += tickHours, n++)
              ticks.push(
                <g key={h}>
                  <line
                    x1={s + h * pxPerHour}
                    x2={s + h * pxPerHour}
                    y1={HEADER - 6}
                    y2={height}
                    className="gantt-hour-line"
                  />
                  {n % labelEvery === 0 && (
                    <text x={s + h * pxPerHour} y={HEADER - 7} className="gantt-hour-text">
                      {String(h).padStart(2, '0')}
                    </text>
                  )}
                </g>,
              );
            return (
              <g key={d}>
                {ticks}
                <line x1={s} x2={s} y1={0} y2={height} className="gantt-day-line" />
                <text x={s + 6} y={15} className="gantt-day-text">
                  {dayLabel(d)}
                </text>
              </g>
            );
          })}
          <line x1={0} x2={width} y1={HEADER} y2={HEADER} className="gantt-day-line" />

          {/* operations */}
          {data.machines.map((m, i) =>
            m.bars.map((bar) => {
              const y = HEADER + i * ROW;
              const s = clamp(xi(text(bar.start_at)));
              const e = clamp(xi(text(bar.end_at)));
              const segs = bar.segments.length
                ? bar.segments
                : [{ start: text(bar.start_at), end: text(bar.end_at) }];
              const widest = Math.max(...segs.map((g) => clamp(xi(g.end)) - clamp(xi(g.start))));
              const first = segs.find((g) => clamp(xi(g.end)) - clamp(xi(g.start)) === widest) || segs[0];
              const label = barLabel(bar);
              const movable = canMove && pinnable(bar);
              return (
                <g
                  key={bar.id}
                  role="button"
                  tabIndex={0}
                  className={`${barClass(bar, colourBy)}${selected === bar.id ? ' selected' : ''}${movable ? ' movable' : ''}`}
                  aria-pressed={selected === bar.id}
                  aria-label={`${label} on ${text(m.code)}, ${when(bar.start_at)} to ${when(bar.end_at)}, ${duration(bar.minutes)}${bar.late ? ', late' : ''}${bar.pin_status === 'HONOURED' ? ', pinned' : ''}`}
                  onClick={() => onSelect(bar, m)}
                  onKeyDown={(ev) => key(ev, bar, m)}
                  onPointerDown={(ev) => down(ev, bar, i)}
                >
                  <rect
                    x={s}
                    y={y + ROW / 2 - 1}
                    width={Math.max(1, e - s)}
                    height={2}
                    className="gantt-span"
                  />
                  {segs.map((g) => {
                    const gs = clamp(xi(g.start));
                    const ge = clamp(xi(g.end));
                    return ge > gs || segs.length === 1 ? (
                      <g key={g.start}>
                        <rect
                          x={gs}
                          y={y + 9}
                          width={Math.max(3, ge - gs)}
                          height={ROW - 18}
                          rx={4}
                          className="gantt-seg"
                        />
                        {bar.running && (
                          <rect
                            x={gs}
                            y={y + 9}
                            width={Math.max(3, ge - gs)}
                            height={ROW - 18}
                            rx={4}
                            fill={`url(#${id}-stripe)`}
                          />
                        )}
                      </g>
                    ) : null;
                  })}
                  {bar.pin_status === 'HONOURED' && (
                    <path d={`M${s} ${y + 4} l5 0 l-2.5 5 z`} className="gantt-pin-mark" />
                  )}
                  {widest > 56 && (
                    <text x={clamp(xi(first.start)) + 6} y={y + ROW / 2 + 4} className="gantt-bar-text">
                      {widest > 150 ? label : text(bar.order_number)}
                    </text>
                  )}
                  <title>
                    {`${label}\n${text(m.code)}${bar.alternate ? ' (alternate)' : ''} · ${when(bar.start_at)} – ${clock(bar.end_at)} · ${duration(bar.minutes)}${bar.late ? '\nLate against due date' : ''}${bar.pin_status === 'HONOURED' ? '\nPinned' : bar.pin_status === 'CONFLICT' ? `\nPin not honoured: ${text(bar.pin_note)}` : ''}`}
                  </title>
                </g>
              );
            }),
          )}

          {ghost && drag && (
            <rect
              x={x(ghost.start)}
              y={HEADER + ghost.row * ROW + 9}
              width={Math.max(3, xi(text(drag.bar.end_at)) - xi(text(drag.bar.start_at)))}
              height={ROW - 18}
              rx={4}
              className="gantt-ghost"
            />
          )}

          {now !== undefined && (
            <g className="gantt-now">
              <line x1={now} x2={now} y1={HEADER - 14} y2={height} />
              <text x={now + 4} y={HEADER - 20}>
                Now
              </text>
            </g>
          )}
        </svg>
      </div>
    </div>
  );
}
