import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { AnimatePresence, motion, useIsPresent } from 'motion/react';
import { easeOut, useMotionPreferences } from '../../motion/motion';
import { Plus, Trash2, LoaderCircle, GitBranch } from 'lucide-react';
import { Overlay } from '../../components';
import { request } from '../../lib/api';
import type { Row, State } from '../../lib/api';
import { text } from '../../lib/format';

/**
 * BOM revision editor: creates a new draft revision, or (with `draft`) replaces a draft's output, yield and
 * components through `POST /api/boms/{id}` (V7). Approved revisions are immutable; copy them into a draft first.
 */
export default function BomDialog({
  data,
  draft,
  onClose,
  onSuccess,
}: {
  data: State;
  /** Edit this draft revision instead of creating a new one. */
  draft?: Row;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const { reduced } = useMotionPreferences();
  const [lines, setLines] = useState(() => {
    const existing = draft ? data.bomLines.filter((l) => l.bom_id === draft.id) : [];
    return existing.length
      ? existing.map((l) => ({
          key: crypto.randomUUID(),
          itemId: text(l.item_id),
          quantity: text(l.quantity),
        }))
      : [{ key: crypto.randomUUID(), itemId: '', quantity: '' }];
  });
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const key = useRef(crypto.randomUUID());
  function update(index: number, field: 'itemId' | 'quantity', value: string) {
    setLines((prev) => prev.map((l, i) => (i === index ? { ...l, [field]: value } : l)));
  }
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError('');
    setPending(true);
    const values = Object.fromEntries(new FormData(e.currentTarget));
    const components = lines.map(({ itemId, quantity }) => ({ itemId, quantity }));
    try {
      if (draft)
        await request(
          `/boms/${draft.id}`,
          { outputQty: values.outputQty, yieldFactor: values.yieldFactor, lines: components },
          key.current,
        );
      else await request('/boms', { ...values, lines: components }, key.current);
      onSuccess();
      onClose();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(false);
    }
  }
  return (
    <Overlay
      title={draft ? `Edit draft revision ${text(draft.revision)}` : 'Create a BOM revision'}
      subtitle={
        draft
          ? `${text(draft.product)} · replace the base output, yield and components before approval.`
          : 'Build a material formula, then approve it before use in production.'
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
              name="productId"
              defaultValue={draft ? text(draft.product_id) : ''}
              disabled={!!draft}
            >
              <option value="" disabled>
                Select a product
              </option>
              {data.items
                .filter(
                  (i) =>
                    ((i.type === 'FINISHED_GOOD' || i.type === 'SEMI_FINISHED') && i.active) ||
                    i.id === draft?.product_id,
                )
                .map((i) => (
                  <option key={i.id} value={i.id}>
                    {text(i.name)}
                    {i.type === 'SEMI_FINISHED' ? ' · semi-finished' : ''}
                  </option>
                ))}
            </select>
          </label>
          <label className="field">
            <span>Revision reference</span>
            <input
              name="revision"
              placeholder="e.g. R04"
              maxLength={30}
              required
              defaultValue={draft ? text(draft.revision) : undefined}
              disabled={!!draft}
            />
          </label>
          <label className="field">
            <span>Base output (pieces)</span>
            <input
              name="outputQty"
              type="number"
              min="1"
              step="1"
              defaultValue={draft ? text(Number(draft.output_qty)) : '1000'}
              required
            />
          </label>
          <label className="field">
            <span>Expected yield factor</span>
            <input
              name="yieldFactor"
              type="number"
              min="0.000001"
              max="1"
              step="0.000001"
              defaultValue={draft ? text(Number(draft.yield_factor)) : '1'}
              required
            />
            <em>Use 0.98 for a 98% expected yield.</em>
          </label>
        </div>
        <div className="bom-editor-lines">
          <h3>
            <GitBranch size={15} />
            Material components
          </h3>
          <AnimatePresence initial={false}>
            {lines.map((line, index) => (
              <MaterialRow
                key={line.key}
                line={line}
                index={index}
                materials={data.items.filter(
                  // Spare parts (V11) are maintenance stock, never BOM components.
                  (i) =>
                    i.type !== 'FINISHED_GOOD' &&
                    i.type !== 'SPARE_PART' &&
                    i.active &&
                    i.id !== draft?.product_id,
                )}
                reduced={reduced}
                removable={lines.length > 1}
                onUpdate={(field, value) => update(index, field, value)}
                onRemove={() => setLines(lines.filter((_, i) => i !== index))}
              />
            ))}
          </AnimatePresence>
          <button
            className="button secondary small"
            type="button"
            onClick={() => setLines([...lines, { key: crypto.randomUUID(), itemId: '', quantity: '' }])}
          >
            <Plus size={14} />
            Add material
          </button>
          <p className="settings-note">
            A semi-finished component expands through its own approved BOM (multi-level), so approve
            sub-assembly revisions first. Pieces are rounded upward at every level when an order is scaled.
            Approved revisions are immutable: copy one into a new draft to change it.
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
            {draft ? 'Save draft changes' : 'Save draft revision'}
          </button>
        </div>
      </form>
    </Overlay>
  );
}

type MaterialLine = { key: string; itemId: string; quantity: string };
function MaterialRow({
  line,
  index,
  materials,
  reduced,
  removable,
  onUpdate,
  onRemove,
}: {
  line: MaterialLine;
  index: number;
  materials: State['items'];
  reduced: boolean;
  removable: boolean;
  onUpdate: (field: 'itemId' | 'quantity', value: string) => void;
  onRemove: () => void;
}) {
  const present = useIsPresent();
  return (
    <motion.div
      className="bom-editor-line"
      layout={reduced ? false : 'position'}
      inert={!present}
      style={{ overflow: 'hidden' }}
      initial={{ opacity: 0, height: reduced ? 'auto' : 0, marginBottom: 0 }}
      animate={{ opacity: 1, height: 'auto', marginBottom: 17 }}
      exit={{ opacity: 0, height: reduced ? 'auto' : 0, marginBottom: 0 }}
      transition={{ duration: reduced ? 0 : 0.2, ease: easeOut }}
    >
      <label className="field">
        <span>Material {index + 1}</span>
        <select
          required
          disabled={!present}
          aria-label={`Material ${index + 1}`}
          value={line.itemId}
          onChange={(e) => onUpdate('itemId', e.target.value)}
        >
          <option value="" disabled>
            Select raw material / packaging / semi-finished
          </option>
          {materials.map((item) => (
            <option key={item.id} value={item.id}>
              {text(item.name)} ({text(item.uom)}){item.type === 'SEMI_FINISHED' ? ' · semi-finished' : ''}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Quantity in base unit</span>
        <input
          disabled={!present}
          aria-label={`Component quantity ${index + 1}`}
          required
          type="number"
          min="0.000001"
          step="0.000001"
          value={line.quantity}
          onChange={(e) => onUpdate('quantity', e.target.value)}
        />
      </label>
      <button
        type="button"
        className="icon-button"
        aria-label={`Remove material ${index + 1}`}
        disabled={!present || !removable}
        onClick={onRemove}
      >
        <Trash2 size={16} />
      </button>
    </motion.div>
  );
}
