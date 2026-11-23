import { useState } from 'react';
import { useStateData } from '../app/workspace';
import { Panel } from '../components';
import { AdjustmentsTab } from '../features/inventory/AdjustmentsTab';
import { usePaged } from '../features/inventory/api';
import { CountsTab } from '../features/inventory/CountsTab';
import { LedgerTab } from '../features/inventory/LedgerTab';
import { LotsTab } from '../features/inventory/LotsTab';
import { TransfersTab } from '../features/inventory/TransfersTab';
import { WarehousesTab } from '../features/inventory/WarehousesTab';
import '../features/inventory/inventory.css';
import { money, num } from '../lib/format';
import { Tabs } from '../motion/motion';

const TABS = [
  { value: 'lots', label: 'Lots' },
  { value: 'ledger', label: 'Ledger' },
  { value: 'adjustments', label: 'Adjustments' },
  { value: 'transfers', label: 'Transfers' },
  { value: 'counts', label: 'Counts' },
  { value: 'warehouses', label: 'Warehouses' },
];

/** Route `/inventory`: lots, ledger, adjustments, transfers, cycle counts and warehouses. */
export default function InventoryPage() {
  const data = useStateData();
  const [tab, setTab] = useState('lots');
  const approvals = usePaged('adjustments', '/inventory/adjustments', {
    status: 'APPROVAL_REQUIRED',
    limit: 50,
  });
  const openCounts = usePaged('counts', '/inventory/counts', { status: 'OPEN', limit: 50 });
  const pending = approvals.rows.length;
  const counting = openCounts.rows.length;
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Active warehouses</span>
          <strong>{data.warehouses.filter((w) => w.active !== false).length}</strong>
        </div>
        <div>
          <span>Lots in stock</span>
          <strong>{data.lots.filter((l) => num(l.quantity) > 0).length}</strong>
        </div>
        <div>
          <span>Awaiting approval</span>
          <strong className={pending ? 'inv-attention' : undefined}>
            {pending}
            {approvals.query.hasNextPage ? '+' : ''}
          </strong>
        </div>
        <div>
          <span>Inventory value</span>
          <strong>{money(data.items.reduce((v, i) => v + num(i.stock_value), 0))}</strong>
        </div>
      </div>
      <Panel className="inventory-panel">
        <Tabs
          label="Inventory views"
          items={TABS.map((t) => ({
            ...t,
            count:
              t.value === 'adjustments' && pending
                ? pending
                : t.value === 'counts' && counting
                  ? counting
                  : undefined,
          }))}
          value={tab}
          onChange={setTab}
        />
        {tab === 'lots' && <LotsTab />}
        {tab === 'ledger' && <LedgerTab />}
        {tab === 'adjustments' && <AdjustmentsTab pending={pending} />}
        {tab === 'transfers' && <TransfersTab />}
        {tab === 'counts' && <CountsTab open={counting} />}
        {tab === 'warehouses' && <WarehousesTab />}
      </Panel>
    </>
  );
}
