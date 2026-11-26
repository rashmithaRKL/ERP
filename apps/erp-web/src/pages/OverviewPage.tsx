import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion } from 'motion/react';
import {
  ArrowRight,
  ArrowUpRight,
  Boxes,
  ChartNoAxesCombined,
  CheckCheck,
  CircleDollarSign,
  Cpu,
  Factory,
  ShieldCheck,
  SlidersHorizontal,
} from 'lucide-react';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { useStateData, useWorkspace } from '../app/workspace';
import { Badge, Panel, Stat, Table } from '../components';
import { useAttention } from '../features/attention/useAttention';
import { useOrderColumns } from '../features/manufacturing/orderColumns';
import { OverviewOee } from '../features/performance/Snippets';
import type { State } from '../lib/api';
import { fmt, num, text, today } from '../lib/format';
import { stagger, Tabs } from '../motion/motion';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const RED = '#d72638';

/** One chart point. Complete days draw the solid series; the open day (today) draws the dashed `*Today` segment. */
type TrendPoint = {
  day: string;
  good: number;
  scrapped: number;
  partial: boolean;
  goodDone: number | null;
  scrapDone: number | null;
  goodToday: number | null;
  scrapToday: number | null;
};

/**
 * The API's daily series ends with the current Asia/Colombo day (`DD Mon`), which is still being produced. It is drawn
 * as a dashed segment ending in a hollow point instead of a drop to zero.
 */
function trendPoints(trend: State['trend']): TrendPoint[] {
  const [, month, day] = today().split('-');
  const todayLabel = `${day} ${MONTHS[Number(month) - 1]}`;
  const last = trend.length - 1;
  const open = last > 0 && trend[last].day === todayLabel;
  return trend.map((point, i) => {
    const good = num(point.completed);
    const scrapped = num(point.scrap);
    const done = !open || i < last;
    const bridge = open && i >= last - 1;
    return {
      day: point.day,
      good,
      scrapped,
      partial: open && i === last,
      goodDone: done ? good : null,
      scrapDone: done ? scrapped : null,
      goodToday: bridge ? good : null,
      scrapToday: bridge ? scrapped : null,
    };
  });
}

const plural = (count: number, one: string, many: string) => `${fmt(count)} ${count === 1 ? one : many}`;

