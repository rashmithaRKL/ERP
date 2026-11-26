import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { fmt } from '../../lib/format';
import type { Figures, Period } from './api';
import { hours, pct, trendRows, waterfallBars } from './format';
import type { TrendRow, WaterfallStep } from './format';
import './performance.css';

const LINES = [
  { key: 'oee', label: 'OEE', color: '#d72638', width: 2.5 },
  { key: 'availability', label: 'Availability', color: 'var(--tone-blue)', width: 1.5 },
  { key: 'performance', label: 'Performance', color: 'var(--tone-amber)', width: 1.5 },
  { key: 'quality', label: 'Quality', color: 'var(--tone-green)', width: 1.5 },
] as const;

/** Plant OEE, availability, performance and quality per day or week; days without a measured OEE leave a gap. An open
 * last period (today so far, or this week so far) is drawn dashed to a hollow point (see `trendRows`). */
export function TrendChart({
  series,
  bucket = 'day',
}: {
  series: (Figures & Period)[];
  bucket?: 'day' | 'week';
}) {
  const points = trendRows(series);
  const open = points.at(-1)?.partial ? points.at(-1) : undefined;
  const measured = series.filter((p) => p.oee != null);
  if (!measured.length)
    return (
      <div className="perf-empty">
        <strong>No measured OEE in this range</strong>
        <p>OEE appears once operations log run time and report quantities on a scheduled day.</p>
      </div>
    );
  const soFar = bucket === 'week' ? 'this week so far' : 'today so far';
  const summary = measured.map((p) => `${p.label}${p.partial ? ` (${soFar})` : ''} ${pct(p.oee)}`).join(', ');
  return (
    <>
      <div className="perf-legend" aria-hidden="true">
        {LINES.map((l) => (
          <span key={l.key}>
            <i style={{ background: l.color }} />
            {l.label}
          </span>
        ))}
        {open && (
          <span>
            <i className="legend-dot partial" />
            {bucket === 'week' ? 'This week, so far' : 'Today, so far'} (dashed)
          </span>
        )}
      </div>
      <div className="perf-chart" role="img" aria-label={`Plant OEE by period: ${summary}.`}>
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={points} margin={{ top: 6, right: 10, left: -20, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 4" vertical={false} stroke="var(--border)" />
            <XAxis
              dataKey="label"
              tickLine={false}
              axisLine={false}
              minTickGap={18}
              tick={{ fontSize: 10, fill: 'var(--muted)' }}
              dy={8}
            />
            <YAxis
              domain={[0, 100]}
              ticks={[0, 25, 50, 75, 100]}
              tickFormatter={(v) => `${v}%`}
              axisLine={false}
              tickLine={false}
              tick={{ fontSize: 10, fill: 'var(--muted)' }}
            />
            <Tooltip
              cursor={{ stroke: 'var(--border)' }}
              content={({ active, payload }) => {
                const point = active ? (payload?.[0]?.payload as TrendRow | undefined) : undefined;
                return (
                  point && (
                    <div className="chart-tooltip">
                      <strong>
                        {point.label}
                        {point.partial && <em> · {soFar}</em>}
                      </strong>
                      {LINES.map((l) => {
                        const value = point[`${l.key}Value`];
                        return (
                          <span key={l.key}>
                            <i className="legend-dot" style={{ background: l.color }} />
                            {l.label} <b>{value == null ? '—' : `${fmt(value, 1)}%`}</b>
                          </span>
                        );
                      })}
                    </div>
                  )
                );
              }}
            />
            {LINES.map((l) => (
              <Line
                key={l.key}
                isAnimationActive={false}
                type="monotone"
                dataKey={l.key}
                stroke={l.color}
                strokeWidth={l.width}
                dot={{ r: l.key === 'oee' ? 3 : 0 }}
                activeDot={{ r: 4, stroke: 'var(--surface)', strokeWidth: 2 }}
                connectNulls={false}
              />
            ))}
            {open &&
              LINES.map((l) => (
                <Line
                  key={`${l.key}-open`}
                  isAnimationActive={false}
                  type="linear"
                  dataKey={`${l.key}Open`}
                  stroke={l.color}
                  strokeOpacity={0.75}
                  strokeWidth={Math.max(l.width - 0.5, 1)}
                  strokeDasharray="3 4"
                  activeDot={false}
                  connectNulls={false}
                  dot={({ cx, cy, index }) =>
                    l.key === 'oee' && index === points.length - 1 && cy != null ? (
                      <circle
                        key="open"
                        cx={cx}
                        cy={cy}
                        r={4}
                        fill="var(--surface)"
                        stroke={l.color}
                        strokeWidth={2}
                      />
                    ) : (
                      <g key={`none-${index}`} />
                    )
                  }
                />
              ))}
          </LineChart>
        </ResponsiveContainer>
      </div>
    </>
  );
}

const BAR_COLORS: Record<string, string> = {
  calendar: 'var(--tone-gray)',
  scheduled: 'var(--tone-blue)',
  planned: 'var(--tone-blue)',
  run: 'var(--tone-teal)',
  netRun: 'var(--tone-teal)',
  fullyProductive: 'var(--tone-green)',
};

/** Calendar → shift → planned production → run → net run → fully productive time, with the loss between each. */
export function LossWaterfall({ steps }: { steps: WaterfallStep[] }) {
  const bars = waterfallBars(steps);
  if (!bars.length || bars[0].minutes <= 0)
    return (
      <div className="perf-empty">
        <strong>No measured planned production time</strong>
        <p>The waterfall covers the work-center days that have a measured OEE.</p>
      </div>
    );
  return (
    <div
      className="perf-chart tall"
      role="img"
      aria-label={`Loss waterfall: ${bars.map((b) => `${b.label} ${hours(b.minutes)}`).join(', ')}.`}
    >
      <ResponsiveContainer width="100%" height="100%">
        <BarChart
          data={bars}
          layout="vertical"
          margin={{ top: 0, right: 16, left: 0, bottom: 0 }}
          barCategoryGap={4}
        >
          <CartesianGrid strokeDasharray="3 4" horizontal={false} stroke="var(--border)" />
          <XAxis
            type="number"
            tickFormatter={(v) => `${fmt(v)} h`}
            axisLine={false}
            tickLine={false}
            tick={{ fontSize: 10, fill: 'var(--muted)' }}
          />
          <YAxis
            type="category"
            dataKey="label"
            width={128}
            axisLine={false}
            tickLine={false}
            interval={0}
            tick={{ fontSize: 10, fill: 'var(--secondary)' }}
          />
          <Tooltip
            cursor={{ fill: 'var(--soft)' }}
            content={({ active, payload }) => {
              const bar = active ? (payload?.[0]?.payload as (typeof bars)[number] | undefined) : undefined;
              return (
                bar && (
                  <div className="chart-tooltip">
                    <strong>{bar.label}</strong>
                    <span>
                      {bar.kind === 'LOSS' ? 'Lost' : 'Time'} <b>{hours(bar.minutes)}</b>
                    </span>
                  </div>
                )
              );
            }}
          />
          <Bar dataKey="base" stackId="w" fill="transparent" isAnimationActive={false} />
          <Bar dataKey="value" stackId="w" radius={[0, 4, 4, 0]} isAnimationActive={false}>
            {bars.map((b) => (
              <Cell
                key={b.key}
                fill={b.kind === 'LOSS' ? 'var(--tone-red)' : BAR_COLORS[b.key] || 'var(--tone-blue)'}
                fillOpacity={b.kind === 'LOSS' ? 0.75 : 0.9}
              />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
