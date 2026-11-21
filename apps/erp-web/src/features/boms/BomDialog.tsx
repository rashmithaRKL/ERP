import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { AnimatePresence, motion, useIsPresent } from 'motion/react';
import { easeOut, useMotionPreferences } from '../../motion/motion';
import { Plus, Trash2, LoaderCircle, GitBranch } from 'lucide-react';
import { Overlay } from '../../components';
import { request } from '../../lib/api';
import type { State } from '../../lib/api';
import { text } from '../../lib/format';
export default function BomDialog({
  data,
  onClose,
  onSuccess,
}: {
  data: State;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const { reduced } = useMotionPreferences();
  const [lines, setLines] = useState([{ key: crypto.randomUUID(), itemId: '', quantity: '' }]);
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
    try {
      await request(
        '/boms',
        { ...values, lines: lines.map(({ itemId, quantity }) => ({ itemId, quantity })) },
        key.current,
      );
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
      title="Create a BOM revision"
      subtitle="Build a material formula, then approve it before use in production."
      onClose={() => {
        if (!pending) onClose();
      }}
      wide
    >
      <form onSubmit={submit}>
        <div className="form-fields">
          <label className="field">
            <span>Finished product</span>
            <select required name="productId" defaultValue="">
              <option value="" disabled>
                Select a product
              </option>
              {data.items
                .filter((i) => i.type === 'FINISHED_GOOD')
                .map((i) => (
                  <option key={i.id} value={i.id}>
                    {text(i.name)}
                  </option>
                ))}
            </select>
          </label>
          <label className="field">
            <span>Revision reference</span>
            <input name="revision" placeholder="e.g. R04" maxLength={30} required />
          </label>
          <label className="field">
            <span>Base output (pieces)</span>
            <input name="outputQty" type="number" min="1" step="1" defaultValue="1000" required />
          </label>
          <label className="field">
            <span>Expected yield factor</span>
            <input
              name="yieldFactor"
              type="number"
              min="0.000001"
              max="1"
              step="0.000001"
              defaultValue="1"
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
                materials={data.items.filter((i) => i.type !== 'FINISHED_GOOD')}
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
            This release supports single-level material formulas. Pieces are rounded upward when an order is
            scaled. Approved revisions are immutable.
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
            {pending && <LoaderCircle className="spin" size={15} />}Save draft revision
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
            Select raw material / packaging
          </option>
          {materials.map((item) => (
            <option key={item.id} value={item.id}>
              {text(item.name)} ({text(item.uom)})
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
