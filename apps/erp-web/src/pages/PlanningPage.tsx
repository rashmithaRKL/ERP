import { ArrowUpRight, Layers } from 'lucide-react';
import { useStateData, useWorkspace } from '../app/workspace';
import { Panel, Table } from '../components';
import { createPurchase } from '../features/purchasing/forms';
import { fmt, num, text } from '../lib/format';

/** Route `/planning`: material requirements planning (MRP). */
export default function PlanningPage() {
  const workspace = useWorkspace();
  const { can, summary } = workspace;
  const { shortages } = summary;
  const data = useStateData();
  return (
    <>
      <div className="info-banner">
        <Layers size={20} />
        <div>
          <strong>Material requirements planning</strong>
          <p>
            Unreserved order demand + safety stock − released, uncommitted inventory − approved incoming
            supply. This is basic infinite-capacity planning.
          </p>
        </div>
      </div>
      <Panel
        title="Material readiness"
        subtitle={`${shortages.length} materials need replenishment. Recommendations require purchasing approval.`}
      >
        <Table
          rows={data.mrp}
          columns={[
            {
              label: 'Material',
              key: 'name',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.name)}</strong>
                  <span>
                    {text(r.sku)} · {text(r.uom)}
                  </span>
                </div>
              ),
            },
            { label: 'Gross demand', render: (r) => fmt(r.gross, 3) },
            { label: 'Usable stock', render: (r) => fmt(r.available, 3) },
            { label: 'Incoming', render: (r) => fmt(r.incoming, 3) },
            { label: 'Safety stock', render: (r) => fmt(r.reorder_level, 3) },
            {
              label: 'Net shortage',
              render: (r) => (
                <span className={num(r.shortage) > 0 ? 'negative weight-semibold' : 'positive'}>
                  {num(r.shortage) > 0 ? `${fmt(r.shortage, 3)} ${r.uom}` : 'Covered'}
                </span>
              ),
            },
            {
              label: 'Action',
              render: (r) =>
                num(r.shortage) > 0 && can('PLANNER', 'WAREHOUSE') ? (
                  <button className="button secondary small" onClick={() => createPurchase(workspace, r)}>
                    Create purchase
                    <ArrowUpRight size={13} />
                  </button>
                ) : (
                  '—'
                ),
            },
          ]}
        />
      </Panel>
    </>
  );
}
