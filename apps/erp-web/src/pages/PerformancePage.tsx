import { useState } from 'react';
import { AnimatePresence, motion } from 'motion/react';
import { Activity, ChartNoAxesCombined, Gauge, Info, Timer } from 'lucide-react';
import { useUrlParams } from '../app/urlState';
import { useStateData } from '../app/workspace';
import { Empty, Panel, Stat } from '../components';
import { useLosses, useOee, usePerformanceSync } from '../features/performance/api';
import type { Filters } from '../features/performance/api';
import {
  DowntimePareto,
  QualityLosses,
  SixLosses,
  UtilizationTable,
  Warnings,
} from '../features/performance/Breakdowns';
import { LossWaterfall, TrendChart } from '../features/performance/Charts';
import { DayDrawer } from '../features/performance/DayDrawer';
import { businessDay, hours, oeeTone, pct } from '../features/performance/format';
import { Heatmap } from '../features/performance/Heatmap';
import { QueryState } from '../features/inventory/shared';
import { fmt, text } from '../lib/format';
import { stagger, Tabs } from '../motion/motion';

const PRESETS = [
  { value: '7', label: '7 days' },
  { value: '14', label: '14 days' },
  { value: '30', label: '30 days' },
  { value: '90', label: '90 days' },
];

/**
 * Route `/performance` (`?machine=`): validated OEE and utilization per work center and day or week, with the plant
 * roll-up, trend, loss waterfall, downtime Pareto, six big losses, quality losses, data-coverage warnings and the
 * work-center day drawer; planners close completed days.
 */
