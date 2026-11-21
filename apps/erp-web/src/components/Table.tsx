import { useState } from 'react';
import type { ReactNode } from 'react';
import { motion } from 'motion/react';
import { ArrowDown, ArrowUp, ArrowUpDown, ChevronLeft, ChevronRight, Search } from 'lucide-react';
import type { Row } from '../lib/api';
import { fmt, text } from '../lib/format';
import { easeOut, useMotionPreferences } from '../motion/motion';
import { Empty } from './Empty';

/** A column renders `render(row)` when given, otherwise `row[key]` as text. A `key` makes the header sortable. */
export type Column = { label: string; key?: string; render?: (r: Row) => ReactNode; className?: string };
export function Table({
  rows,
  columns,
  search = false,
  placeholder = 'Search records…',
  onRow,
  compact = false,
  pageSize = 8,
}: {
  rows: Row[];
  columns: Column[];
  search?: boolean;
  placeholder?: string;
  onRow?: (r: Row) => void;
  compact?: boolean;
  pageSize?: number;
}) {
  const { reduced } = useMotionPreferences();
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<{ key: string; desc: boolean }>();
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
          <span className="muted">{fmt(filtered.length)} records</span>
        </div>
      )}
      <div className={`table-scroll ${compact ? 'compact' : ''}`}>
        <table>
          <thead>
            <tr>
              {columns.map((c, i) => (
                <th key={i} className={c.className}>
                  {c.key ? (
                    <button
                      className="sort-button"
                      onClick={() => {
                        setSort({ key: c.key!, desc: sort && sort.key === c.key ? !sort.desc : false });
                      }}
                    >
                      {c.label}
                      {sort?.key === c.key ? (
                        sort.desc ? (
                          <ArrowDown size={12} />
                        ) : (
                          <ArrowUp size={12} />
                        )
                      ) : (
                        <ArrowUpDown size={12} />
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
                onClick={onRow ? () => onRow(r) : undefined}
              >
                {columns.map((c, j) => (
                  <td className={c.className} key={j}>
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
