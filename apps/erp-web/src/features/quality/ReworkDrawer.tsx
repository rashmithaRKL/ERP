import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { AnimatePresence } from 'motion/react';
import { LoaderCircle, Plus, Trash2 } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import type { Row } from '../../lib/api';
import { codeLabel, fmt, labelState, money, num, text } from '../../lib/format';
import { LotPicker } from '../inventory/LotPicker';
import { ActivityFeed, dateTime, Lifecycle, QueryState } from '../inventory/shared';
import { useQualityCommand, useQualityQuery } from './api';
import type { ReworkDetail } from './api';
import { ActionError, Facts, QualityBadge } from './shared';

/** One rework order: input lot, costs, extra material, the output lot (genealogy links) and its inspection. */
export function ReworkDrawer({
  id,
  onClose,
  onOpenNcr,
}: {
  id: string;
  onClose: () => void;
  onOpenNcr: (id: string) => void;
}) {
  const { can } = useWorkspace();
  const detail = useQualityQuery<ReworkDetail>('rework', `/quality/rework/${id}`);
  const [completing, setCompleting] = useState(false);
  const r = detail.data?.rework;
  const completed = r?.status === 'COMPLETED';
  return (
    <Overlay
      title={text(r?.number) || 'Rework order'}
      subtitle="Rework of nonconforming stock: costs, material and the reworked lot"
      onClose={onClose}
      drawer
      wide
    >
      <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
        {detail.data && r && (
          <>
            <div className="drawer-body">
              <div className="detail-product">
                <div>
                  <h2>{text(r.item)}</h2>
                  <p>
                    {text(r.sku)} · opened by {text(r.created_by_name)} · {dateTime(r.created_at)}
                  </p>
                </div>
                <QualityBadge value={r.status} />
              </div>
              <Lifecycle
                steps={['Input taken', 'Reworked', 'Re-inspected']}
                index={!completed ? 0 : r.inspection_status === 'PENDING' ? 1 : 2}
              />
              <div className="detail-stats">
                <div>
                  <span>Input</span>
                  <strong>
                    {fmt(r.input_quantity, 3)} <small>{text(r.uom)}</small>
                  </strong>
                </div>
                <div>
                  <span>Input value</span>
                  <strong>{money(r.input_value)}</strong>
                </div>
                <div>
                  <span>Rework cost</span>
                  <strong>{completed ? money(num(r.labour_cost) + num(r.material_cost)) : '—'}</strong>
                </div>
                <div>
                  <span>Output value</span>
                  <strong>{completed ? money(r.output_value) : '—'}</strong>
                </div>
              </div>
              <Facts
                items={[
                  [
                    'Nonconformance',
                    <button key="ncr" className="text-button" onClick={() => onOpenNcr(text(r.ncr_id))}>
                      {text(r.ncr)} · {text(r.ncr_title)}
                    </button>,
                  ],
                  ['Input lot', text(r.input_lot)],
                  [
                    'Output lot',
                    r.output_lot ? (
                      <span className="qa-inline-badge">
                        {text(r.output_lot)} <QualityBadge value={r.output_lot_status} />
                      </span>
                    ) : (
                      ''
                    ),
                  ],
                  ['Output', completed ? `${fmt(r.output_quantity, 3)} ${text(r.uom)}` : ''],
                  ['Labour', completed ? money(r.labour_cost) : ''],
                  ['Material', completed ? money(r.material_cost) : ''],
                  ['Re-inspection', r.inspection_status ? labelState(r.inspection_status) : ''],
                  [
                    'Completed',
                    completed ? `${dateTime(r.completed_at)} by ${text(r.completed_by_name)}` : '',
                  ],
                ]}
              />
              {text(r.instructions) && <p className="inv-notes">{text(r.instructions)}</p>}
              {text(r.completion_note) && (
                <p className="inv-small">
                  <strong>Completion note:</strong> {text(r.completion_note)}
                </p>
              )}
              {detail.data.links.length > 0 && (
                <>
                  <h3>Lot genealogy</h3>
                  <Table
                    compact
                    pageSize={detail.data.links.length}
                    rows={detail.data.links}
                    label="Rework lot links"
                    columns={[
                      { label: 'Input lot', key: 'input_lot' },
                      { label: 'Role', render: (l) => codeLabel(l.role) },
                      { label: 'Quantity', render: (l) => fmt(l.quantity, 3) },
                      { label: 'Value', render: (l) => money(l.value_amount) },
                      { label: 'Output lot', key: 'output_lot' },
                    ]}
                  />
                </>
              )}
              {detail.data.materials.length > 0 && (
                <>
                  <h3>Extra material</h3>
                  <Table
                    compact
                    pageSize={detail.data.materials.length}
                    rows={detail.data.materials}
                    label="Rework material"
                    columns={[
                      { label: 'Lot', key: 'lot' },
                      { label: 'Item', render: (m) => `${text(m.sku)} · ${text(m.item)}` },
                      { label: 'Quantity', render: (m) => `${fmt(m.quantity, 3)} ${text(m.uom)}` },
                      { label: 'Value', render: (m) => money(m.value_amount) },
                    ]}
                  />
                </>
              )}
              <h3>Activity</h3>
              <ActivityFeed events={detail.data.activity} />
            </div>
            {!completed && can('QUALITY', 'OPERATOR', 'PLANNER') && (
              <div className="dialog-footer detail-footer">
                <span className="muted inv-small">
                  The output enters a new quarantined lot and waits for its re-inspection.
                </span>
                <span className="grow" />
                <button className="button primary" onClick={() => setCompleting(true)}>
                  Complete rework
                </button>
              </div>
            )}
          </>
        )}
      </QueryState>
      <AnimatePresence>
        {completing && r && <CompleteDialog key="complete" rework={r} onClose={() => setCompleting(false)} />}
      </AnimatePresence>
    </Overlay>
  );
}