export default function PerformancePage() {
  usePerformanceSync();
  const data = useStateData();
  // The work-center filter lives in the URL (`?machine=`, linked from the machine drawer) so the view can be shared.
  const [params, update] = useUrlParams();
  const [preset, setPreset] = useState('14');
  const [from, setFrom] = useState(() => businessDay(-13));
  const [to, setTo] = useState(() => businessDay(0));
  const machine = params.get('machine') || '';
  const setMachine = (id: string) => update({ machine: id || null });
  const [bucket, setBucket] = useState<Filters['bucket']>('day');
  const [open, setOpen] = useState<{ machineId: string; day: string }>();
  const filters: Filters = { from, to, machine, bucket };
  const oee = useOee(filters);
  const losses = useLosses(filters);
  const today = businessDay(0);

  function choosePreset(value: string) {
    setPreset(value);
    setFrom(businessDay(1 - Number(value)));
    setTo(today);
    if (Number(value) > 31) setBucket('week');
  }

  const plant = oee.data?.plant;
  const coverage = oee.data?.coverage;
  const openDay = (machineId: string, day: string) => setOpen({ machineId, day });
  return (
    <>
      <div className="perf-toolbar">
        <Tabs compact label="Period" items={PRESETS} value={preset} onChange={choosePreset} />
        <label>
          From
          <input
            type="date"
            value={from}
            max={to}
            onChange={(e) => {
              if (e.target.value) {
                setFrom(e.target.value);
                setPreset('custom');
              }
            }}
          />
        </label>
        <label>
          To
          <input
            type="date"
            value={to}
            min={from}
            max={today}
            onChange={(e) => {
              if (e.target.value) {
                setTo(e.target.value);
                setPreset('custom');
              }
            }}
          />
        </label>
        <select aria-label="Work center" value={machine} onChange={(e) => setMachine(e.target.value)}>
          <option value="">All work centers</option>
          {data.machines.map((m) => (
            <option key={m.id} value={m.id}>
              {text(m.code)} · {text(m.name)}
            </option>
          ))}
        </select>
        <select
          aria-label="Group by"
          value={bucket}
          onChange={(e) => setBucket(e.target.value as Filters['bucket'])}
        >
          <option value="day">By day</option>
          <option value="week">By week</option>
        </select>
      </div>

      <QueryState pending={oee.isPending} error={oee.error} retry={() => oee.refetch()}>
        {oee.data && plant && coverage && (
          <>
            {oee.data.workCenters.length === 0 ? (
              <Panel>
                <Empty
                  title="No work centers yet"
                  body="Create work centers and run routed operations to measure OEE."
                />
              </Panel>
            ) : (
              <>
                <motion.div className="stats-grid" variants={stagger} initial="hidden" animate="visible">
                  <Stat
                    label="Plant OEE"
                    value={pct(plant.oee)}
                    icon={Gauge}
                    note={
                      plant.oee == null
                        ? 'Not measured in this range'
                        : `${fmt(plant.measuredDays)} of ${fmt(plant.days)} work-center days measured`
                    }
                    tone={oeeTone(plant.oee)}
                  />
                  <Stat
                    label="Availability"
                    value={pct(plant.availability)}
                    icon={Timer}
                    note={`Run ${hours(plant.runMinutes)} of ${hours(plant.plannedMinutes)} planned`}
                  />
                  <Stat
                    label="Performance"
                    value={pct(plant.performance)}
                    icon={Activity}
                    note={`Ideal-time output in ${hours(plant.runMinutes)} of run time`}
                  />
                  <Stat
                    label="Quality"
                    value={pct(plant.quality)}
                    icon={ChartNoAxesCombined}
                    note={`${fmt(plant.scrapCount)} scrap of ${fmt(plant.totalCount)} reported`}
                  />
                </motion.div>
                <div className="summary-strip perf-coverage">
                  <div>
                    <span>Planned time measured</span>
                    <strong>{pct(coverage.timeCoverage, 0)}</strong>
                    <small>
                      {hours(coverage.measuredPlannedMinutes)} of {hours(coverage.plannedMinutes)}
                    </small>
                  </div>
                  <div>
                    <span>Output with time records</span>
                    <strong>{pct(coverage.outputCoverage, 0)}</strong>
                    <small>{fmt(coverage.untimedOutput)} units from orders without a routing</small>
                  </div>
                  <div>
                    <span>Utilization</span>
                    <strong>{pct(plant.utilization, 0)}</strong>
                    <small>{pct(plant.capacityUtilization, 0)} of 24 hours</small>
                  </div>
                  <div>
                    <span>Closed work-center days</span>
                    <strong>
                      {fmt(coverage.closedDays)}
                      <small>/ {fmt(coverage.workCenterDays)}</small>
                    </strong>
                    <small>{fmt(oee.data.warningTotal)} data warnings</small>
                  </div>
                </div>
                <p className="perf-method">
                  <Info size={14} />
                  OEE = availability × performance × quality, time-weighted across work centers and days
                  (never an average of percentages). Days without planned time, without records or with only
                  untimed output have no OEE and are shown as such.
                </p>

                <div className="perf-grid">
                  <Panel title="OEE trend" subtitle={`Plant, by ${bucket}`}>
                    <div className="perf-body">
                      <TrendChart series={oee.data.series} bucket={bucket} />
                    </div>
                  </Panel>
                  <Panel title="Loss waterfall" subtitle="From calendar time to fully productive time">
                    <div className="perf-body">
                      <QueryState
                        pending={losses.isPending}
                        error={losses.error}
                        retry={() => losses.refetch()}
                      >
                        {losses.data && <LossWaterfall steps={losses.data.waterfall} />}
                      </QueryState>
                    </div>
                  </Panel>
                </div>

                <Panel
                  className="perf-section"
                  title="OEE by work center"
                  subtitle={
                    bucket === 'day'
                      ? 'Select a day to see its losses, records and closing history'
                      : 'Weekly roll-up'
                  }
                >
                  <div className="perf-body">
                    <Heatmap data={oee.data} onOpen={openDay} />
                  </div>
                </Panel>

                {losses.data && (
                  <div className="perf-grid three">
                    <Panel title="Downtime by reason" subtitle="Recorded downtime in shift time">
                      <div className="perf-body">
                        <DowntimePareto rows={losses.data.pareto} />
                      </div>
                    </Panel>
                    <Panel title="Six big losses" subtitle="Measured where the records allow">
                      <div className="perf-body">
                        <SixLosses losses={losses.data.sixLosses} />
                      </div>
                    </Panel>
                    <Panel title="Quality losses" subtitle="In process and after production">
                      <div className="perf-body">
                        <QualityLosses quality={losses.data.quality} />
                      </div>
                    </Panel>
                  </div>
                )}

                <Panel
                  className="perf-section"
                  title="Utilization"
                  subtitle="Busy time (setup, changeover, run) against shift time and 24 hours"
                >
                  <UtilizationTable data={oee.data} />
                </Panel>

                <Panel
                  className="perf-section"
                  title="Data coverage and quality"
                  subtitle="Why some days are not measured, and what to fix"
                >
                  <div className="perf-body">
                    <Warnings data={oee.data} onOpen={openDay} />
                  </div>
                </Panel>
              </>
            )}
          </>
        )}
      </QueryState>
      <AnimatePresence>
        {open && (
          <DayDrawer
            key={`${open.machineId}-${open.day}`}
            machineId={open.machineId}
            day={open.day}
            onClose={() => setOpen(undefined)}
          />
        )}
      </AnimatePresence>
    </>
  );
}
