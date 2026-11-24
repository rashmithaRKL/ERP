import { useRef } from 'react';
import type { KeyboardEvent } from 'react';
import { AnimatePresence, motion, useIsPresent } from 'motion/react';
import { Plus, Trash2 } from 'lucide-react';
import { fmt } from '../lib/format';
import { easeOut, useMotionPreferences } from '../motion/motion';
import { lineGross, lineNet, newLine } from './lineItems';
import type { LineItem, LineItemOption, UnitChoice } from './lineItems';
import './LineItemsEditor.css';

export type { LineItem, LineItemOption, UnitChoice } from './lineItems';

type FieldName = 'itemId' | 'quantity' | 'unitPrice' | 'discountPct';

/**
 * Generic multi-line document editor (sales orders, purchase orders…): item picker, quantity, unit price, optional
 * discount, computed line total, add/remove rows and running totals. Controlled: pass `lines` and `onChange`.
 *
 * Keyboard: Enter moves to the same field on the next line and adds a line after the last one; Ctrl+Delete removes
 * the current line. Each control has an accessible per-line label.
 */
export function LineItemsEditor({
  lines,
  onChange,
  options,
  itemLabel = 'Item',
  quantityLabel = 'Quantity',
  priceLabel = 'Unit price',
  discount = true,
  currency = 'Rs.',
  disabled = false,
  maxLines = 100,
  addLabel = 'Add line',
  emptyOption = 'Select an item',
  units,
}: {
  lines: LineItem[];
  onChange: (lines: LineItem[]) => void;
  options: LineItemOption[];
  itemLabel?: string;
  quantityLabel?: string;
  priceLabel?: string;
  /** Show the discount % column. */
  discount?: boolean;
  currency?: string;
  disabled?: boolean;
  maxLines?: number;
  addLabel?: string;
  emptyOption?: string;
  /**
   * Optional unit choices per line (e.g. purchase units of the line's item). When it returns choices, the quantity
   * cell shows a unit picker bound to `line.unit` and the quantity step follows the chosen unit's decimals.
   */
  units?: (line: LineItem) => UnitChoice[] | undefined;
}) {
  const { reduced } = useMotionPreferences();
  const root = useRef<HTMLDivElement>(null);
  const byValue = new Map(options.map((o) => [o.value, o]));

  function focus(index: number, field: FieldName) {
    // After React commits the new row.
    requestAnimationFrame(() =>
      root.current
        ?.querySelector<HTMLElement>(`[data-line="${index}"][data-field="${field}"]`)
        ?.focus({ preventScroll: false }),
    );
  }
  function update(index: number, patch: Partial<LineItem>) {
    onChange(lines.map((l, i) => (i === index ? { ...l, ...patch } : l)));
  }
  function add(after = lines.length - 1) {
    if (lines.length >= maxLines) return;
    const next = [...lines.slice(0, after + 1), newLine(), ...lines.slice(after + 1)];
    onChange(next);
    focus(after + 1, 'itemId');
  }
  function remove(index: number) {
    onChange(lines.filter((_, i) => i !== index));
    if (lines.length > 1) focus(Math.max(0, index - 1), 'itemId');
  }
  function onKey(e: KeyboardEvent<HTMLElement>, index: number, field: FieldName) {
    if (e.key === 'Enter' && !e.shiftKey && !e.altKey) {
      e.preventDefault();
      if (index === lines.length - 1) add(index);
      else focus(index + 1, field);
    } else if (e.key === 'Delete' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      remove(index);
    }
  }

  const gross = lines.reduce((sum, l) => sum + lineGross(l), 0);
  const net = lines.reduce((sum, l) => sum + lineNet(l), 0);
  return (
    <div className={`line-editor ${discount ? '' : 'no-discount'} ${units ? 'with-units' : ''}`} ref={root}>
      <div className="line-editor-head" aria-hidden="true">
        <span>#</span>
        <span>{itemLabel}</span>
        <span>{quantityLabel}</span>
        <span>{priceLabel}</span>
        {discount && <span>Discount %</span>}
        <span className="line-editor-amount">Line total</span>
        <span />
      </div>
      <AnimatePresence initial={false}>
        {lines.map((line, index) => (
          <Row
            key={line.key}
            line={line}
            index={index}
            option={byValue.get(line.itemId)}
            options={options}
            unitChoices={units?.(line)}
            resetUnit={!!units}
            discount={discount}
            disabled={disabled}
            reduced={reduced}
            currency={currency}
            itemLabel={itemLabel}
            quantityLabel={quantityLabel}
            priceLabel={priceLabel}
            emptyOption={emptyOption}
            onUpdate={(patch) => update(index, patch)}
            onRemove={() => remove(index)}
            onKey={(e, field) => onKey(e, index, field)}
          />
        ))}
      </AnimatePresence>
      {!lines.length && (
        <p className="line-editor-empty">No lines yet. Add the first line to build the document.</p>
      )}
      <div className="line-editor-foot">
        <div className="line-editor-actions">
          <button
            type="button"
            className="button secondary small"
            disabled={disabled || lines.length >= maxLines}
            onClick={() => add()}
          >
            <Plus size={14} />
            {addLabel}
          </button>
          <span className="line-editor-hint">
            <kbd>Enter</kbd> next line · <kbd>Ctrl</kbd>+<kbd>Del</kbd> remove line
          </span>
        </div>
        <dl className="line-editor-totals">
          <dt>Subtotal</dt>
          <dd>
            {currency} {fmt(gross, 2)}
          </dd>
          {discount && (
            <>
              <dt>Discount</dt>
              <dd>
                − {currency} {fmt(gross - net, 2)}
              </dd>
            </>
          )}
          <dt className="grand">Total</dt>
          <dd className="grand">
            {currency} {fmt(net, 2)}
          </dd>
        </dl>
      </div>
    </div>
  );
}

