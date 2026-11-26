import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { useTabParam } from '../app/urlState';
import { useStateData } from '../app/workspace';
import { Panel } from '../components';
import { useMaintenanceSync } from '../features/maintenance/api';
import { DowntimeTab } from '../features/maintenance/DowntimeTab';
import { PlansTab } from '../features/maintenance/PlansTab';
import { WorkOrderDrawer } from '../features/maintenance/WorkOrderDrawer';
import { WorkOrdersTab } from '../features/maintenance/WorkOrdersTab';
import { fmt, num } from '../lib/format';
import { Tabs } from '../motion/motion';

const TABS = [
  { value: 'orders', label: 'Work orders' },
  { value: 'plans', label: 'Plans' },
  { value: 'downtime', label: 'Downtime' },
];

/** Route `/maintenance`: maintenance work orders, preventive plans and machine downtime (`?tab=plans|downtime`). */
export default function MaintenancePage() {
  useMaintenanceSync();
  const data = useStateData();
  const [tab, setTab] = useTabParam(
    TABS.map((t) => t.value),
    'orders',
  );
  const [order, setOrder] = useState<string>();
  const m = data.maintenance;
  const breakdowns = num(m?.open_breakdowns);
  const overdue = num(m?.overdue_plans);
  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Open breakdowns</span>
          <strong className={breakdowns ? 'mnt-alert' : undefined}>{fmt(breakdowns)}</strong>
        </div>
        <div>
          <span>Awaiting approval</span>
          <strong className={num(m?.awaiting_approval) ? 'inv-attention' : undefined}>
            {fmt(m?.awaiting_approval)}
          </strong>
        </div>
        <div>
          <span>Work in progress</span>
          <strong>{fmt(m?.in_progress)}</strong>
        </div>
        <div>
          <span>Overdue preventive plans</span>
          <strong className={overdue ? 'mnt-alert' : undefined}>{fmt(overdue)}</strong>
        </div>
      </div>
      <Panel className="inventory-panel">
        <Tabs
          label="Maintenance views"
          items={TABS.map((t) => ({
            ...t,
            count:
              t.value === 'plans' && overdue
                ? overdue
                : t.value === 'downtime' && num(m?.open_downtime)
                  ? num(m?.open_downtime)
                  : undefined,
          }))}
          value={tab}
          onChange={setTab}
        />
        {tab === 'orders' && <WorkOrdersTab onOpen={setOrder} />}
        {tab === 'plans' && <PlansTab onOpenOrder={setOrder} />}
        {tab === 'downtime' && <DowntimeTab onOpenOrder={setOrder} />}
      </Panel>
      <AnimatePresence>
        {order && <WorkOrderDrawer key={order} id={order} onClose={() => setOrder(undefined)} />}
      </AnimatePresence>
    </>
  );
}
