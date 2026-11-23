import { useId } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../lib/api';
import { fmt } from '../lib/format';

/** One unit a quantity of the item can be entered in (`GET /api/items/{id}/units`). */
type UnitOption = {
  code: string;
  name: string;
  decimals: number;
  factor: number | string;
  source: 'BASE' | 'ITEM' | 'STANDARD';
  purchase: boolean;
  sales: boolean;
};
type ItemUnits = { itemId: string; baseUnit: string; baseDecimals: number; units: UnitOption[] };

/** The entered quantity plus its base-unit equivalent (display preview; the API converts authoritatively). */
export type UnitQuantity = { quantity: string; unit: string; baseQuantity?: string; exact?: boolean };

/**
 * Quantity + unit picker for one item. Lists the item's base unit, its own conversions (filtered by `usage`) and
 * standard units of the same kind, and shows the base-unit equivalent rounded half-up at the base unit's precision,
 * exactly as `UnitService.toBase` does on the server. With `name`, the inputs also post as `name` and `${name}Unit`
 * in a plain form.
 */
export function UnitQuantityInput({
  itemId,
  value,
  onChange,
  label = 'Quantity',
  usage = 'all',
  name,
  required = false,
  disabled = false,
  hint,
}: {
  itemId: string;
  value: UnitQuantity;
  onChange: (value: UnitQuantity) => void;
  label?: string;
  usage?: 'all' | 'purchase' | 'sales';
  name?: string;
  required?: boolean;
  disabled?: boolean;
  hint?: string;
}) {
  const id = useId();
  const units = useQuery({
    queryKey: ['items', 'units', itemId],
    queryFn: () => request<ItemUnits>(`/items/${itemId}/units`),
    enabled: !!itemId,
  });
  const data = units.data;
  const options = (data?.units || []).filter(
    (u) => usage === 'all' || u.source !== 'ITEM' || (usage === 'purchase' ? u.purchase : u.sales),
  );
  const unit = value.unit || data?.baseUnit || '';
  const choice = options.find((o) => o.code === unit);
  const base = data && choice ? convert(value.quantity, choice.factor, data.baseDecimals) : undefined;
  const tooPrecise = !!choice && decimalsOf(value.quantity) > choice.decimals;

  function emit(quantity: string, nextUnit: string) {
    const option = options.find((o) => o.code === nextUnit);
    const result = data && option ? convert(quantity, option.factor, data.baseDecimals) : undefined;
    onChange({ quantity, unit: nextUnit, baseQuantity: result?.value, exact: result?.exact });
  }

  return (
    <div className="field unit-quantity">
      <span>
        <label htmlFor={id}>{label}</label>
        {units.isPending && itemId && <small>Loading units…</small>}
      </span>
      <div className="unit-quantity-control">
        <input
          id={id}
          name={name}
          type="number"
          inputMode="decimal"
          required={required}
          disabled={disabled || !itemId}
          min="0"
          step={choice ? stepFor(choice.decimals) : 'any'}
          value={value.quantity}
          aria-invalid={tooPrecise || undefined}
          onChange={(e) => emit(e.target.value, unit)}
        />
        <select
          aria-label={`${label} unit`}
          name={name ? `${name}Unit` : undefined}
          disabled={disabled || !data}
          value={unit}
          onChange={(e) => emit(value.quantity, e.target.value)}
        >
          {!data && <option value="">Unit</option>}
          {options.map((o) => (
            <option key={o.code} value={o.code}>
              {o.code}
            </option>
          ))}
        </select>
      </div>
      <em className={tooPrecise ? 'negative' : undefined}>
        {units.isError
          ? (units.error as Error).message
          : tooPrecise
            ? `${choice!.name} allows ${choice!.decimals} decimal place${choice!.decimals === 1 ? '' : 's'}`
            : base && choice && choice.source !== 'BASE'
              ? `= ${fmt(base.value, data!.baseDecimals)} ${data!.baseUnit}${base.exact ? '' : ' (rounded)'} · 1 ${choice.code} = ${fmt(choice.factor, 12)} ${data!.baseUnit}`
              : hint || (data ? `Stock is kept in ${data.baseUnit}` : '')}
      </em>
    </div>
  );
}

const stepFor = (decimals: number) => (decimals <= 0 ? '1' : `0.${'0'.repeat(decimals - 1)}1`);

function decimalsOf(value: string) {
  const match = /\.(\d*?)0*$/.exec(value.trim());
  return match ? match[1].length : 0;
}

/** Exact decimal multiplication (BigInt) rounded half-up at `decimals`; undefined for an empty/invalid quantity. */
function convert(quantity: string, factor: number | string, decimals: number) {
  const a = parse(quantity);
  const b = parse(typeof factor === 'number' ? factor.toFixed(12) : factor);
  if (!a || !b) return undefined;
  const product = a.digits * b.digits;
  const scale = a.scale + b.scale;
  let rounded: bigint;
  let exact = true;
  if (scale <= decimals) rounded = product * 10n ** BigInt(decimals - scale);
  else {
    const divisor = 10n ** BigInt(scale - decimals);
    const remainder = product % divisor;
    exact = remainder === 0n;
    rounded = product / divisor;
    const magnitude = remainder < 0n ? -remainder : remainder;
    if (magnitude * 2n >= divisor) rounded += product < 0n ? -1n : 1n;
  }
  return { value: format(rounded, decimals), exact };
}

function parse(value: string) {
  const match = /^(-)?(\d*)(?:\.(\d*))?$/.exec(value.trim());
  if (!match || (!match[2] && !match[3])) return undefined;
  const fraction = match[3] || '';
  const digits = BigInt((match[2] || '0') + fraction);
  return { digits: match[1] ? -digits : digits, scale: fraction.length };
}

function format(value: bigint, decimals: number) {
  const negative = value < 0n;
  const raw = (negative ? -value : value).toString().padStart(decimals + 1, '0');
  const whole = raw.slice(0, raw.length - decimals);
  const fraction = decimals ? raw.slice(raw.length - decimals).replace(/0+$/, '') : '';
  return `${negative ? '-' : ''}${whole}${fraction ? `.${fraction}` : ''}`;
}
