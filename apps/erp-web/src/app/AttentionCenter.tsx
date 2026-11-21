import { useNavigate } from 'react-router-dom';
import { ChevronRight, TriangleAlert } from 'lucide-react';
import { Empty, Overlay } from '../components';
import { fmt, num, text, today } from '../lib/format';
import { useWorkspace } from './workspace';

/** Bell-icon notifications: material shortages, pending inspections and overdue orders. */
export function AttentionCenter({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const { data, summary } = useWorkspace();
  const { active, shortages, pendingQc } = summary;
  return (
    <Overlay
      title="Attention center"
      subtitle="Exceptions from current manufacturing records"
      onClose={onClose}
    >
      <div className="notification-list">
        {[
          ...shortages.map((m) => ({
            title: `${m.name} needs replenishment`,
            body: `${fmt(m.shortage, 3)} ${m.uom} net shortage`,
            path: '/planning',
          })),
          ...pendingQc.map((q) => ({
            title: `${q.item} awaiting inspection`,
            body: `Lot ${q.lot}`,
            path: '/quality',
          })),
          ...active
            .filter((o) => text(o.due_date) < today())
            .map((o) => ({
              title: `${o.number} is overdue`,
              body: text(o.product),
              path: '/manufacturing',
            })),
        ].map((n, i) => (
          <button
            key={i}
            onClick={() => {
              navigate(n.path);
              onClose();
            }}
          >
            <span className="attention-symbol amber">
              <TriangleAlert size={17} />
            </span>
            <span>
              <strong>{n.title}</strong>
              <small>{n.body}</small>
            </span>
            <ChevronRight size={15} />
          </button>
        ))}
        {!shortages.length && !pendingQc.length && !num(data?.metrics.overdue) && (
          <Empty title="You’re all caught up" />
        )}
      </div>
    </Overlay>
  );
}
