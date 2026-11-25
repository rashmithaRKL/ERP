import { text } from '../../lib/format';
import { duration, useOrderProjection, when } from './api';
import type { Projection } from './api';

function line(p: Projection) {
  if (p.status === 'NO_ROUTING') return 'Not scheduled: no routing or duration estimate';
  if (p.status === 'BEYOND_HORIZON') return 'Not finished within the schedule horizon';
  if (p.status === 'NO_OPEN_WORK') return 'Every operation is done';
  return `Projected to finish ${when(p.projected_end)}`;
}

/**
 * Order drawer: projected completion from the published schedule run (and the newest draft for planners), with the
 * lateness against the due date. Hidden for orders no schedule run has considered.
 */
export function OrderProjection({ orderId }: { orderId: string }) {
  const q = useOrderProjection(orderId);
  const published = q.data?.published;
  const draft = q.data?.draft;
  if (!published && !draft) return null;
  const rows = [
    ...(published ? [{ label: 'Published schedule', p: published }] : []),
    ...(draft ? [{ label: 'Draft schedule', p: draft }] : []),
  ];
  return (
    <div className="routing-notes" aria-label="Schedule projection">
      {rows.map(({ label, p }) => (
        <p key={label}>
          <strong>{label}</strong> ({text(p.run_number)}): {line(p)}
          {p.late ? (
            <span className="late-flag">
              {' '}
              · late by {p.status === 'BEYOND_HORIZON' ? 'at least ' : ''}
              {duration(p.lateness_minutes)}
            </span>
          ) : p.projected_end ? (
            <span className="positive"> · on time</span>
          ) : null}
        </p>
      ))}
    </div>
  );
}