type Material = { key: string; lot: Row | null; quantity: string };

/** `POST /quality/rework/{id}/complete`: output quantity, labour cost and extra material from released lots. */
function CompleteDialog({ rework, onClose }: { rework: Row; onClose: () => void }) {
  const { run, busy } = useQualityCommand();
  const key = useRef(crypto.randomUUID());
  const [materials, setMaterials] = useState<Material[]>([]);
  const [error, setError] = useState('');
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const v = Object.fromEntries(new FormData(e.currentTarget).entries()) as Record<string, string>;
    const lines = materials.filter((m) => m.lot);
    if (lines.some((m) => !m.quantity)) {
      setError('Enter a quantity for every material lot.');
      return;
    }
    setError('');
    try {
      await run(
        `/quality/rework/${rework.id}/complete`,
        {
          outputQuantity: v.outputQuantity,
          labourCost: v.labourCost || '0',
          materials: lines.map((m) => ({ lotId: m.lot!.id, quantity: m.quantity })),
          note: v.note || null,
        },
        key.current,
        `${text(rework.number)} completed successfully; the reworked lot awaits re-inspection.`,
      );
      onClose();
    } catch (err) {
      setError((err as Error).message);
    }
  }
  return (
    <Overlay
      title={`Complete ${text(rework.number)}`}
      subtitle={`Output value = input ${money(rework.input_value)} + labour + material. The new lot is quarantined until its re-inspection passes.`}
      onClose={() => !busy && onClose()}
      wide
    >
      <form onSubmit={submit}>
        <div className="form-fields qa-form-grid">
          <label className="field">
            <span>Good output ({text(rework.uom)})</span>
            <input
              name="outputQuantity"
              type="number"
              required
              min="0.000001"
              max={String(num(rework.input_quantity))}
              step={rework.uom === 'pcs' ? '1' : '0.000001'}
              defaultValue={String(num(rework.input_quantity))}
            />
            <em>At most the {fmt(rework.input_quantity, 3)} taken into rework.</em>
          </label>
          <label className="field">
            <span>Labour cost (LKR)</span>
            <input name="labourCost" type="number" min="0" step="0.01" defaultValue="0" />
          </label>
          <div className="qa-span qa-materials">
            <div className="qa-materials-head">
              <strong>Extra material</strong>
              <button
                type="button"
                className="button small secondary"
                onClick={() =>
                  setMaterials([...materials, { key: crypto.randomUUID(), lot: null, quantity: '' }])
                }
              >
                <Plus size={13} /> Add material
              </button>
            </div>
            {materials.length === 0 && (
              <p className="inv-small muted">
                Optional: labels, packaging or components issued from released lots.
              </p>
            )}
            {materials.map((m, i) => (
              <div className="qa-material" key={m.key}>
                <LotPicker
                  label={`Material lot ${i + 1}`}
                  value={m.lot}
                  movable
                  exclude={[
                    text(rework.input_lot_id),
                    ...materials.filter((x) => x.lot).map((x) => x.lot!.id),
                  ]}
                  onChange={(lot) =>
                    setMaterials(materials.map((x) => (x.key === m.key ? { ...x, lot } : x)))
                  }
                />
                <label className="field">
                  <span>Quantity{m.lot ? ` (${text(m.lot.uom)})` : ''}</span>
                  <input
                    type="number"
                    min="0.000001"
                    step={m.lot?.uom === 'pcs' ? '1' : '0.000001'}
                    value={m.quantity}
                    onChange={(e) =>
                      setMaterials(
                        materials.map((x) => (x.key === m.key ? { ...x, quantity: e.target.value } : x)),
                      )
                    }
                  />
                </label>
                <button
                  type="button"
                  className="icon-button"
                  aria-label={`Remove material lot ${i + 1}`}
                  onClick={() => setMaterials(materials.filter((x) => x.key !== m.key))}
                >
                  <Trash2 size={15} />
                </button>
              </div>
            ))}
          </div>
          <label className="field qa-span">
            <span>
              Completion note <small>Optional</small>
            </span>
            <textarea name="note" rows={2} maxLength={2000} />
          </label>
          <div className="qa-span">
            <ActionError message={error} />
          </div>
        </div>
        <div className="dialog-footer">
          <button type="button" className="button secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button className="button primary" disabled={busy}>
            {busy && <LoaderCircle className="spin" size={16} />}
            Complete rework
          </button>
        </div>
      </form>
    </Overlay>
  );
}
