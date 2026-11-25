import { CalendarClock, CircleAlert, Wrench } from 'lucide-react';
import { text } from '../../lib/format';
import { reasonLabel, statusText, statusTone } from './api';
import './maintenance.css';

/** Work-order, downtime, plan-due or machine status chip (shared `.badge` tones; the label is always text). */
export function StatusChip({ value }: { value: unknown }) {
  return (
    <span className={`badge ${statusTone(value)}`}>
      <i />
      {statusText(value)}
    </span>
  );
}

/** Downtime reason with a planned/unplanned marker. */
export function ReasonChip({ reason, planned }: { reason: unknown; planned?: unknown }) {
  return (
    <span className="mnt-reason">
      <span className={`mnt-reason-dot ${text(reason).toLowerCase()}`} aria-hidden="true" />
      {reasonLabel(reason)}
      {planned !== undefined && planned !== null && (
        <small className={planned ? 'planned' : 'unplanned'}>{planned ? 'planned' : 'unplanned'}</small>
      )}
    </span>
  );
}

/** Corrective / preventive work-order type. */
export function TypeChip({ type }: { type: unknown }) {
  const preventive = type === 'PREVENTIVE';
  return (
    <span className={`mnt-type ${preventive ? 'preventive' : 'corrective'}`}>
      {preventive ? <CalendarClock size={12} /> : <Wrench size={12} />}
      {preventive ? 'Preventive' : 'Corrective'}
    </span>
  );
}

/** HIGH / URGENT priorities are highlighted; NORMAL stays plain text. */
export function PriorityText({ value }: { value: unknown }) {
  const code = text(value);
  if (code === 'URGENT')
    return (
      <span className="mnt-priority urgent">
        <CircleAlert size={12} />
        Urgent
      </span>
    );
  if (code === 'HIGH') return <span className="mnt-priority high">High</span>;
  return <span className="mnt-priority">Normal</span>;
}
