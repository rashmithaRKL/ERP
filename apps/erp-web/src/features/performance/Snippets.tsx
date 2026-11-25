import { ArrowRight, Gauge } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { State } from '../../lib/api';
import { date } from '../../lib/format';
import { useOee } from './api';
import { businessDay, cellText, oeeBand, pct, statusText } from './format';
import './performance.css';

/** Overview line: yesterday's measured plant OEE with A/P/Q and the share of planned time it covers. */
export function OverviewOee({ performance }: { performance: State['performance'] }) {
  const navigate = useNavigate();
  return (
    <div className="perf-overview">
      <Gauge size={18} />
      {performance ? (
        <>
          <div>
            <span>Plant OEE · {date(performance.day)}</span>
            <strong>{pct(performance.oee)}</strong>
          </div>
          <div>
            <span>
              A {pct(performance.availability, 0)} · P {pct(performance.performance, 0)} · Q{' '}
              {pct(performance.quality, 0)}
            </span>
            <span>{pct(performance.timeCoverage, 0)} of planned time measured</span>
          </div>
        </>
      ) : (
        <div>
          <span>Plant OEE · yesterday</span>
          <span>Not measured: no work center logged run time on a scheduled day.</span>
        </div>
      )}
      <button className="text-button" onClick={() => navigate('/performance')}>
        Performance
        <ArrowRight size={13} />
      </button>
    </div>
  );
}

/** Machine drawer: the work center's OEE of the last seven days (today so far included). */
export function MachineOee({ machineId }: { machineId: string }) {
  const navigate = useNavigate();
  const oee = useOee({ from: businessDay(-6), to: businessDay(0), machine: machineId, bucket: 'day' });
  const wc = oee.data?.workCenters[0];
  if (oee.isError) return <p className="perf-note">OEE could not be loaded: {oee.error.message}</p>;
  if (!wc) return <p className="perf-note">Loading OEE…</p>;
  return (
    <>
      <div className="perf-spark" role="list" aria-label="OEE of the last seven days">
        {oee.data!.periods.map((p) => {
          const cell = wc.cells.find((c) => c.period === p.key);
          const band = cell?.oee == null && cell?.status === 'UNTIMED' ? 'untimed' : oeeBand(cell?.oee);
          return (
            <div key={p.key} role="listitem">
              <span
                className={`perf-cell perf-static ${band}${cell?.closed ? '' : ' open'}`}
                title={`${p.label}: ${cell?.oee == null ? statusText(cell?.status) : pct(cell.oee)}`}
              >
                <span aria-hidden="true">{cell ? cellText(cell) : '—'}</span>
                <span className="sr-only">
                  {p.label}: {cell?.oee == null ? statusText(cell?.status) : `OEE ${pct(cell.oee)}`}
                </span>
              </span>
              <small aria-hidden="true">{p.label}</small>
            </div>
          );
        })}
      </div>
      <p className="perf-note">
        7-day OEE {pct(wc.totals.oee)} · availability {pct(wc.totals.availability, 0)} · performance{' '}
        {pct(wc.totals.performance, 0)} · quality {pct(wc.totals.quality, 0)} · utilization{' '}
        {pct(wc.totals.utilization, 0)}.{' '}
        <button className="text-button" onClick={() => navigate(`/performance?machine=${machineId}`)}>
          Open in Performance
          <ArrowRight size={12} />
        </button>
      </p>
    </>
  );
}
