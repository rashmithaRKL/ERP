import { useState } from 'react';
import { ArrowRight, Check, EyeOff, LoaderCircle, RotateCcw, Save, TriangleAlert } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import type { Row } from '../../lib/api';
import { fmt, money, num, text } from '../../lib/format';
import { OPERATORS, useDetail, useInventoryCommand, useSettings } from './api';
import type { CountDetail } from './api';
import {
  ActivityFeed,
  dateTime,
  Delta,
  Lifecycle,
  MoneyDelta,
  QueryState,
  SearchBox,
  StatusBadge,
  qty,
} from './shared';

/**
 * Cycle count sheet. While COUNTING it is a touch-friendly entry screen (system quantities hidden for blind
 * counts); in REVIEW it shows quantity and value variances against the live balance and warns about lots that
 * moved after the snapshot; posting turns the variances into a count-variance adjustment.
 */
export function CountSheet({
  id,
  onClose,
  onOpenAdjustment,
}: {
  id: string;
  onClose: () => void;
  onOpenAdjustment: (id: string) => void;
}) {
  const { can, notify } = useWorkspace();
  const detail = useDetail<CountDetail>('count', `/inventory/counts/${id}`);
  const settings = useSettings();
  const { run, busy } = useInventoryCommand();
  const [values, setValues] = useState<Record<string, string>>({});
  const [filter, setFilter] = useState('');
  const [error, setError] = useState('');
  const c = detail.data?.count;
  const status = text(c?.status);
  const lines = detail.data?.lines ?? [];
  const hidden = !!detail.data?.hidden;
  const editable = status === 'COUNTING' && can(...OPERATORS);
  const current = (l: Row) => values[l.id] ?? (l.counted_qty === null ? '' : String(l.counted_qty));
  const changed = lines.filter(
    (l) =>
      values[l.id] !== undefined &&
      values[l.id] !== '' &&
      (l.counted_qty === null || num(values[l.id]) !== num(l.counted_qty)),
  );
  const counted = lines.filter((l) => current(l) !== '').length;
  const visible = lines.filter((l) =>
    [l.lot, l.item, l.sku].some((x) => text(x).toLowerCase().includes(filter.toLowerCase())),
  );
  const summary = detail.data?.summary;
  const gross = num(summary?.value_increase) + num(summary?.value_decrease);
  const threshold = num(settings.data?.approvalThreshold ?? 50000);

  async function attempt(work: () => Promise<unknown>) {
    setError('');
    try {
      await work();
    } catch (e) {
      setError((e as Error).message);
    }
  }
  async function saveEntries(success?: string) {
    if (!changed.length) return;
    await run(
      `/inventory/counts/${id}/entries`,
      { entries: changed.map((l) => ({ lineId: l.id, countedQty: values[l.id] })) },
      undefined,
      success,
    );
    setValues({});
  }

  return (
    <Overlay
      title={text(c?.number) || 'Cycle count'}
      subtitle={
        c
          ? `${text(c.warehouse)} · started by ${text(c.created_by_name)} · ${dateTime(c.created_at)}`
          : undefined
      }
      onClose={onClose}
      drawer
      wide
    >
      <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
        {detail.data && c && (
          <>
            <div className="drawer-body">
              <div className="detail-product">
                <div>
                  <h2>
                    {c.item
                      ? text(c.item)
                      : c.item_type
                        ? `All ${text(c.item_type).toLowerCase().replaceAll('_', ' ')}`
                        : 'All items'}{' '}
                    in {text(c.warehouse)}
                  </h2>
                  <p>
                    {fmt(counted)} of {fmt(lines.length)} lots counted
                    {c.blind ? ' · blind count' : ''}
                  </p>
                </div>
                <StatusBadge value={c.status} />
              </div>
              {error && (
                <div className="form-error" role="alert">
                  {error}
                </div>
              )}
              {status !== 'CANCELLED' && (
                <Lifecycle
                  steps={['Counting', 'Review', 'Posted']}
                  index={status === 'POSTED' ? 2 : status === 'REVIEW' ? 1 : 0}
                />
              )}
              {status === 'COUNTING' ? (
                <>
                  {hidden && (
                    <p className="inv-small muted inv-blind">
                      <EyeOff size={14} /> Blind count: system quantities stay hidden until the count is
                      submitted for review.
                    </p>
                  )}
                  <div className="count-toolbar">
                    <SearchBox value={filter} onChange={setFilter} placeholder="Find lot or item…" />
                    <span className="muted inv-small">
                      {changed.length ? `${changed.length} unsaved` : 'All entries saved'}
                    </span>
                  </div>
                  <div className="count-grid">
                    {visible.map((l) => (
                      <div key={l.id} className={`count-card ${current(l) !== '' ? 'done' : ''}`}>
                        <div className="count-card-head">
                          <strong>{text(l.lot)}</strong>
                          <StatusBadge value={l.lot_status} />
                        </div>
                        <span>
                          {text(l.item)} · {text(l.sku)}
                        </span>
                        {!hidden && <span>System at snapshot: {qty(l.snapshot_qty, l.uom)}</span>}
                        <label className="count-input">
                          <span className="inv-sr-only">Counted quantity for lot {text(l.lot)}</span>
                          <input
                            type="number"
                            inputMode="decimal"
                            min="0"
                            step={l.uom === 'pcs' ? '1' : '0.000001'}
                            placeholder="Count"
                            disabled={!editable || busy}
                            value={current(l)}
                            onChange={(e) => setValues({ ...values, [l.id]: e.target.value })}
                          />
                          <span>{text(l.uom)}</span>
                        </label>
                        {l.counted_by && (
                          <small>
                            <Check size={12} /> {text(l.counted_by)} · {dateTime(l.counted_at)}
                          </small>
                        )}
                      </div>
                    ))}
                  </div>
                  {!visible.length && <p className="muted inv-small">No lots match “{filter}”.</p>}
                </>
              ) : (
                <>
                  {detail.data.moved.length > 0 && (
                    <div className="inv-warning" role="status">
                      <TriangleAlert size={16} />
                      <div>
                        <strong>Stock moved after the snapshot</strong>
                        <p>
                          {detail.data.moved.join(', ')}. Variances are calculated against the system quantity
                          at
                          {status === 'POSTED' ? ' posting time.' : ' posting time, not the snapshot.'}
                        </p>
                      </div>
                    </div>
                  )}
                  <div className="detail-stats">
                    <div>
                      <span>Variances</span>
                      <strong>
                        {fmt(summary?.variances)} <small>of {fmt(summary?.lines)}</small>
                      </strong>
                    </div>
                    <div>
                      <span>Gain</span>
                      <strong className="positive">{money(summary?.value_increase)}</strong>
                    </div>
                    <div>
                      <span>Loss</span>
                      <strong className="negative">{money(summary?.value_decrease)}</strong>
                    </div>
                    <div>
                      <span>Net value</span>
                      <strong>
                        <MoneyDelta value={summary?.net_value} />
                      </strong>
                    </div>
                  </div>
                  {status === 'REVIEW' && (
                    <p className={`inv-threshold ${gross > threshold ? 'over' : ''}`}>
                      {num(summary?.variances) === 0
                        ? 'No variances: posting closes the count without changing stock.'
                        : gross > threshold
                          ? `Variance value above the ${money(threshold)} threshold: posting creates a count-variance adjustment that a different authorized person must approve.`
                          : 'Posting creates and posts a count-variance adjustment at each lot’s current value.'}
                    </p>
                  )}
                  {status === 'POSTED' && c.adjustment_id && (
                    <p className="inv-small">
                      Variance adjustment{' '}
                      <button className="text-button" onClick={() => onOpenAdjustment(text(c.adjustment_id))}>
                        {text(c.adjustment_number)}
                      </button>{' '}
                      <StatusBadge value={c.adjustment_status} />
                    </p>
                  )}
                  <h3>Variance review</h3>
                  <Table
                    compact
                    pageSize={Math.max(lines.length, 1)}
                    rows={lines}
                    columns={[
                      {
                        label: 'Lot',
                        render: (r) => (
                          <div className="cell-stack">
                            <strong>
                              {text(r.lot)}{' '}
                              {r.moved === true && <TriangleAlert size={12} className="inv-moved" />}
                            </strong>
                            <span>{text(r.item)}</span>
                          </div>
                        ),
                      },
                      { label: 'Snapshot', render: (r) => qty(r.snapshot_qty, r.uom) },
                      {
                        label: status === 'POSTED' ? 'System at posting' : 'System now',
                        render: (r) => qty(r.system_qty, r.uom),
                      },
                      { label: 'Counted', render: (r) => qty(r.counted_qty, r.uom) },
                      { label: 'Variance', render: (r) => <Delta value={r.variance_qty} uom={r.uom} /> },
                      { label: 'Value', render: (r) => <MoneyDelta value={r.variance_value} /> },
                    ]}
                  />
                </>
              )}
              {text(c.notes) && <p className="inv-notes">{text(c.notes)}</p>}
              <h3>Document activity</h3>
              <ActivityFeed events={detail.data.events} />
            </div>
            {['COUNTING', 'REVIEW'].includes(status) && can(...OPERATORS) && (
              <div className="dialog-footer detail-footer">
                <button
                  className="button danger-ghost"
                  disabled={busy}
                  onClick={() =>
                    attempt(() =>
                      run(
                        `/inventory/counts/${id}/cancel`,
                        {},
                        undefined,
                        `${text(c.number)} cancelled successfully.`,
                      ),
                    )
                  }
                >
                  Cancel count
                </button>
                <span className="grow" />
                {status === 'COUNTING' ? (
                  <>
                    <button
                      className="button secondary"
                      disabled={busy || !changed.length}
                      onClick={() => attempt(() => saveEntries('Counts saved successfully.'))}
                    >
                      <Save size={15} />
                      Save counts
                    </button>
                    <button
                      className="button primary"
                      disabled={busy}
                      onClick={() =>
                        attempt(async () => {
                          await saveEntries();
                          await run(
                            `/inventory/counts/${id}/submit`,
                            {},
                            undefined,
                            'Count submitted for review successfully.',
                          );
                        })
                      }
                    >
                      {busy && <LoaderCircle size={15} className="spin" />}
                      Submit for review
                      <ArrowRight size={15} />
                    </button>
                  </>
                ) : (
                  <>
                    <button
                      className="button secondary"
                      disabled={busy}
                      onClick={() =>
                        attempt(() =>
                          run(
                            `/inventory/counts/${id}/reopen`,
                            {},
                            undefined,
                            'Count reopened successfully.',
                          ),
                        )
                      }
                    >
                      <RotateCcw size={15} />
                      Reopen for recount
                    </button>
                    <button
                      className="button primary"
                      disabled={busy}
                      onClick={() =>
                        attempt(async () => {
                          const result = await run<{ adjustment: Row | null; moved: string[] }>(
                            `/inventory/counts/${id}/post`,
                          );
                          const adjustment = result.adjustment;
                          notify(
                            !adjustment
                              ? `${text(c.number)} posted successfully with no variances.`
                              : adjustment.status === 'APPROVAL_REQUIRED'
                                ? `${text(c.number)} posted successfully; variance adjustment ${text(adjustment.number)} needs a different person’s approval.`
                                : `${text(c.number)} posted successfully; variances posted as ${text(adjustment.number)}.`,
                          );
                        })
                      }
                    >
                      {busy && <LoaderCircle size={15} className="spin" />}
                      Post variances
                      <ArrowRight size={15} />
                    </button>
                  </>
                )}
              </div>
            )}
          </>
        )}
      </QueryState>
    </Overlay>
  );
}
