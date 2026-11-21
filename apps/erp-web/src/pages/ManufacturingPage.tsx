import { useState } from 'react';
import { useStateData, useWorkspace } from '../app/workspace';
import { Panel, Table } from '../components';
import { useOrderColumns } from '../features/manufacturing/orderColumns';
import { labelState, text } from '../lib/format';
import { filterRows } from '../lib/rows';
import { Tabs } from '../motion/motion';

/** Route `/manufacturing`: manufacturing order list. */
export default function ManufacturingPage() {
  const { openOrder, search, summary } = useWorkspace();
  const { active } = summary;
  const data = useStateData();
  const orderColumns = useOrderColumns();
  const [tab, setTab] = useState('all');
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Active orders</span>
          <strong>{active.length}</strong>
        </div>
        <div>
          <span>Awaiting approval</span>
          <strong>{data.orders.filter((r) => r.status === 'DRAFT').length}</strong>
        </div>
        <div>
          <span>In production</span>
          <strong>
            {
              data.orders.filter((r) => ['IN_PROGRESS', 'PARTIALLY_COMPLETED'].includes(text(r.status)))
                .length
            }
          </strong>
        </div>
        <div>
          <span>Quality review</span>
          <strong>{data.orders.filter((r) => r.status === 'QUALITY_REVIEW').length}</strong>
        </div>
      </div>
      <Panel>
        <Tabs
          label="Manufacturing order filters"
          items={['all', 'active', 'DRAFT', 'COMPLETED', 'CLOSED'].map((t) => ({
            value: t,
            label: t === 'all' ? 'All orders' : labelState(t),
          }))}
          value={tab}
          onChange={setTab}
        />
        <Table
          rows={filterRows(data.orders, search, tab)}
          columns={orderColumns}
          search
          placeholder="Search order, customer or product…"
          onRow={(r) => openOrder(r.id)}
        />
      </Panel>
    </>
  );
}
