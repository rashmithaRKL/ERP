import type { Workspace } from '../../app/workspace';
import { selectField } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { num, text } from '../../lib/format';
import { scheduleChanged } from './api';

const yesNo = [
  { value: 'true', label: 'Yes' },
  { value: 'false', label: 'No' },
];

/**
 * "Run scheduler": schedules every open operation from now into a new draft run (horizon, alternates, how long an open
 * breakdown without a maintenance window is assumed to last, draft orders). The page selects the new draft.
 */
export function runScheduler(workspace: Workspace, previous?: Row | null) {
  workspace.openForm({
    title: 'Run the scheduler',
    description:
      'Schedules every open operation from now on finite capacity: shift calendars, holidays and overtime, maintenance windows and open downtime, one operation at a time per work center. The result is a draft you can compare and publish.',
    submit: 'Run scheduler',
    fields: [
      {
        name: 'horizonDays',
        label: 'Horizon (days)',
        type: 'number',
        min: '1',
        step: '1',
        defaultValue: text(previous?.horizon_days || 28),
        hint: '1 to 90 days from now.',
      },
      selectField(
        'useAlternates',
        'Use alternate work centers',
        yesNo,
        previous ? String(previous.use_alternates !== false) : 'true',
      ),
      {
        name: 'breakdownHours',
        label: 'Open breakdown lasts (hours)',
        type: 'number',
        min: '1',
        step: '1',
        defaultValue: text(previous?.breakdown_hours || 8),
        hint: 'Used when an open downtime has no maintenance window ending later.',
      },
      selectField(
        'includeDrafts',
        'Include draft orders',
        yesNo,
        previous?.include_drafts ? 'true' : 'false',
      ),
      { name: 'notes', label: 'Notes', type: 'textarea', optional: true },
    ],
    run: async (v, key) => {
      const run = await request<Row>(
        '/schedule/runs',
        {
          horizonDays: num(v.horizonDays),
          useAlternates: v.useAlternates === 'true',
          breakdownHours: num(v.breakdownHours),
          includeDrafts: v.includeDrafts === 'true',
          notes: v.notes || null,
        },
        key,
      );
      scheduleChanged(run.id);
      return run;
    },
  });
}

/** Remaining-duration estimate (working minutes) of an order without a routing, so the scheduler can place it. */
export function estimateDuration(workspace: Workspace, order: Row) {
  workspace.openForm({
    title: `Duration estimate · ${text(order.number)}`,
    description:
      'This order has no routing, so the scheduler has no rates for it. Enter the remaining working time on its work center; the next run schedules it as one operation.',
    submit: 'Save estimate',
    fields: [
      {
        name: 'minutes',
        label: 'Remaining work (minutes)',
        type: 'number',
        min: '1',
        step: '1',
        defaultValue: order.current_estimate ? text(Math.round(num(order.current_estimate))) : '',
      },
      { name: 'notes', label: 'Basis of the estimate', type: 'textarea', optional: true },
    ],
    run: async (v, key) => {
      const out = await request<Row>(
        '/schedule/estimates',
        { orderId: order.order_id || order.id, minutes: num(v.minutes), notes: v.notes || null },
        key,
      );
      scheduleChanged();
      return out;
    },
  });
}
