import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { useStateData } from '../../app/workspace';
import { Table } from '../../components';
import { labelState, money, num, text } from '../../lib/format';
import { useDebounced, usePaged } from './api';
import { LotDrawer } from './LotDrawer';
import { dateTime, Delta, LoadMore, QueryState, SearchBox } from './shared';

/** The immutable stock ledger, server-paged, filtered by warehouse, item, reference, text and date range. */
export function LedgerTab() {
  const data = useStateData();
  const [warehouseId, setWarehouseId] = useState('');
  const [itemId, setItemId] = useState('');
  const [reference, setReference] = useState('');
  const [search, setSearch] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [lot, setLot] = useState<string>();
  const q = useDebounced(search);
  const ref = useDebounced(reference);
  const { query, rows } = usePaged('ledger', '/inventory/ledger', {
    warehouseId,
    itemId,
    reference: ref,
    q,
    from,
    to,
    limit: 50,
  });
  return (
    <>
      <div className="inv-toolbar">
        <div className="inv-filters">
          <SearchBox value={search} onChange={setSearch} placeholder="Search lot, item or reason…" />
          <input
            aria-label="Reference"
            placeholder="Reference (e.g. ADJ-)"
            value={reference}
            onChange={(e) => setReference(e.target.value)}
          />
          <select aria-label="Warehouse" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
            <option value="">All warehouses</option>
            {data.warehouses.map((w) => (
              <option key={w.id} value={w.id}>
                {text(w.name)}
              </option>
            ))}
          </select>
          <select aria-label="Item" value={itemId} onChange={(e) => setItemId(e.target.value)}>
            <option value="">All items</option>
            {data.items.map((i) => (
              <option key={i.id} value={i.id}>
                {text(i.name)}
              </option>
            ))}
          </select>
          <label className="inv-date">
            <span>From</span>
            <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="inv-date">
            <span>To</span>
            <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
          </label>
        </div>
      </div>
      <QueryState pending={query.isPending} error={query.error} retry={() => query.refetch()}>
        <Table
          rows={rows}
          compact
          pageSize={Math.max(rows.length, 1)}
          onRow={(r) => setLot(text(r.lot_id))}
          columns={[
            { label: 'When', render: (r) => dateTime(r.created_at) },
            {
              label: 'Item / lot',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.item)}</strong>
                  <span>{text(r.lot)}</span>
                </div>
              ),
            },
            { label: 'Warehouse', key: 'warehouse' },
            { label: 'Movement', render: (r) => <Delta value={r.delta} uom={r.uom} /> },
            {
              label: 'Value',
              render: (r) => (
                <span className={num(r.value_delta) < 0 ? 'negative' : 'positive'}>
                  {money(r.value_delta)}
                </span>
              ),
            },
            { label: 'Reference', key: 'reference' },
            { label: 'Reason', render: (r) => <span className="capitalize">{labelState(r.reason)}</span> },
            { label: 'By', key: 'actor' },
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
