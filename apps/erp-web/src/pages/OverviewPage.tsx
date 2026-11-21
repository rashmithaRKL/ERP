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
  ClipboardCheck,
  Cpu,
  Factory,
  Package,
  ShieldCheck,
  SlidersHorizontal,
  Timer,
} from 'lucide-react';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { useStateData, useWorkspace } from '../app/workspace';
import { Badge, Panel, Stat, Table } from '../components';
import { useOrderColumns } from '../features/manufacturing/orderColumns';
import { fmt, num, text } from '../lib/format';
import { stagger, Tabs } from '../motion/motion';

/** Route `/`: factory overview dashboard. */
export default function OverviewPage() {
  const navigate = useNavigate();
  const { days, openOrder, summary } = useWorkspace();
  const { active, shortages, pendingQc, output, scrap, yieldPct } = summary;
  const data = useStateData();
  const orderColumns = useOrderColumns();
  const [tab, setTab] = useState('all');
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
          note={`${data.metrics.overdue} order needs attention`}
          tone={num(data.metrics.overdue) ? 'amber' : 'neutral'}
        />
        <Stat
          label="Good output recorded"
          value={fmt(output)}
          icon={Boxes}
          note={`${days}-day production output`}
          foot="pieces"
        />
        <Stat
          label="Production yield"
          value={`${fmt(yieldPct, 1)}%`}
          icon={ChartNoAxesCombined}
          note={`${fmt(scrap)} pieces of scrap`}
          tone="green"
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
            <div className="chart-legend">
              <span>
                <i className="legend-dot red" />
                Good output
              </span>
              <span>
                <i className="legend-dot gray" />
                Scrap
              </span>
            </div>
          </div>
          <div className="production-chart">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={data.trend} margin={{ top: 10, right: 12, left: -18, bottom: 0 }}>
                <defs>
                  <linearGradient id="outputFill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#d72638" stopOpacity={0.15} />
                    <stop offset="95%" stopColor="#d72638" stopOpacity={0.01} />
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
                  tick={{ fontSize: 10, fill: 'var(--muted)' }}
                />
                <Tooltip
                  contentStyle={{
                    background: 'var(--surface)',
                    border: '1px solid var(--border)',
                    borderRadius: 8,
                    fontSize: 12,
                  }}
                  formatter={(v, name) => [fmt(v), name === 'completed' ? 'Good output' : 'Scrap']}
                />
                <Area
                  isAnimationActive={false}
                  type="monotone"
                  dataKey="completed"
                  stroke="#d72638"
                  strokeWidth={2.5}
                  fill="url(#outputFill)"
                  activeDot={{ r: 4, stroke: '#fff', strokeWidth: 2 }}
                />
                <Area
                  isAnimationActive={false}
                  type="monotone"
                  dataKey="scrap"
                  stroke="#9ba7b9"
                  strokeWidth={1.5}
                  fill="transparent"
                  strokeDasharray="4 4"
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
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
          action={
            <span className="attention-count">
              {shortages.length + pendingQc.length + num(data.metrics.overdue)}
            </span>
          }
          className="attention-panel"
        >
          <div className="attention-list">
            {shortages.slice(0, 2).map((r) => (
              <button key={r.id} className="attention-item" onClick={() => navigate('/planning')}>
                <span className="attention-symbol amber">
                  <Package size={16} />
                </span>
                <div>
                  <strong>{text(r.name).split(' · ')[0]} running low</strong>
                  <p>
                    {fmt(r.shortage, 2)} {text(r.uom)} below planning requirement
                  </p>
                  <span>
                    Review material planning
                    <ArrowRight size={11} />
                  </span>
                </div>
              </button>
            ))}
            {!!pendingQc.length && (
              <button className="attention-item" onClick={() => navigate('/quality')}>
                <span className="attention-symbol blue">
                  <ClipboardCheck size={16} />
                </span>
                <div>
                  <strong>{pendingQc.length} inspections awaiting review</strong>
                  <p>Quarantined stock needs a quality decision.</p>
                  <span>
                    Open quality control
                    <ArrowRight size={11} />
                  </span>
                </div>
              </button>
            )}
            {num(data.metrics.overdue) > 0 && (
              <button className="attention-item" onClick={() => navigate('/manufacturing')}>
                <span className="attention-symbol red">
                  <Timer size={16} />
                </span>
                <div>
                  <strong>{text(data.metrics.overdue)} overdue manufacturing order</strong>
                  <p>Review progress and delivery commitments.</p>
                  <span>
                    Review overdue orders
                    <ArrowRight size={11} />
                  </span>
                </div>
              </button>
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
