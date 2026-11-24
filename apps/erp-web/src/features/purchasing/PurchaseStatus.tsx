import { statusLabel } from './api';

const TONES: Record<string, string> = {
  DRAFT: 'gray',
  SUBMITTED: 'amber',
  APPROVED: 'blue',
  PARTIAL: 'amber',
  RECEIVED: 'green',
  CLOSED: 'green',
  CANCELLED: 'red',
};

/** Purchase-order status in the shared badge style ("awaiting approval", "partially received", "closed short"…). */
export function PurchaseStatus({ status }: { status: unknown }) {
  const code = String(status ?? '');
  return (
    <span className={`badge ${TONES[code] || 'gray'}`}>
      <i />
      {code === 'CLOSED' ? 'closed short' : statusLabel(code)}
    </span>
  );
}