function Row({
  line,
  index,
  option,
  options,
  discount,
  disabled,
  reduced,
  currency,
  itemLabel,
  quantityLabel,
  priceLabel,
  emptyOption,
  onUpdate,
  onRemove,
  onKey,
  unitChoices,
  resetUnit,
}: {
  unitChoices?: UnitChoice[];
  resetUnit: boolean;
  line: LineItem;
  index: number;
  option?: LineItemOption;
  options: LineItemOption[];
  discount: boolean;
  disabled: boolean;
  reduced: boolean;
  currency: string;
  itemLabel: string;
  quantityLabel: string;
  priceLabel: string;
  emptyOption: string;
  onUpdate: (patch: Partial<LineItem>) => void;
  onRemove: () => void;
  onKey: (e: KeyboardEvent<HTMLElement>, field: FieldName) => void;
}) {
  const present = useIsPresent();
  const off = disabled || !present;
  const unit = unitChoices?.length ? line.unit || unitChoices[0].code : undefined;
  const decimals = unitChoices?.find((u) => u.code === unit)?.decimals;
  const whole = decimals !== undefined ? decimals <= 0 : option?.unit === 'pcs';
  const step = whole ? '1' : decimals !== undefined ? `0.${'0'.repeat(decimals - 1)}1` : '0.000001';
  const n = index + 1;
  return (
    <motion.div
      className="line-editor-row"
      role="group"
      aria-label={`Line ${n}`}
      layout={reduced ? false : 'position'}
      inert={!present}
      style={{ overflow: 'hidden' }}
      initial={{ opacity: 0, height: reduced ? 'auto' : 0 }}
      animate={{ opacity: 1, height: 'auto' }}
      exit={{ opacity: 0, height: reduced ? 'auto' : 0 }}
      transition={{ duration: reduced ? 0 : 0.18, ease: easeOut }}
    >
      <span className="line-editor-no">{n}</span>
      <label className="line-editor-cell item">
        <span className="line-editor-label">{itemLabel}</span>
        <select
          required
          disabled={off}
          aria-label={`${itemLabel} on line ${n}`}
          data-line={index}
          data-field="itemId"
          value={line.itemId}
          onKeyDown={(e) => onKey(e, 'itemId')}
          onChange={(e) => {
            const picked = options.find((o) => o.value === e.target.value);
            onUpdate({
              itemId: e.target.value,
              unitPrice: line.unitPrice || picked?.price || '',
              ...(resetUnit && e.target.value !== line.itemId ? { unit: '' } : {}),
            });
          }}
        >
          <option value="" disabled>
            {emptyOption}
          </option>
          {options.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
          {line.itemId && !option && <option value={line.itemId}>Unavailable item</option>}
        </select>
      </label>
      <label className="line-editor-cell qty">
        <span className="line-editor-label">{quantityLabel}</span>
        <span className="line-editor-input-unit">
          <input
            required
            disabled={off}
            type="number"
            inputMode="decimal"
            min={step}
            step={step}
            aria-label={`${quantityLabel} on line ${n}`}
            data-line={index}
            data-field="quantity"
            value={line.quantity}
            onKeyDown={(e) => onKey(e, 'quantity')}
            onChange={(e) => onUpdate({ quantity: e.target.value })}
          />
          {unitChoices?.length ? (
            <select
              disabled={off}
              aria-label={`Unit on line ${n}`}
              value={unit}
              onChange={(e) => onUpdate({ unit: e.target.value })}
            >
              {unitChoices.map((u) => (
                <option key={u.code} value={u.code}>
                  {u.label || u.code}
                </option>
              ))}
            </select>
          ) : (
            option?.unit && <small>{option.unit}</small>
          )}
        </span>
      </label>
      <label className="line-editor-cell price">
        <span className="line-editor-label">{priceLabel}</span>
        <input
          required
          disabled={off}
          type="number"
          inputMode="decimal"
          min="0"
          step="0.000001"
          aria-label={`${priceLabel} on line ${n}`}
          data-line={index}
          data-field="unitPrice"
          value={line.unitPrice}
          onKeyDown={(e) => onKey(e, 'unitPrice')}
          onChange={(e) => onUpdate({ unitPrice: e.target.value })}
        />
      </label>
      {discount && (
        <label className="line-editor-cell disc">
          <span className="line-editor-label">Discount %</span>
          <input
            disabled={off}
            type="number"
            inputMode="decimal"
            min="0"
            max="100"
            step="0.01"
            placeholder="0"
            aria-label={`Discount percent on line ${n}`}
            data-line={index}
            data-field="discountPct"
            value={line.discountPct}
            onKeyDown={(e) => onKey(e, 'discountPct')}
            onChange={(e) => onUpdate({ discountPct: e.target.value })}
          />
        </label>
      )}
      <span className="line-editor-cell line-editor-amount" aria-label={`Line ${n} total`}>
        <span className="line-editor-label">Line total</span>
        <strong>
          {currency} {fmt(lineNet(line), 2)}
        </strong>
      </span>
      <button
        type="button"
        className="icon-button line-editor-remove"
        aria-label={`Remove line ${n}`}
        title="Remove line (Ctrl+Delete)"
        disabled={off}
        onClick={onRemove}
      >
        <Trash2 size={16} />
      </button>
    </motion.div>
  );
}
