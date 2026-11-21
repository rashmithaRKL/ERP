import type { Row } from '../lib/api';
import { text, today } from '../lib/format';
import type { Field } from './FormDialog';

/** Positive decimal quantity input (spread and override `min` for fields that allow zero). */
export const qtyField = (name: string, label: string, value?: string): Field => ({
  name,
  label,
  type: 'number',
  min: '0.000001',
  defaultValue: value,
});
/** "Required by" date input; the default is today's date (Asia/Colombo) when the app loaded. */
export const dateField: Field = {
  name: 'dueDate',
  label: 'Required by',
  type: 'date',
  defaultValue: today(),
};
/** Rows to `<select>` options, using `r.id` as the value and `r[label]` as the text. */
export const option = (rows: Row[], label: string) =>
  rows.map((r) => ({ value: r.id, label: text(r[label]) }));
export const selectField = (
  name: string,
  label: string,
  options: { value: string; label: string }[],
  value?: string,
): Field => ({ name, label, options, defaultValue: value });
