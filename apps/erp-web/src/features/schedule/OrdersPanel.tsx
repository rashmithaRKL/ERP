import { useState } from 'react';
import { Timer } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Badge, Empty, Table } from '../../components';
import type { Row } from '../../lib/api';
import { date, fmt, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { ORDER_STATUS, PLANNERS, duration, when } from './api';
import { estimateDuration } from './forms';

function OrderStatus({ value }: { value: unknown }) {
  const code = text(value);
  const tone = code === 'SCHEDULED' ? 'green' : code === 'NO_OPEN_WORK' ? 'gray' : 'amber';
  return (
    <span className={`badge ${tone}`}>
      <i />
      {ORDER_STATUS[code] || code}
    </span>
  );
}

/**
 * The run's orders: late orders with projected completion against the due date, orders the scheduler could not place
 * (no routing, beyond the horizon; planners can enter a duration estimate for orders without a routing) and every
 * order in scheduling sequence.
 */
export function OrdersPanel({ orders, editable }: { orders: Row[]; editable: boolean }) {
  const workspace = useWorkspace();
  const { openOrder, can } = workspace;
  const late = orders.filter((o) => o.late);
  const unscheduled = orders.filter((o) => o.status === 'NO_ROUTING' || o.status === 'BEYOND_HORIZON');
  const [tab, setTab] = useState(late.length ? 'late' : unscheduled.length ? 'unscheduled' : 'all');
  const rows = (
    tab === 'late'
      ? [...late].sort((a, b) => Number(b.lateness_minutes) - Number(a.lateness_minutes))
      : tab === 'unscheduled'
        ? unscheduled
        : orders
  ).map((o) => ({
    ...o,
    id: text(o.order_id),
  }));
  return (
    <>
      <Tabs
        label="Orders in this run"
        compact
        items={[
          { value: 'late', label: 'Late', count: late.length },
          { value: 'unscheduled', label: 'Unscheduled', count: unscheduled.length },
          { value: 'all', label: 'All orders', count: orders.length },
        ]}
        value={tab}
        onChange={setTab}
      />
      {!rows.length ? (
        <Empty
          title={
            tab === 'late'
              ? 'No late orders'
              : tab === 'unscheduled'
                ? 'Everything is scheduled'
                : 'No open orders'
          }
          body={
            tab === 'late'
              ? 'Every scheduled order is projected to finish by its due date.'
              : tab === 'unscheduled'
                ? 'Every open order has a routing or a duration estimate and fits in the horizon.'
                : 'Approved and released manufacturing orders appear here when the scheduler runs.'
          }
        />
      ) : (
        <Table
          label="Scheduled orders"
          rows={rows}
          compact
          pageSize={8}
          onRow={(r) => openOrder(text(r.order_id))}
          columns={[
            {
              label: 'Order',
              key: 'number',
              render: (o) => (
                <span className="cell-stack">
                  <strong>{text(o.number)}</strong>
                  <small>{text(o.product)}</small>
                </span>
              ),
            },
            { label: 'Priority', render: (o) => <Badge value={o.priority} /> },
            { label: 'Due', render: (o) => date(o.due_date) },
            {
              label: 'Projected finish',
              render: (o) => (o.projected_end ? when(o.projected_end) : <OrderStatus value={o.status} />),
            },
            {
              label: 'Late by',
              render: (o) =>
                o.late ? (
                  <span className="late-flag">
                    <Timer size={12} /> {o.status === 'BEYOND_HORIZON' ? 'at least ' : ''}
                    {duration(o.lateness_minutes)}
                  </span>
                ) : (
                  '—'
                ),
            },
            {
              label: 'Work',
              render: (o) =>
                o.status === 'NO_ROUTING'
                  ? 'No routing'
                  : `${duration(o.remaining_minutes)} · ${fmt(o.operations)} op${Number(o.operations) === 1 ? '' : 's'}`,
            },
            {
              label: '',
              render: (o) =>
                editable && can(...PLANNERS) && !o.routed && o.status !== 'NO_OPEN_WORK' ? (
                  <button
                    className="button secondary small"
                    onClick={(e) => {
                      e.stopPropagation();
                      estimateDuration(workspace, o);
                    }}
                  >
                    {o.current_estimate ? 'Edit estimate' : 'Estimate duration'}
                  </button>
                ) : o.warnings ? (
                  <span className="muted sched-warning" title={text(o.warnings)}>
                    {text(o.warnings).split('\n')[0]}
                  </span>
                ) : null,
            },
          ]}
        />
      )}
    </>
  );
}
