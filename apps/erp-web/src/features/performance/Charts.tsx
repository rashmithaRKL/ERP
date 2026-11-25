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
import { hours, pct, waterfallBars } from './format';
import type { WaterfallStep } from './format';
import './performance.css';

const LINES = [
  { key: 'oee', label: 'OEE', color: '#d72638', width: 2.5 },
  { key: 'availability', label: 'Availability', color: 'var(--tone-blue)', width: 1.5 },
  { key: 'performance', label: 'Performance', color: 'var(--tone-amber)', width: 1.5 },
  { key: 'quality', label: 'Quality', color: 'var(--tone-green)', width: 1.5 },
] as const;

type TrendPoint = { label: string; partial: boolean } & Record<string, number | null | string | boolean>;

const percentOf = (value: number | null | undefined) =>
  value == null ? null : Math.round(value * 1000) / 10;

/** Plant OEE, availability, performance and quality per day or week; days without a measured OEE leave a gap. */
export function TrendChart({ series }: { series: (Figures & Period)[] }) {
  const points: TrendPoint[] = series.map((p) => ({
    label: p.label,
    partial: p.partial,
    oee: percentOf(p.oee),
    availability: percentOf(p.availability),
    performance: percentOf(p.performance),
    quality: percentOf(p.quality),
  }));
  const measured = series.filter((p) => p.oee != null);
  if (!measured.length)
    return (
      <div className="perf-empty">
        <strong>No measured OEE in this range</strong>
        <p>OEE appears once operations log run time and report quantities on a scheduled day.</p>
      </div>
    );
  const summary = measured.map((p) => `${p.label} ${pct(p.oee)}`).join(', ');
  return (
    <>
      <div className="perf-legend" aria-hidden="true">
        {LINES.map((l) => (
          <span key={l.key}>
            <i style={{ background: l.color }} />
            {l.label}
          </span>
        ))}
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
                const point = active ? (payload?.[0]?.payload as TrendPoint | undefined) : undefined;
                return (
                  point && (
                    <div className="chart-tooltip">
                      <strong>
                        {point.label}
                        {point.partial && <em> · so far</em>}
                      </strong>
                      {LINES.map((l) => (
                        <span key={l.key}>
                          <i className="legend-dot" style={{ background: l.color }} />
                          {l.label} <b>{point[l.key] == null ? '—' : `${fmt(point[l.key], 1)}%`}</b>
                        </span>
                      ))}
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