/** Route `/`: factory overview dashboard. */
export default function OverviewPage() {
  const navigate = useNavigate();
  const { days, setDays, openOrder, summary } = useWorkspace();
  const { active, output, scrap, yieldPct } = summary;
  const data = useStateData();
  const points = trendPoints(data.trend);
  const partialDay = points.find((p) => p.partial);
  const produced = output + scrap > 0 || points.some((p) => p.good || p.scrapped);
  const overdue = num(data.metrics.overdue);
  const orderColumns = useOrderColumns();
  const [tab, setTab] = useState('all');
  // One attention model for this panel, the attention center and the header bell (features/attention).
  const attention = useAttention();
  return (
    <>
      <motion.div className="stats-grid" variants={stagger} initial="hidden" animate="visible">
        <Stat
          label={data.financial ? 'Sales revenue' : 'Pending inspections'}
          value={
            data.financial
              ? `Rs. ${fmt(num(data.financial.revenue) / 1000000, 2)}M`
              : fmt(data.metrics.pending_qc)
          }
          icon={data.financial ? CircleDollarSign : ShieldCheck}
          note={data.financial ? 'Recognized on dispatch' : 'Waiting for quality review'}
          foot={data.financial ? 'LKR' : undefined}
          tone="green"
        />
        <Stat
          label="Active manufacturing orders"
          value={fmt(data.metrics.active_orders)}
          icon={Factory}
          note={overdue ? `${plural(overdue, 'order needs', 'orders need')} attention` : 'No overdue orders'}
          tone={overdue ? 'amber' : 'neutral'}
        />
        <Stat
          label="Good output recorded"
          value={fmt(output)}
          icon={Boxes}
          note={produced ? `Last ${days} days incl. today` : `Nothing recorded in ${days} days`}
          foot="pieces"
        />
        <Stat
          label="Production yield"
          value={output + scrap > 0 ? `${fmt(yieldPct, 1)}%` : '—'}
          icon={ChartNoAxesCombined}
          note={
            output + scrap > 0 ? `${plural(scrap, 'piece', 'pieces')} of scrap` : 'No output in this period'
          }
          tone={output + scrap > 0 ? 'green' : 'neutral'}
        />
      </motion.div>
      <div className="dashboard-middle">
        <Panel
          title="Production performance"
          subtitle="Good output recorded across your manufacturing orders"
          action={<span className="chart-period">{days}-day view</span>}
          className="production-chart-panel"
        >
          <div className="chart-summary">
            <div>
              <strong>
                {fmt(output)}
                <span>pcs</span>
              </strong>
              <span className="chart-caption">Total good output</span>
            </div>
            {produced && (
              <div className="chart-legend">
                <span>
                  <i className="legend-dot red" />
                  Good output
                </span>
                <span>
                  <i className="legend-dot gray" />
                  Scrap
                </span>
                {partialDay && (
                  <span>
                    <i className="legend-dot partial" />
                    Today, so far
                  </span>
                )}
              </div>
            )}
          </div>
          {produced ? (
            <div
              className="production-chart"
              role="img"
              aria-label={`Daily good output and scrap for the last ${days} days: ${fmt(output)} pieces good, ${fmt(scrap)} scrap${partialDay ? `; today so far ${fmt(partialDay.good)} good` : ''}.`}
            >
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={points} margin={{ top: 10, right: 12, left: -18, bottom: 0 }}>
                  <defs>
                    <linearGradient id="outputFill" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor={RED} stopOpacity={0.15} />
                      <stop offset="95%" stopColor={RED} stopOpacity={0.01} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 4" vertical={false} stroke="var(--border)" />
                  <XAxis
                    dataKey="day"
                    tickLine={false}
                    axisLine={false}
                    minTickGap={28}
                    tick={{ fontSize: 10, fill: 'var(--muted)' }}
                    dy={10}
                  />
                  <YAxis
                    tickFormatter={(v) => (v >= 1000 ? `${v / 1000}k` : v)}
                    axisLine={false}
                    tickLine={false}
                    allowDecimals={false}
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
                              {point.day}
                              {point.partial && <em> · today, so far</em>}
                            </strong>
                            <span>
                              <i className="legend-dot red" />
                              Good output <b>{fmt(point.good)}</b>
                            </span>
                            <span>
                              <i className="legend-dot gray" />
                              Scrap <b>{fmt(point.scrapped)}</b>
                            </span>
                          </div>
                        )
                      );
                    }}
                  />
                  <Area
                    isAnimationActive={false}
                    type="monotone"
                    dataKey="goodDone"
                    stroke={RED}
                    strokeWidth={2.5}
                    fill="url(#outputFill)"
                    activeDot={{ r: 4, stroke: 'var(--surface)', strokeWidth: 2 }}
                  />
                  <Area
                    isAnimationActive={false}
                    type="monotone"
                    dataKey="scrapDone"
                    stroke="#9ba7b9"
                    strokeWidth={1.5}
                    fill="transparent"
                    strokeDasharray="4 4"
                  />
                  {partialDay && (
                    <Area
                      isAnimationActive={false}
                      type="linear"
                      dataKey="goodToday"
                      stroke={RED}
                      strokeOpacity={0.75}
                      strokeWidth={2}
                      strokeDasharray="3 4"
                      fill="transparent"
                      activeDot={false}
                      dot={({ cx, cy, index }) =>
                        index === points.length - 1 ? (
                          <circle
                            key="today"
                            cx={cx}
                            cy={cy}
                            r={4}
                            fill="var(--surface)"
                            stroke={RED}
                            strokeWidth={2}
                          />
                        ) : null
                      }
                    />
                  )}
                  {partialDay && (
                    <Area
                      isAnimationActive={false}
                      type="linear"
                      dataKey="scrapToday"
                      stroke="#9ba7b9"
                      strokeOpacity={0.6}
                      strokeWidth={1.5}
                      strokeDasharray="1 4"
                      fill="transparent"
                      activeDot={false}
                    />
                  )}
                </AreaChart>
              </ResponsiveContainer>
            </div>
          ) : (
            <div className="production-chart chart-empty">
              <ChartNoAxesCombined size={28} strokeWidth={1.5} />
              <strong>No production recorded in the last {days} days</strong>
              <p>Good output and scrap appear here as soon as an operator records production on an order.</p>
              {days < 90 && (
                <button className="button secondary small" onClick={() => setDays(90)}>
                  Show the last 90 days
                </button>
              )}
            </div>
          )}
          <div className="chart-footer">
            <span>
              <CheckCheck size={14} />
              Based on posted production records
            </span>
            <button className="text-button" onClick={() => navigate('/reports')}>
              View report
              <ArrowRight size={13} />
            </button>
          </div>
        </Panel>
        <Panel
          title="Factory floor"
          subtitle="Registered work-center status"
          action={
            <button
              className="icon-button"
              aria-label="View work centers"
              onClick={() => navigate('/machines')}
            >
              <ArrowUpRight size={17} />
            </button>
          }
          className="floor-panel"
        >
          <div className="floor-summary">
            <div className="floor-summary-count">
              <strong>
                {data.machines.filter((m) => m.status === 'RUNNING').length}
                <span>/{data.machines.length}</span>
              </strong>
              <p>work centers running</p>
            </div>
            <div className="floor-ring">
              <svg viewBox="0 0 80 80">
                <circle cx="40" cy="40" r="31" fill="none" stroke="var(--soft)" strokeWidth="7" />
                <circle
                  cx="40"
                  cy="40"
                  r="31"
                  fill="none"
                  stroke="#27967a"
                  strokeWidth="7"
                  strokeLinecap="round"
                  strokeDasharray={`${(data.machines.filter((m) => m.status === 'RUNNING').length / data.machines.length) * 195} 195`}
                  transform="rotate(-90 40 40)"
                />
              </svg>
              <Factory size={22} />
            </div>
          </div>
          <div className="machine-list">
            {data.machines.slice(0, 5).map((m) => (
              <div className="machine-row" key={m.id}>
                <span className={`machine-symbol ${m.status === 'MAINTENANCE' ? 'warning' : ''}`}>
                  <Cpu size={16} />
                </span>
                <div>
                  <strong>{text(m.code)}</strong>
                  <span>{text(m.kind)}</span>
                </div>
                <Badge value={m.status} />
              </div>
            ))}
          </div>
          {/* V15: yesterday's measured plant OEE (null = not measured; never a fabricated figure). */}
          <OverviewOee performance={data.performance} />
          <button className="floor-link" onClick={() => navigate('/shopfloor')}>
            Open shop floor
            <ArrowRight size={14} />
          </button>
        </Panel>
      </div>
      <div className="dashboard-bottom">
        <Panel
          title="Manufacturing orders"
          subtitle="A closer look at what’s moving through your factory"
          action={
            <button className="text-button" onClick={() => navigate('/manufacturing')}>
              View all orders
              <ArrowRight size={13} />
            </button>
          }
          className="orders-panel"
        >
          <Tabs
            compact
            label="Dashboard order filters"
            items={[
              { value: 'all', label: 'Active orders', count: active.length },
              {
                value: 'DRAFT',
                label: 'Awaiting approval',
                count: active.filter((r) => r.status === 'DRAFT').length,
              },
            ]}
            value={tab}
            onChange={setTab}
          >
            <span className="grow" />
            <button
              className="icon-button"
              aria-label="Manage and filter orders"
              onClick={() => navigate('/manufacturing')}
            >
              <SlidersHorizontal size={15} />
            </button>
          </Tabs>
          <Table
            rows={active.filter((r) => tab === 'all' || r.status === tab)}
            columns={orderColumns.filter((c) => c.label !== 'Due date')}
            compact
            pageSize={5}
            onRow={(r) => openOrder(r.id)}
          />
        </Panel>
        <Panel
          title="Needs your attention"
          action={<span className="attention-count">{fmt(attention.total)}</span>}
          className="attention-panel"
        >
          <div className="attention-list">
            {attention.groups.map((group) => (
              <button key={group.key} className="attention-item" onClick={() => navigate(group.path)}>
                <span className={`attention-symbol ${group.tone}`}>
                  <group.icon size={16} />
                </span>
                <div>
                  <strong>{group.title}</strong>
                  <p>{group.body}</p>
                  <span>
                    {group.action}
                    <ArrowRight size={11} />
                  </span>
                </div>
              </button>
            ))}
            {attention.caughtUp && (
              <div className="attention-empty">
                <CheckCheck size={16} />
                <span>You’re all caught up. Nothing needs attention right now.</span>
              </div>
            )}
          </div>
          <div className="attention-foot">
            <ShieldCheck size={14} />A little attention now. Smoother production later.
          </div>
        </Panel>
      </div>
    </>
  );
}
