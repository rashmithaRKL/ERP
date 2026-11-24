import { useState } from 'react';
import { ArrowRight, LoaderCircle } from 'lucide-react';
import { Empty, Overlay } from '../../components';
import type { Row } from '../../lib/api';
import { fmt, money, num, text } from '../../lib/format';
import { useComparison } from './api';
import type { CompareLine } from './api';
import './boms.css';

const CHANGE_LABEL: Record<CompareLine['change'], string> = {
  ADDED: 'Added',
  REMOVED: 'Removed',
  CHANGED: 'Changed',
  UNCHANGED: 'Unchanged',
};
const CHANGE_TONE: Record<CompareLine['change'], string> = {
  ADDED: 'green',
  REMOVED: 'red',
  CHANGED: 'amber',
  UNCHANGED: 'gray',
};

function Delta({
  value,
  digits,
  format,
}: {
  value: unknown;
  digits?: number;
  format?: (x: number) => string;
}) {
  const n = num(value);
  const shown = format ? format(Math.abs(n)) : fmt(Math.abs(n), digits);
  return (
    <span className={`delta ${n > 0 ? 'up' : n < 0 ? 'down' : 'zero'}`}>
      {n > 0 ? '+' : n < 0 ? '−' : ''}
      {shown}
    </span>
  );
}

/**
 * Compare two revisions of one product (V7): added, removed and changed components with quantity and cost deltas.
 * Quantities are compared per unit of product (output and yield applied); costs use rolled-up standard costs.
 */
export function CompareDialog({
  revisions,
  initialFrom,
  initialTo,
  onClose,
}: {
  revisions: Row[];
  initialFrom: string;
  initialTo: string;
  onClose: () => void;
}) {
  const [from, setFrom] = useState(initialFrom);
  const [to, setTo] = useState(initialTo);
  const [showUnchanged, setShowUnchanged] = useState(false);
  const comparison = useComparison(from, to);
  const c = comparison.data;
  const product = revisions.find((r) => r.id === from) || revisions[0];
  const option = (r: Row) => `${text(r.revision)} · ${text(r.status).toLowerCase()}`;
  const lines = c ? c.lines.filter((l) => showUnchanged || l.change !== 'UNCHANGED') : [];
  return (
    <Overlay
      title={`Compare revisions · ${text(product?.product)}`}
      subtitle="Component changes per unit of product, with quantity and standard-cost deltas."
      onClose={onClose}
      wide
    >
      <div className="compare-pickers">
        <label className="field">
          <span>From revision</span>
          <select value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From revision">
            {revisions.map((r) => (
              <option key={r.id} value={r.id}>
                {option(r)}
              </option>
            ))}
          </select>
        </label>
        <ArrowRight size={18} />
        <label className="field">
          <span>To revision</span>
          <select value={to} onChange={(e) => setTo(e.target.value)} aria-label="To revision">
            {revisions.map((r) => (
              <option key={r.id} value={r.id}>
                {option(r)}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="compare-body">
        {from === to ? (
          <Empty title="Pick two different revisions" body="Choose another revision to see what changed." />
        ) : comparison.isPending ? (
          <div className="mrp-state">
            <LoaderCircle className="spin" />
          </div>
        ) : comparison.isError ? (
          <div className="form-error" role="alert">
            {(comparison.error as Error).message}
          </div>
        ) : (
          c && (
            <>
              <div className="bom-summary">
                <div>
                  <span>Components</span>
                  <strong>
                    {c.summary.ADDED + c.summary.REMOVED + c.summary.CHANGED}{' '}
                    {c.summary.ADDED + c.summary.REMOVED + c.summary.CHANGED === 1 ? 'change' : 'changes'}
                  </strong>
                  <small>
                    {c.summary.ADDED} added · {c.summary.REMOVED} removed · {c.summary.CHANGED} changed
                  </small>
                </div>
                <div>
                  <span>Rolled-up cost / unit</span>
                  <strong>{money(c.summary.toUnitCost)}</strong>
                  <small>
                    from {money(c.summary.fromUnitCost)} ·{' '}
                    <Delta value={c.summary.unitCostDelta} format={(x) => money(x)} />
                  </small>
                </div>
                <div>
                  <span>Output · yield</span>
                  <strong>
                    {fmt(c.to.outputQty, 6)} · {fmt(num(c.to.yieldFactor) * 100, 2)}%
                  </strong>
                  <small>
                    {c.summary.outputChanged || c.summary.yieldChanged
                      ? `was ${fmt(c.from.outputQty, 6)} · ${fmt(num(c.from.yieldFactor) * 100, 2)}%`
                      : 'unchanged'}
                  </small>
                </div>
              </div>
              <div className="bom-tree-controls">
                <span className="grow" />
                <label className="check-row">
                  <input
                    type="checkbox"
                    checked={showUnchanged}
                    onChange={(e) => setShowUnchanged(e.target.checked)}
                  />{' '}
                  Show unchanged ({c.summary.UNCHANGED})
                </label>
              </div>
              {lines.length === 0 ? (
                <Empty
                  title="No component changes"
                  body="Both revisions consume the same components in the same quantities per unit."
                />
              ) : (
                <div className="diff-wrap">
                  <table className="diff-table">
                    <thead>
                      <tr>
                        <th>Component</th>
                        <th>Change</th>
                        <th className="num">
                          {text(c.from.revision)} → {text(c.to.revision)}
                        </th>
                        <th className="num">Per unit Δ</th>
                        <th className="num">Cost / unit Δ</th>
                      </tr>
                    </thead>
                    <tbody>
                      {lines.map((l) => (
                        <tr key={l.itemId} className={l.change.toLowerCase()}>
                          <td>
                            <div className="cell-stack">
                              <strong>{l.name}</strong>
                              <span>
                                {l.sku} · {l.uom}
                              </span>
                            </div>
                          </td>
                          <td>
                            <span className={`badge change ${CHANGE_TONE[l.change]}`}>
                              <i />
                              {CHANGE_LABEL[l.change]}
                            </span>
                          </td>
                          <td className="num">
                            {l.fromQty == null ? '—' : fmt(l.fromQty, 6)} →{' '}
                            {l.toQty == null ? '—' : fmt(l.toQty, 6)}
                          </td>
                          <td className="num">
                            <Delta value={l.perUnitDelta} digits={6} />
                          </td>
                          <td className="num">
                            <Delta value={l.costDelta} format={(x) => money(x)} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )
        )}
      </div>
      <div className="dialog-footer">
        <button type="button" className="button secondary" onClick={onClose}>
          Close
        </button>
      </div>
    </Overlay>
  );
}
