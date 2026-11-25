import type { Figures, OeeResponse } from './api';
import { cellText, oeeBand, pct, statusReason, statusText } from './format';
import './performance.css';

/** Accessible description of one heatmap cell. */
function describe(code: string, label: string, cell: Figures) {
  const parts = [`${code}, ${label}`];
  if (cell.oee != null)
    parts.push(
      `OEE ${pct(cell.oee)}, availability ${pct(cell.availability)}, performance ${pct(cell.performance)}, quality ${pct(cell.quality)}`,
    );
  else parts.push(`${statusText(cell.status)}: ${statusReason(cell.status)}`);
  if (cell.closed)
    parts.push(cell.version && cell.version > 1 ? `closed, version ${cell.version}` : 'closed');
  else if (cell.days === undefined) parts.push(cell.partial ? 'today so far, open' : 'open (not closed)');
  const warnings = cell.warnings?.filter((w) => w.severity === 'warning').length || cell.warningCount || 0;
  if (warnings) parts.push(`${warnings} data ${warnings === 1 ? 'warning' : 'warnings'}`);
  return parts.join('; ');
}

/**
 * OEE heatmap: one row per work center, one cell per day or week, the range total at the end. Cells without an OEE say
 * why (not scheduled, not measured, untimed output) instead of showing 0 %; open (not closed) days have a dashed
 * outline, cells with data warnings an amber dot. Day cells open the work-center day.
 */
export function Heatmap({
  data,
  onOpen,
}: {
  data: OeeResponse;
  onOpen?: (machineId: string, day: string) => void;
}) {
  const daily = data.bucket === 'day';
  return (
    <>
      <div
        className="perf-heatmap-wrap"
        role="region"
        aria-label="OEE by work center and period"
        tabIndex={0}
      >
        <table className="perf-heatmap">
          <thead>
            <tr>
              <th scope="col">Work center</th>
              {data.periods.map((p) => (
                <th key={p.key} scope="col" className={p.partial ? 'partial' : undefined}>
                  {p.label}
                  {p.partial && daily ? ' · today' : ''}
                </th>
              ))}
              <th scope="col">Range</th>
            </tr>
          </thead>
          <tbody>
            {data.workCenters.map((wc) => (
              <tr key={wc.id}>
                <th scope="row">
                  <strong>{wc.code}</strong>
                  <span>{wc.name}</span>
                </th>
                {data.periods.map((p) => {
                  const cell = wc.cells.find((c) => c.period === p.key);
                  if (!cell) return <td key={p.key} />;
                  const band = cell.oee == null && cell.status === 'UNTIMED' ? 'untimed' : oeeBand(cell.oee);
                  const open = daily ? !cell.closed : (cell.closedDays || 0) < (cell.days || 0);
                  const flagged = (cell.warnings || []).some((w) => w.severity === 'warning');
                  const label = describe(wc.code, p.label, cell);
                  const className = `perf-cell ${band}${open ? ' open' : ''}`;
                  return (
                    <td key={p.key}>
                      {daily && onOpen ? (
                        <button
                          className={className}
                          aria-label={label}
                          title={label}
                          onClick={() => onOpen(wc.id, p.from)}
                        >
                          {cellText(cell)}
                          {flagged && <span className="perf-flag" aria-hidden="true" />}
                        </button>
                      ) : (
                        <span className={`${className} perf-static`} title={label}>
                          <span aria-hidden="true">{cellText(cell)}</span>
                          <span className="sr-only">{label}</span>
                        </span>
                      )}
                    </td>
                  );
                })}
                <td className="total" title={describe(wc.code, 'range', wc.totals)}>
                  {cellText(wc.totals)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="perf-key" aria-hidden="true">
        <span>
          <i className="excellent" />≥ 85 %
        </span>
        <span>
          <i className="good" />
          65–85 %
        </span>
        <span>
          <i className="fair" />
          45–65 %
        </span>
        <span>
          <i className="poor" />
          &lt; 45 %
        </span>
        <span>
          <i className="untimed" />
          Untimed output
        </span>
        <span>
          <i className="none" />
          n/m not measured · · not scheduled
        </span>
        <span>
          <i className="open" />
          Open (not closed)
        </span>
      </div>
    </>
  );
}
