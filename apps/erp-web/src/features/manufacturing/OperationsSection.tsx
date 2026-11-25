import { LoaderCircle } from 'lucide-react';
import { Table } from '../../components';
import type { Row } from '../../lib/api';
import { fmt, num, text } from '../../lib/format';
import { minutesText, useOrderOperations } from '../routings/api';
import { StatusChip, useElapsedMinutes } from '../routings/shared';

/**
 * "Operations" section of the manufacturing order drawer (V10): the routing revision the order snapshotted and each
 * operation's work center, planned vs actual minutes, status and good/scrap. Nothing is shown for orders without a
 * routing (single implicit operation).
 */
export function OperationsSection({ order }: { order: Row }) {
  const operations = useOrderOperations(order.routing_id ? order.id : '');
  if (!order.routing_id) return null;
  const d = operations.data;
  return (
    <>
      <h3>
        Operations{' '}
        <small className="muted">
          routing {text(order.routing_revision)}
          {d?.routing ? ` · ${text(d.routing.number)}` : ''}
        </small>
      </h3>
      {operations.isPending ? (
        <LoaderCircle className="spin" />
      ) : operations.isError || !d ? (
        <div className="form-error" role="alert">
          {(operations.error as Error)?.message || 'The operations could not be loaded.'}
        </div>
      ) : (
        <Table
          label="Manufacturing order operations"
          compact
          rows={d.operations}
          columns={[
            { label: 'Seq', key: 'seq' },
            {
              label: 'Operation',
              render: (o) => (
                <span className="cell-stack">
                  <strong>{text(o.name)}</strong>
                  <small>
                    {text(o.machine_code)}
                    {o.machine_id !== o.planned_machine_id
                      ? ` (planned ${text(o.planned_machine_code)})`
                      : ''}
                    {o.skill_code ? ` · ${text(o.skill_code)}` : ''}
                    {o.inspection_point ? ' · inspection point' : ''}
                  </small>
                </span>
              ),
            },
            { label: 'Planned', render: (o) => minutesText(o.planned_minutes) },
            { label: 'Actual', render: (o) => <ActualMinutes op={o} /> },
            {
              label: 'Status',
              render: (o) => (
                <span className="cell-stack">
                  <StatusChip value={o.status} />
                  {o.operator_name && <small>{text(o.operator_name)}</small>}
                </span>
              ),
            },
            {
              label: 'Good / scrap',
              render: (o) => `${fmt(o.good_qty)} / ${fmt(o.scrap_qty)}`,
            },
          ]}
        />
      )}
    </>
  );
}

function ActualMinutes({ op }: { op: Row }) {
  const elapsed = useElapsedMinutes(op.open_since);
  const actual = num(op.actual_setup_minutes) + num(op.actual_run_minutes) + (elapsed || 0);
  if (!actual) return <span className="muted">—</span>;
  return (
    <span className="cell-stack">
      <strong className={actual > num(op.planned_minutes) ? 'negative' : ''}>{minutesText(actual)}</strong>
      <small>
        setup {minutesText(op.actual_setup_minutes)}
        {elapsed !== null ? ' · running' : ''}
      </small>
    </span>
  );
}
