import { useState } from 'react';
import { ChevronDown, LoaderCircle, X } from 'lucide-react';
import type { Row } from '../../lib/api';
import { text } from '../../lib/format';
import { useDebounced, usePaged } from './api';
import { qty, SearchBox, StatusBadge } from './shared';

/**
 * Searchable lot chooser for document lines. Each result shows the item, warehouse, quality status and
 * available / reserved quantity. `movable` limits results to lots with unreserved stock that can be moved
 * or written down (receipts awaiting incoming inspection are excluded).
 */
export function LotPicker({
  label,
  value,
  onChange,
  warehouseId,
  movable = false,
  exclude = [],
  disabled = false,
}: {
  label: string;
  value: Row | null;
  onChange: (lot: Row | null) => void;
  warehouseId?: string;
  movable?: boolean;
  exclude?: string[];
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const q = useDebounced(search);
  const { query, rows } = usePaged(
    'lot-picker',
    '/inventory/lots',
    { q, warehouseId, movable, limit: 20 },
    open,
  );
  const results = rows.filter((r) => !exclude.includes(r.id) || r.id === value?.id);
  return (
    <div className="field lot-picker">
      <span>{label}</span>
      {value ? (
        <div className="lot-choice">
          <LotSummary lot={value} />
          <button
            type="button"
            className="icon-button"
            aria-label={`Change ${label.toLowerCase()}`}
            disabled={disabled}
            onClick={() => {
              onChange(null);
              setOpen(true);
            }}
          >
            <X size={15} />
          </button>
        </div>
      ) : (
        <button
          type="button"
          className="lot-trigger"
          aria-expanded={open}
          disabled={disabled}
          onClick={() => setOpen(!open)}
        >
          <span>{warehouseId === '' ? 'Choose the source warehouse first' : 'Choose a lot'}</span>
          <ChevronDown size={15} />
        </button>
      )}
      {open && !value && (
        <div className="lot-results">
          <SearchBox value={search} onChange={setSearch} placeholder="Search lot, item or SKU…" />
          {query.isPending ? (
            <div className="inv-state">
              <LoaderCircle className="spin" size={16} />
            </div>
          ) : query.isError ? (
            <div className="form-error">{query.error.message}</div>
          ) : results.length ? (
            <ul role="listbox" aria-label={label}>
              {results.map((lot) => (
                <li key={lot.id}>
                  <button
                    type="button"
                    role="option"
                    aria-selected={false}
                    onClick={() => {
                      onChange(lot);
                      setOpen(false);
                      setSearch('');
                    }}
                  >
                    <LotSummary lot={lot} />
                  </button>
                </li>
              ))}
              {query.hasNextPage && (
                <li>
                  <button type="button" className="text-button" onClick={() => query.fetchNextPage()}>
                    Show more lots
                  </button>
                </li>
              )}
            </ul>
          ) : (
            <p className="muted inv-small">No matching lots with stock.</p>
          )}
        </div>
      )}
    </div>
  );
}

export function LotSummary({ lot }: { lot: Row }) {
  return (
    <div className="lot-summary">
      <div>
        <strong>{text(lot.number)}</strong>
        <StatusBadge value={lot.status} />
      </div>
      <span>
        {text(lot.item)} · {text(lot.warehouse)}
      </span>
      <span>
        {qty(lot.available, lot.uom)} available · {qty(lot.reserved, lot.uom)} reserved
      </span>
    </div>
  );
}
