import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { useStateData } from '../../app/workspace';
import { Table } from '../../components';
import { money, text } from '../../lib/format';
import { ProductMark } from '../products/ProductMark';
import { useDebounced, usePaged } from './api';
import { LotDrawer } from './LotDrawer';
import { LoadMore, QueryState, SearchBox, StatusBadge, qty } from './shared';

/** Server-paged lot list with warehouse / status / text filters and the lot drawer. */
export function LotsTab() {
  const data = useStateData();
  const [warehouseId, setWarehouseId] = useState('');
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const [includeEmpty, setIncludeEmpty] = useState(false);
  const [lot, setLot] = useState<string>();
  const q = useDebounced(search);
  const { query, rows } = usePaged('lots', '/inventory/lots', {
    warehouseId,
    status,
    q,
    includeEmpty,
    limit: 50,
  });
  return (
    <>
      <div className="inv-toolbar">
        <div className="inv-filters">
          <SearchBox value={search} onChange={setSearch} placeholder="Search lot, item or SKU…" />
          <select aria-label="Warehouse" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
            <option value="">All warehouses</option>
            {data.warehouses.map((w) => (
              <option key={w.id} value={w.id}>
                {text(w.name)}
              </option>
            ))}
          </select>
          <select aria-label="Quality status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Any status</option>
            <option value="RELEASED">Released</option>
            <option value="QUARANTINE">Quarantine</option>
            <option value="REJECTED">Rejected</option>
          </select>
          <label className="inv-check">
            <input
              type="checkbox"
              checked={includeEmpty}
              onChange={(e) => setIncludeEmpty(e.target.checked)}
            />
            Include empty lots
          </label>
        </div>
      </div>
      <QueryState pending={query.isPending} error={query.error} retry={() => query.refetch()}>
        <Table
          rows={rows}
          compact
          pageSize={Math.max(rows.length, 1)}
          onRow={(r) => setLot(r.id)}
          columns={[
            {
              label: 'Material / product',
              render: (r) => (
                <div className="product-cell">
                  <ProductMark kind={r.item} small />
                  <div className="cell-stack">
                    <strong>{text(r.item)}</strong>
                    <span>{text(r.sku)}</span>
                  </div>
                </div>
              ),
            },
            {
              label: 'Lot',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.number)}</strong>
                  <span>
                    {r.source_lot ? `from ${text(r.source_lot)}` : text(r.source_type).toLowerCase()}
                    {r.open_count ? ` · in ${text(r.open_count)}` : ''}
                  </span>
                </div>
              ),
            },
            { label: 'Warehouse', key: 'warehouse' },
            { label: 'On hand', render: (r) => qty(r.quantity, r.uom) },
            { label: 'Reserved', render: (r) => qty(r.reserved, r.uom) },
            { label: 'Available', render: (r) => qty(r.available, r.uom) },
            { label: 'Value', render: (r) => money(r.value_amount) },
            { label: 'Status', render: (r) => <StatusBadge value={r.status} /> },
          ]}
        />
        <LoadMore query={query} count={rows.length} />
      </QueryState>
      <AnimatePresence>
        {lot && <LotDrawer key={lot} id={lot} onClose={() => setLot(undefined)} onOpen={setLot} />}
      </AnimatePresence>
    </>
  );
}
