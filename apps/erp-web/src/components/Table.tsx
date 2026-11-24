import { useLayoutEffect, useRef, useState } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import { motion } from 'motion/react';
import { ArrowDown, ArrowUp, ArrowUpDown, ChevronLeft, ChevronRight, Search } from 'lucide-react';
import type { Row } from '../lib/api';
import { fmt, text } from '../lib/format';
import { easeOut, useMotionPreferences } from '../motion/motion';
import { Empty } from './Empty';

/** A column renders `render(row)` when given, otherwise `row[key]` as text. A `key` makes the header sortable. */
export type Column = { label: string; key?: string; render?: (r: Row) => ReactNode; className?: string };

type ScrollState = { overflow: boolean; scrolled: boolean; more: boolean };

/**
 * Horizontal overflow of a table's scroll container: drives the sticky first column, the edge shadows and keyboard
 * scrolling (the container becomes a focusable, labelled region only while it actually scrolls).
 */
function useHorizontalScroll() {
  const ref = useRef<HTMLDivElement>(null);
  const [state, setState] = useState<ScrollState>({ overflow: false, scrolled: false, more: false });
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    const update = () => {
      const overflow = element.scrollWidth - element.clientWidth > 1;
      const next = {
        overflow,
        scrolled: overflow && element.scrollLeft > 1,
        more: overflow && element.scrollLeft + element.clientWidth < element.scrollWidth - 1,
      };
      setState((current) =>
        current.overflow === next.overflow && current.scrolled === next.scrolled && current.more === next.more
          ? current
          : next,
      );
    };
    update();
    element.addEventListener('scroll', update, { passive: true });
    const observer = new ResizeObserver(update);
    observer.observe(element);
    if (element.firstElementChild) observer.observe(element.firstElementChild);
    return () => {
      element.removeEventListener('scroll', update);
      observer.disconnect();
    };
  }, []);
  return { ref, ...state };
}

/**
 * Data table with optional search, client-side sorting and paging. Inside dialogs and drawers (styles.css) secondary
 * text wraps and padding tightens; whatever still overflows scrolls with a sticky first column and edge shadows, and at
 * phone width each row becomes a card labelled by its column headers.
 */
export function Table({
  rows,
  columns,
  search = false,
  placeholder = 'Search records…',
  onRow,
  compact = false,
  pageSize = 8,
  label,
}: {
  rows: Row[];
  columns: Column[];
  search?: boolean;
  placeholder?: string;
  /** Makes rows clickable and keyboard-operable (Enter/Space). */
  onRow?: (r: Row) => void;
  compact?: boolean;
  pageSize?: number;
  /** Accessible name of the table (also names its scroll region). */
  label?: string;
}) {
  const { reduced } = useMotionPreferences();
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<{ key: string; desc: boolean }>();
  const scroll = useHorizontalScroll();
  let filtered = rows.filter((r) =>
    Object.values(r).some((v) => text(v).toLowerCase().includes(query.toLowerCase())),
  );
  if (sort)
    filtered = [...filtered].sort(
      (a, b) =>
        (typeof a[sort.key] === 'number'
          ? Number(a[sort.key]) - Number(b[sort.key])
          : text(a[sort.key]).localeCompare(text(b[sort.key]))) * (sort.desc ? -1 : 1),
    );
  const safePage = Math.min(page, Math.max(0, Math.ceil(filtered.length / pageSize) - 1));
  const visible = filtered.slice(safePage * pageSize, (safePage + 1) * pageSize);
  const rowKey = (r: Row) => (e: KeyboardEvent<HTMLTableRowElement>) => {
    if (e.target !== e.currentTarget || (e.key !== 'Enter' && e.key !== ' ')) return;
    e.preventDefault();
    onRow?.(r);
  };
  return (
    <>
      {search && (
        <div className="table-toolbar">
          <div className="search-input">
            <Search size={16} />
            <input
              aria-label={placeholder}
              placeholder={placeholder}
              value={query}
              onChange={(e) => {
                setQuery(e.target.value);
                setPage(0);
              }}
            />
          </div>
          <span className="muted" aria-live="polite">
            {fmt(filtered.length)} records
          </span>
        </div>
      )}
      <div
        ref={scroll.ref}
        className={`table-scroll ${compact ? 'compact' : ''}`}
        data-overflow={scroll.overflow || undefined}
        data-scrolled={scroll.scrolled || undefined}
        data-more={scroll.more || undefined}
        {...(scroll.overflow
          ? { role: 'region', tabIndex: 0, 'aria-label': `${label || 'Table'}, scrolls sideways` }
          : {})}
      >
        <table aria-label={label}>
          <thead>
            <tr>
              {columns.map((c, i) => (
                <th
                  key={i}
                  scope="col"
                  className={c.className}
                  aria-sort={
                    c.key && sort?.key === c.key ? (sort.desc ? 'descending' : 'ascending') : undefined
                  }
                >
                  {c.key ? (
                    <button
                      type="button"
                      className="sort-button"
                      onClick={() => {
                        setSort({ key: c.key!, desc: sort && sort.key === c.key ? !sort.desc : false });
                      }}
                    >
                      {c.label}
                      {sort?.key === c.key ? (
                        sort.desc ? (
                          <ArrowDown size={12} aria-hidden />
                        ) : (
                          <ArrowUp size={12} aria-hidden />
                        )
                      ) : (
                        <ArrowUpDown size={12} aria-hidden />
                      )}
                    </button>
                  ) : (
                    c.label
                  )}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {visible.map((r, i) => (
              <motion.tr
                key={r.id || i}
                layout={reduced ? false : 'position'}
                initial={false}
                animate={{ opacity: 1 }}
                transition={{ layout: { duration: 0.2, ease: easeOut } }}
                className={onRow ? 'clickable-row' : ''}
                tabIndex={onRow ? 0 : undefined}
                onClick={onRow ? () => onRow(r) : undefined}
                onKeyDown={onRow ? rowKey(r) : undefined}
              >
                {columns.map((c, j) => (
                  <td className={c.className} key={j} data-label={c.label || undefined}>
                    {c.render ? c.render(r) : text(r[c.key || ''])}
                  </td>
                ))}
              </motion.tr>
            ))}
          </tbody>
        </table>
        {!visible.length && (
          <Empty
            title={query ? 'No matching records' : 'No records yet'}
            body={query ? 'Try a different search.' : undefined}
          />
        )}
      </div>
      {!compact && (
        <div className="table-footer">
          <span>
            Showing {filtered.length ? safePage * pageSize + 1 : 0}–
            {Math.min((safePage + 1) * pageSize, filtered.length)} of {fmt(filtered.length)}
          </span>
          <div>
            <button
              className="icon-button"
              aria-label="Previous page"
              disabled={safePage === 0}
              onClick={() => setPage(safePage - 1)}
            >
              <ChevronLeft size={16} />
            </button>
            <span>
              {safePage + 1} / {Math.max(1, Math.ceil(filtered.length / pageSize))}
            </span>
            <button
              className="icon-button"
              aria-label="Next page"
              disabled={(safePage + 1) * pageSize >= filtered.length}
              onClick={() => setPage(safePage + 1)}
            >
              <ChevronRight size={16} />
            </button>
          </div>
        </div>
      )}
    </>
  );
}
