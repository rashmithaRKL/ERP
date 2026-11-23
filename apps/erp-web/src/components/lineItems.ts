/** One editable document line. Values are the raw input strings; the server stays authoritative for totals. */
export type LineItem = {
  /** Stable React key for the row (not sent to the server). */
  key: string;
  /** Server id of an existing line, kept when a draft is edited. */
  id?: string;
  itemId: string;
  quantity: string;
  unitPrice: string;
  discountPct: string;
};

/** A choice in the item picker. `unit` sets the quantity step (`pcs` = whole numbers); `price` pre-fills new lines. */
export type LineItemOption = { value: string; label: string; unit?: string; price?: string };

/** An empty line (or one pre-filled with `patch`). */
export const newLine = (patch: Partial<LineItem> = {}): LineItem => ({
  key: crypto.randomUUID(),
  itemId: '',
  quantity: '',
  unitPrice: '',
  discountPct: '',
  ...patch,
});

const amount = (x: string) => (Number.isFinite(Number(x)) ? Number(x) : 0);
/** Display-only gross amount: quantity × unit price. */
export const lineGross = (l: LineItem) => amount(l.quantity) * amount(l.unitPrice);
/** Display-only net amount after the line discount. */
export const lineNet = (l: LineItem) => lineGross(l) * (1 - amount(l.discountPct) / 100);
