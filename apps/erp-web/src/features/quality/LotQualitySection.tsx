import { useNavigate } from 'react-router-dom';
import { fmt, labelState, text } from '../../lib/format';
import { useQualityQuery } from './api';
import type { LotQuality } from './api';
import { QualityBadge } from './shared';

/**
 * Quality side of a lot for the inventory lot drawer (`GET /quality/lots/{id}`, V12): its nonconformances, the rework
 * order that produced it and the rework orders that consumed it. Renders nothing when there is none.
 */
export function LotQualitySection({ lotId, onOpenLot }: { lotId: string; onOpenLot: (id: string) => void }) {
  const navigate = useNavigate();
  const query = useQualityQuery<LotQuality>('lot', `/quality/lots/${lotId}`);
  const q = query.data;
  if (!q || (!q.ncrs.length && !q.reworkOrigin && !q.reworkedInto.length)) return null;
  const origin = q.reworkOrigin;
  return (
    <>
      <h3>Quality</h3>
      {origin && (
        <p className="inv-small">
          Reworked by {text(origin.number)} ({text(origin.ncr)}) from lot{' '}
          <button className="text-button" onClick={() => onOpenLot(text(origin.input_lot_id))}>
            {text(origin.input_lot)}
          </button>
          {origin.output_quantity !== null ? ` · ${fmt(origin.output_quantity, 3)} reworked` : ''}
        </p>
      )}
      {q.reworkedInto.map((r) => (
        <p className="inv-small" key={`${text(r.id)}-${text(r.output_lot_id)}`}>
          {labelState(r.role) === 'material' ? 'Material for' : 'Reworked in'} {text(r.number)} ·{' '}
          {fmt(r.quantity, 3)}
          {r.output_lot_id ? (
            <>
              {' '}
              → lot{' '}
              <button className="text-button" onClick={() => onOpenLot(text(r.output_lot_id))}>
                {text(r.output_lot)}
              </button>
            </>
          ) : (
            ' · rework in progress'
          )}
        </p>
      ))}
      {q.ncrs.map((n) => (
        <p className="inv-small" key={n.id}>
          <button className="text-button" onClick={() => navigate(`/quality?tab=ncrs&ncr=${n.id}`)}>
            {text(n.number)}
          </button>{' '}
          <QualityBadge value={n.status} /> {labelState(n.severity)} · {text(n.title)}
        </p>
      ))}
    </>
  );
}
