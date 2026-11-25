import { Overlay, Table } from '../../components';
import type { Row } from '../../lib/api';
import { date, fmt, num, text } from '../../lib/format';
import { CHANGE_LABELS, duration, runLabel, useCompare, when } from './api';

const TONE: Record<string, string> = {
  NEWLY_LATE: 'red',
  NO_LONGER_LATE: 'green',
  LATER: 'amber',
  EARLIER: 'green',
  NEW: 'blue',
  DROPPED: 'gray',
  MOVED: 'violet',
  SAME: 'gray',
};

function Change({ value }: { value: unknown }) {
  const code = text(value);
  return (
    <span className={`badge ${TONE[code] || 'gray'}`}>
      <i />
      {CHANGE_LABELS[code] || code}
    </span>
  );
}

const delta = (minutes: unknown) =>
  minutes === null || minutes === undefined || num(minutes) === 0
    ? '—'
    : `${num(minutes) > 0 ? '+' : '−'}${duration(Math.abs(num(minutes)))}`;

/** A run against the published run (or the run that was published when it was created): order and operation moves. */
export function CompareDialog({ run, onClose }: { run: Row; onClose: () => void }) {
  const diff = useCompare(text(run.id), true);
  const d = diff.data;
  const changedOrders = (d?.orders || []).filter((o) => o.change !== 'SAME');
  return (
    <Overlay
      title={`Compare ${text(run.number)}`}
      subtitle={
        d?.base
          ? `Against ${runLabel(d.base)} (published ${when(d.base.published_at)})`
          : 'Changes against the published schedule'
      }
      onClose={onClose}
      wide
    >
      <div className="drawer-body">
        {diff.isPending ? (
          <div className="server-loading">
            <div className="skeleton" />
          </div>
        ) : diff.isError ? (
          <div className="form-error" role="alert">
            {(diff.error as Error).message}
          </div>
        ) : !d?.base ? (
          <p className="muted">
            Nothing to compare with yet: no schedule has been published before this run.
          </p>
        ) : (
          <>
            <div className="sched-compare-strip">
              {[
                ['Newly late', d.summary.newly_late, 'red'],
                ['No longer late', d.summary.no_longer_late, 'green'],
                ['Finish later', d.summary.later, 'amber'],
                ['Finish earlier', d.summary.earlier, 'green'],
                ['Operations moved', d.summary.moved, 'violet'],
                ['New operations', d.summary.new, 'blue'],
                ['Dropped', d.summary.dropped, 'gray'],
              ].map(([label, value, tone]) => (
                <div key={text(label)}>
                  <span>{label}</span>
                  <strong className={num(value) ? `tone-${tone}` : undefined}>{fmt(value)}</strong>
                </div>
              ))}
            </div>
            <h3>Orders</h3>
            <Table
              label="Order changes"
              rows={changedOrders.map((o) => ({ ...o, id: text(o.order_id) }))}
              compact
              pageSize={8}
              columns={[
                { label: 'Order', key: 'number' },
                { label: 'Product', key: 'product' },
                { label: 'Due', render: (o) => date(o.due_date) },
                { label: 'Was', render: (o) => when(o.base_projected_end) },
                { label: 'Now', render: (o) => when(o.projected_end) },
                { label: 'Shift', render: (o) => delta(o.delta_minutes) },
                { label: 'Change', render: (o) => <Change value={o.change} /> },
              ]}
            />
            {!changedOrders.length && <p className="muted">Every order finishes at the same time.</p>}
            <h3>Operations</h3>
            <Table
              label="Operation changes"
              rows={d.operations.map((o, i) => ({
                ...o,
                id: `${text(o.order_id)}-${text(o.operation_id)}-${i}`,
              }))}
              compact
              pageSize={8}
              columns={[
                {
                  label: 'Operation',
                  render: (o) => `${text(o.order_number)} · ${text(o.seq)} ${text(o.name)}`,
                },
                {
                  label: 'Was',
                  render: (o) =>
                    o.base_machine_code ? `${text(o.base_machine_code)} · ${when(o.base_start_at)}` : '—',
                },
                {
                  label: 'Now',
                  render: (o) => (o.machine_code ? `${text(o.machine_code)} · ${when(o.start_at)}` : '—'),
                },
                { label: 'Shift', render: (o) => delta(o.delta_minutes) },
                { label: 'Change', render: (o) => <Change value={o.change} /> },
              ]}
            />
            {!d.operations.length && <p className="muted">No operation moved.</p>}
          </>
        )}
      </div>
    </Overlay>
  );
}
