import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { AnimatePresence, motion, useIsPresent } from 'motion/react';
import { LoaderCircle, Plus, Route, Trash2 } from 'lucide-react';
import { useStateData } from '../../app/workspace';
import { Overlay } from '../../components';
import type { Row } from '../../lib/api';
import { text } from '../../lib/format';
import { easeOut, useMotionPreferences } from '../../motion/motion';
import { listOf, rateText, useRoutingRequest, useSkills } from './api';
import type { MachineRef, RoutingDetail } from './api';

type OperationLine = {
  key: string;
  seq: string;
  name: string;
  machineId: string;
  alternates: string[];
  setupMinutes: string;
  runMinutesPerUnit: string;
  crewSize: string;
  skillId: string;
  skillLevel: string;
  inspectionPoint: boolean;
  instructions: string;
};

const blank = (seq: number): OperationLine => ({
  key: crypto.randomUUID(),
  seq: String(seq),
  name: '',
  machineId: '',
  alternates: [],
  setupMinutes: '0',
  runMinutesPerUnit: '',
  crewSize: '1',
  skillId: '',
  skillLevel: '1',
  inspectionPoint: false,
  instructions: '',
});

/**
 * Routing revision editor (V10): creates a draft (`POST /api/routings`) or replaces a draft's revision, notes and
 * operations (`POST /api/routings/{id}`). Approved revisions are immutable; copy them into a new draft first.
 */
