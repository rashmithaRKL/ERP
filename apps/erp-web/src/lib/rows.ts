import type { Row } from './api';
import { text } from './format';

/** Open documents: anything not closed, cancelled or completed. */
export const isActive = (r: Row) => !['CLOSED', 'CANCELLED', 'COMPLETED'].includes(text(r.status));

/**
 * Free-text search across every column plus a status tab filter.
 * `tab` is `'all'`, `'active'` (see {@link isActive}) or an exact status code.
 */
export const filterRows = (rows: Row[], search: string, tab: string) =>
  rows.filter(
    (r) =>
      Object.values(r).some((x) => text(x).toLowerCase().includes(search.toLowerCase())) &&
      (tab === 'all' || text(r.status) === tab || (tab === 'active' && isActive(r))),
  );
