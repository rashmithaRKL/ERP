import { useState } from 'react';
import { useStateData } from '../app/workspace';
import { Badge, Panel, Table } from '../components';
import { ProductMark } from '../features/products/ProductMark';
import { date, fmt, labelState, money, num, text } from '../lib/format';
import { Tabs } from '../motion/motion';

/** Route `/inventory`: lots by status and the stock ledger. */
export default function InventoryPage() {
  const data = useStateData();
  const [tab, setTab] = useState('all');
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Warehouses</span>
          <strong>{data.warehouses.length}</strong>
        </div>
        <div>
          <span>Tracked lots</span>
          <strong>{data.lots.filter((l) => num(l.quantity) > 0).length}</strong>
        </div>
        <div>
          <span>Quarantined lots</span>
          <strong>{data.lots.filter((l) => l.status === 'QUARANTINE').length}</strong>
        </div>
        <div>
          <span>Inventory value</span>
          <strong>{money(data.items.reduce((v, i) => v + num(i.stock_value), 0))}</strong>
        </div>
      </div>
      <Panel>
        <Tabs
          label="Inventory views"
          items={['all', 'RELEASED', 'QUARANTINE', 'ledger'].map((t) => ({
            value: t,
            label: t === 'all' ? 'All lots' : labelState(t),
          }))}
          value={tab}
          onChange={setTab}
        />
        {tab === 'ledger' ? (
          <Table
            rows={data.ledger}
            search
            columns={[
              { label: 'Item', key: 'item' },
              { label: 'Lot', key: 'lot' },
              {
                label: 'Movement',
                key: 'delta',
                render: (r) => (
                  <span className={num(r.delta) > 0 ? 'positive' : 'negative'}>
                    {num(r.delta) > 0 ? '+' : ''}
                    {fmt(r.delta, 3)} {text(r.uom)}
                  </span>
                ),
              },
              { label: 'Reference', key: 'reference' },
              { label: 'Reason', render: (r) => labelState(r.reason) },
              { label: 'Actor', key: 'actor' },
              { label: 'Date', render: (r) => date(r.created_at) },
            ]}
          />
        ) : (
          <Table
            rows={data.lots.filter((r) => (tab === 'all' || r.status === tab) && num(r.quantity) > 0)}
            search
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
              { label: 'Lot', key: 'number' },
              { label: 'Warehouse', key: 'warehouse' },
              {
                label: 'On hand',
                key: 'quantity',
                render: (r) => `${fmt(r.quantity, 3)} ${r.uom}`,
              },
              { label: 'Reserved', render: (r) => `${fmt(r.reserved, 3)} ${r.uom}` },
              { label: 'Status', render: (r) => <Badge value={r.status} /> },
            ]}
          />
        )}
      </Panel>
    </>
  );
}
