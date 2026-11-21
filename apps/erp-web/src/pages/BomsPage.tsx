import { Check, ShieldCheck } from 'lucide-react';
import { useStateData, useWorkspace } from '../app/workspace';
import { Badge, Panel } from '../components';
import { ProductMark } from '../features/products/ProductMark';
import { fmt, money, num, text } from '../lib/format';

/** Route `/boms`: BOM revisions. "New BOM revision" opens the shared BOM editor (features/boms). */
export default function BomsPage() {
  const { busy, can, mutate } = useWorkspace();
  const data = useStateData();
  return (
    <div className="bom-grid">
      {data.boms.map((b) => (
        <Panel key={b.id} className="bom-card">
          <div className="bom-card-head">
            <ProductMark kind={b.product} />
            <Badge value={b.status} />
          </div>
          <h2>{text(b.product)}</h2>
          <p className="muted">
            {text(b.sku)} · Revision {text(b.revision)}
          </p>
          <div className="bom-meta">
            <span>
              Base output<strong>{fmt(b.output_qty)} pcs</strong>
            </span>
            <span>
              Expected yield<strong>{fmt(num(b.yield_factor) * 100, 1)}%</strong>
            </span>
            <span>
              Material cost / unit<strong>{money(b.unit_cost)}</strong>
            </span>
          </div>
          <div className="bom-lines">
            {data.bomLines
              .filter((l) => l.bom_id === b.id)
              .map((l) => (
                <div key={l.id}>
                  <span>
                    <span className="bom-line-dot" />
                    {text(l.name).split(' · ')[0]}
                  </span>
                  <strong>
                    {fmt(l.quantity, 3)} {text(l.uom)}
                  </strong>
                </div>
              ))}
          </div>
          <div className="bom-actions">
            {b.status === 'DRAFT' && can('PLANNER') && (
              <button
                className="button secondary small"
                disabled={busy}
                onClick={() => mutate(`/boms/${b.id}/approve`)}
              >
                <Check size={14} />
                Approve revision
              </button>
            )}
          </div>
          <div className="bom-note">
            <ShieldCheck size={14} />
            Released orders retain this exact material snapshot.
          </div>
        </Panel>
      ))}
    </div>
  );
}