export function RoutingEditor({
  draft,
  onClose,
  onSaved,
}: {
  /** Edit this draft instead of creating a new routing. */
  draft?: RoutingDetail;
  onClose: () => void;
  onSaved: (id: string) => void;
}) {
  const data = useStateData();
  const skills = useSkills().data?.skills || [];
  const save = useRoutingRequest();
  const { reduced } = useMotionPreferences();
  const [lines, setLines] = useState<OperationLine[]>(() =>
    draft?.operations.length
      ? draft.operations.map((o) => ({
          key: crypto.randomUUID(),
          seq: text(o.seq),
          name: text(o.name),
          machineId: text(o.machine_id),
          alternates: listOf<MachineRef>(o, 'alternates').map((m) => m.id),
          setupMinutes: text(Number(o.setup_minutes)),
          runMinutesPerUnit: text(Number(o.run_minutes_per_unit)),
          crewSize: text(Number(o.crew_size)),
          skillId: text(o.skill_id),
          skillLevel: text(o.skill_level || 1),
          inspectionPoint: !!o.inspection_point,
          instructions: text(o.instructions),
        }))
      : [blank(10)],
  );
  const [productId, setProductId] = useState(text(draft?.routing.product_id));
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const key = useRef(crypto.randomUUID());
  const uom = text(data.items.find((i) => i.id === productId)?.uom) || 'pcs';
  const products = data.items.filter(
    (i) => ((i.type === 'FINISHED_GOOD' || i.type === 'SEMI_FINISHED') && i.active) || i.id === productId,
  );

  function update(index: number, patch: Partial<OperationLine>) {
    setLines((prev) => prev.map((l, i) => (i === index ? { ...l, ...patch } : l)));
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError('');
    setPending(true);
    const values = Object.fromEntries(new FormData(e.currentTarget)) as Record<string, string>;
    const operations = lines.map((l) => ({
      seq: Number(l.seq),
      name: l.name,
      machineId: l.machineId,
      alternateMachineIds: l.alternates.filter((a) => a !== l.machineId),
      setupMinutes: l.setupMinutes || '0',
      runMinutesPerUnit: l.runMinutesPerUnit || '0',
      crewSize: l.crewSize || '1',
      skillId: l.skillId || null,
      skillLevel: l.skillId ? Number(l.skillLevel || 1) : null,
      inspectionPoint: l.inspectionPoint,
      instructions: l.instructions,
    }));
    try {
      const result = draft
        ? await save<Row>(
            `/routings/${draft.routing.id}`,
            { revision: values.revision, notes: values.notes, operations },
            key.current,
          )
        : await save<Row>(
            '/routings',
            { productId, revision: values.revision, notes: values.notes, operations },
            key.current,
          );
      onSaved(text(result.id));
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }

  return (
    <Overlay
      title={draft ? `Edit draft ${text(draft.routing.revision)}` : 'New routing'}
      subtitle={
        draft
          ? `${text(draft.routing.product)} · replace the operations before approval.`
          : 'Describe how a product is made, operation by operation. Another planner approves it.'
      }
      onClose={() => {
        if (!pending) onClose();
      }}
      wide
    >
      <form onSubmit={submit}>
        <div className="form-fields">
          <label className="field">
            <span>Finished or semi-finished product</span>
            <select
              required
              value={productId}
              disabled={!!draft}
              onChange={(e) => setProductId(e.target.value)}
            >
              <option value="" disabled>
                Select a product
              </option>
              {products.map((i) => (
                <option key={i.id} value={i.id}>
                  {text(i.sku)} · {text(i.name)}
                  {i.type === 'SEMI_FINISHED' ? ' · semi-finished' : ''}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Revision reference</span>
            <input
              name="revision"
              required
              maxLength={30}
              placeholder="e.g. R01"
              defaultValue={draft ? text(draft.routing.revision) : 'R01'}
            />
          </label>
          <label className="field">
            <span>
              Notes<small>Optional</small>
            </span>
            <textarea name="notes" rows={2} maxLength={2000} defaultValue={text(draft?.routing.notes)} />
          </label>
        </div>
        <div className="routing-editor-ops">
          <h3>
            <Route size={15} />
            Operations
          </h3>
          <AnimatePresence initial={false}>
            {lines.map((line, index) => (
              <OperationEditor
                key={line.key}
                line={line}
                index={index}
                uom={uom}
                machines={data.machines}
                skills={skills}
                reduced={reduced}
                removable={lines.length > 1}
                onUpdate={(patch) => update(index, patch)}
                onRemove={() => setLines(lines.filter((_, i) => i !== index))}
              />
            ))}
          </AnimatePresence>
          <button
            className="button secondary small"
            type="button"
            onClick={() =>
              setLines([...lines, blank(Math.max(0, ...lines.map((l) => Number(l.seq) || 0)) + 10)])
            }
          >
            <Plus size={14} />
            Add operation
          </button>
          <p className="settings-note">
            Run time is minutes per {uom} (0.05 = 1,200 {uom} per hour); setup is once per order. Operations
            run in sequence order on the shop floor. New manufacturing orders snapshot the product&apos;s
            current approved routing; orders already created keep theirs.
          </p>
          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <button type="button" className="button secondary" disabled={pending} onClick={onClose}>
            Cancel
          </button>
          <button className="button primary" disabled={pending}>
            {pending && <LoaderCircle className="spin" size={15} />}
            {draft ? 'Save draft changes' : 'Save draft routing'}
          </button>
        </div>
      </form>
    </Overlay>
  );
}

function OperationEditor({
  line,
  index,
  uom,
  machines,
  skills,
  reduced,
  removable,
  onUpdate,
  onRemove,
}: {
  line: OperationLine;
  index: number;
  uom: string;
  machines: Row[];
  skills: Row[];
  reduced: boolean;
  removable: boolean;
  onUpdate: (patch: Partial<OperationLine>) => void;
  onRemove: () => void;
}) {
  const present = useIsPresent();
  const primary = machines.find((m) => m.id === line.machineId);
  const sameKind = machines.filter((m) => primary && m.kind === primary.kind && m.id !== primary.id);
  const n = index + 1;
  return (
    <motion.fieldset
      className="routing-op-editor"
      layout={reduced ? false : 'position'}
      inert={!present}
      disabled={!present}
      initial={{ opacity: 0, y: reduced ? 0 : 6 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, height: reduced ? 'auto' : 0, transition: { duration: reduced ? 0 : 0.16 } }}
      transition={{ duration: reduced ? 0 : 0.2, ease: easeOut }}
    >
      <legend>Operation {n}</legend>
      <div className="routing-op-grid">
        <label className="field seq">
          <span>Sequence</span>
          <input
            required
            type="number"
            min="1"
            max="9999"
            step="1"
            aria-label={`Operation ${n} sequence`}
            value={line.seq}
            onChange={(e) => onUpdate({ seq: e.target.value })}
          />
        </label>
        <label className="field wide">
          <span>Operation name</span>
          <input
            required
            maxLength={120}
            aria-label={`Operation ${n} name`}
            value={line.name}
            onChange={(e) => onUpdate({ name: e.target.value })}
          />
        </label>
        <label className="field">
          <span>Work center</span>
          <select
            required
            aria-label={`Operation ${n} work center`}
            value={line.machineId}
            onChange={(e) => onUpdate({ machineId: e.target.value, alternates: [] })}
          >
            <option value="" disabled>
              Select work center
            </option>
            {machines.map((m) => (
              <option key={m.id} value={m.id}>
                {text(m.code)} · {text(m.kind)}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>Setup (min)</span>
          <input
            required
            type="number"
            min="0"
            step="any"
            aria-label={`Operation ${n} setup minutes`}
            value={line.setupMinutes}
            onChange={(e) => onUpdate({ setupMinutes: e.target.value })}
          />
        </label>
        <label className="field">
          <span>Run (min / {uom})</span>
          <input
            required
            type="number"
            min="0"
            step="any"
            aria-label={`Operation ${n} run minutes per unit`}
            value={line.runMinutesPerUnit}
            onChange={(e) => onUpdate({ runMinutesPerUnit: e.target.value })}
          />
          <em>{rateText(line.runMinutesPerUnit, uom)}</em>
        </label>
        <label className="field">
          <span>Crew</span>
          <input
            required
            type="number"
            min="0.01"
            step="0.01"
            aria-label={`Operation ${n} crew size`}
            value={line.crewSize}
            onChange={(e) => onUpdate({ crewSize: e.target.value })}
          />
        </label>
        <label className="field">
          <span>
            Required skill<small>Optional</small>
          </span>
          <select
            aria-label={`Operation ${n} required skill`}
            value={line.skillId}
            onChange={(e) => onUpdate({ skillId: e.target.value })}
          >
            <option value="">No skill required</option>
            {skills
              .filter((s) => s.active || s.id === line.skillId)
              .map((s) => (
                <option key={s.id} value={s.id}>
                  {text(s.code)} · {text(s.name)}
                </option>
              ))}
          </select>
        </label>
        <label className="field">
          <span>Minimum level</span>
          <select
            aria-label={`Operation ${n} minimum skill level`}
            value={line.skillLevel}
            disabled={!line.skillId}
            onChange={(e) => onUpdate({ skillLevel: e.target.value })}
          >
            {[1, 2, 3, 4, 5].map((l) => (
              <option key={l} value={l}>
                Level {l}
              </option>
            ))}
          </select>
        </label>
        <label className="check-field">
          <input
            type="checkbox"
            checked={line.inspectionPoint}
            onChange={(e) => onUpdate({ inspectionPoint: e.target.checked })}
          />
          Inspection point
        </label>
        {sameKind.length > 0 && (
          <div className="field wide alternates">
            <span>Alternate work centers ({text(primary?.kind)})</span>
            <div className="check-row">
              {sameKind.map((m) => (
                <label key={m.id} className="check-field">
                  <input
                    type="checkbox"
                    checked={line.alternates.includes(m.id)}
                    onChange={(e) =>
                      onUpdate({
                        alternates: e.target.checked
                          ? [...line.alternates, m.id]
                          : line.alternates.filter((a) => a !== m.id),
                      })
                    }
                  />
                  {text(m.code)}
                  {m.status === 'MAINTENANCE' ? ' (maintenance)' : ''}
                </label>
              ))}
            </div>
          </div>
        )}
        <label className="field full">
          <span>
            Instructions<small>Optional</small>
          </span>
          <textarea
            rows={2}
            maxLength={4000}
            aria-label={`Operation ${n} instructions`}
            value={line.instructions}
            onChange={(e) => onUpdate({ instructions: e.target.value })}
          />
        </label>
      </div>
      <button
        type="button"
        className="icon-button routing-op-remove"
        aria-label={`Remove operation ${n}`}
        disabled={!removable}
        onClick={onRemove}
      >
        <Trash2 size={16} />
      </button>
    </motion.fieldset>
  );
}
