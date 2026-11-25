import { useNavigate } from 'react-router-dom';
import { ChevronRight, TriangleAlert } from 'lucide-react';
import { Empty, Overlay } from '../components';
import { fmt, num, text, today } from '../lib/format';
import { useWorkspace } from './workspace';

/** Bell-icon notifications: shortages, pending inspections, overdue orders, open breakdowns and overdue preventive work. */
export function AttentionCenter({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const { data, summary } = useWorkspace();
  const { active, shortages, pendingQc } = summary;
  const breakdowns = data?.maintenance?.breakdowns || [];
  const overduePlans = data?.maintenance?.overdue || [];
  return (
    <Overlay
      title="Attention center"
      subtitle="Exceptions from current manufacturing records"
      onClose={onClose}
    >
      <div className="notification-list">
        {[
          ...breakdowns.map((b) => ({
            title: `${text(b.machine)} is down (breakdown)`,
            body: b.work_order
              ? `${text(b.number)} · repair ${text(b.work_order)}`
              : `${text(b.number)} · no repair requested yet`,
            path: '/maintenance?tab=downtime',
          })),
          ...overduePlans.map((p) => ({
            title: `${text(p.title)} overdue on ${text(p.machine)}`,
            body: `Preventive plan ${text(p.number)} was due ${text(p.next_due_date).substring(0, 10)}`,
            path: '/maintenance?tab=plans',
          })),
          ...shortages.map((m) => ({
            title: `${m.name} needs replenishment`,
            body: `${fmt(m.shortage, 3)} ${m.uom} net shortage`,
            path: '/planning',
          })),
          // V12 quality attention counts (`qualityAlerts` state key).
          ...(num(data?.qualityAlerts?.open_critical_ncrs)
            ? [
                {
                  title: `${fmt(data?.qualityAlerts?.open_critical_ncrs)} critical nonconformance(s) open`,
                  body: 'Held stock is waiting for a disposition decision',
                  path: '/quality?tab=ncrs',
                },
              ]
            : []),
          ...(num(data?.qualityAlerts?.overdue_capa_actions)
            ? [
                {
                  title: `${fmt(data?.qualityAlerts?.overdue_capa_actions)} CAPA action(s) overdue`,
                  body: 'Corrective and preventive actions past their due date',
                  path: '/quality?tab=capa',
                },
              ]
            : []),
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
        {!shortages.length &&
          !pendingQc.length &&
          !num(data?.metrics.overdue) &&
          !breakdowns.length &&
          !overduePlans.length &&
          !num(data?.qualityAlerts?.open_critical_ncrs) &&
          !num(data?.qualityAlerts?.overdue_capa_actions) && <Empty title="You’re all caught up" />}
      </div>
    </Overlay>
  );
}
