import { motion } from 'motion/react';
import { ArrowDownToLine, Boxes, CircleDollarSign, Layers, TriangleAlert } from 'lucide-react';
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import { useStateData, useWorkspace } from '../app/workspace';
import { Empty, Panel, Stat, Table } from '../components';
import { csv, fmt, labelState, money, num } from '../lib/format';
import { stagger } from '../motion/motion';

/** Route `/reports`: production costs, reconciliation and the posted journal (finance roles only). */
export default function ReportsPage() {
  const { days, summary } = useWorkspace();
  const { output, scrap } = summary;
  const data = useStateData();
  return data.financial ? (
    <>
      <motion.div className="stats-grid" variants={stagger} initial="hidden" animate="visible">
        <Stat
          label="Posted manufacturing cost"
          value={money(data.financial.actual_cost)}
          icon={CircleDollarSign}
          note="Materials + conversion"
        />
        <Stat
          label="Unfinished WIP value"
          value={money(data.financial.wip)}
          icon={Layers}
          note="Remains in production"
        />
        <Stat
          label="Recorded good output"
          value={fmt(output)}
          icon={Boxes}
          note={`${days}-day reporting period`}
        />
        <Stat label="Recorded scrap" value={fmt(scrap)} icon={TriangleAlert} note="Expensed separately" />
      </motion.div>
      <div className="reports-layout">
        <Panel title="Production cost composition" subtitle="Posted cost across all manufacturing orders">
          <div className="cost-composition">
            <div className="cost-donut">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    isAnimationActive={false}
                    data={[
                      {
                        name: 'Raw materials',
                        value: data.orders.reduce((v, r) => v + num(r.material_cost), 0),
                      },
                      {
                        name: 'Labour & machinery',
                        value: data.orders.reduce((v, r) => v + num(r.conversion_cost), 0),
                      },
                    ]}
                    dataKey="value"
                    innerRadius={56}
                    outerRadius={78}
                    paddingAngle={3}
                  >
                    <Cell fill="#d72638" />
                    <Cell fill="#24364d" />
                  </Pie>
                  <Tooltip formatter={(v) => money(v)} />
                </PieChart>
              </ResponsiveContainer>
            </div>
            <div>
              <p>
                <i className="legend-dot red" />
                Raw materials
                <strong>{money(data.orders.reduce((v, r) => v + num(r.material_cost), 0))}</strong>
              </p>
              <p>
                <i className="legend-dot navy" />
                Labour & machinery
                <strong>{money(data.orders.reduce((v, r) => v + num(r.conversion_cost), 0))}</strong>
              </p>
            </div>
          </div>
        </Panel>
        <Panel title="Cost reconciliation" subtitle="Material + conversion = WIP + finished output + scrap">
          <div className="reconciliation">
            {['material_cost', 'conversion_cost', 'wip_cost', 'finished_cost', 'scrap_cost'].map((k) => (
              <div key={k}>
                <span>{labelState(k)}</span>
                <strong>{money(data.orders.reduce((v, r) => v + num(r[k]), 0))}</strong>
              </div>
            ))}
          </div>
        </Panel>
      </div>
      <Panel
        title="Manufacturing cost register"
        className="spaced-panel"
        action={
          <button className="button small secondary" onClick={() => csv('manufacturing-costs', data.orders)}>
            <ArrowDownToLine size={14} />
            CSV
          </button>
        }
      >
        <Table
          rows={data.orders}
          columns={[
            { label: 'Order', key: 'number' },
            { label: 'Product', key: 'product' },
            { label: 'Estimated materials', render: (r) => money(r.estimated_cost) },
            { label: 'Actual materials', render: (r) => money(r.material_cost) },
            { label: 'Conversion', render: (r) => money(r.conversion_cost) },
            { label: 'WIP', render: (r) => money(r.wip_cost) },
            { label: 'Finished cost', render: (r) => money(r.finished_cost) },
          ]}
        />
      </Panel>
      <Panel
        title="Posted journal"
        subtitle="Immutable, balanced manufacturing and delivery postings"
        className="spaced-panel"
      >
        <Table
          rows={data.journal || []}
          search
          columns={[
            { label: 'Reference', key: 'reference' },
            { label: 'Description', key: 'description' },
            { label: 'Account', render: (r) => labelState(r.account) },
            { label: 'Debit', render: (r) => (num(r.debit) ? money(r.debit) : '—') },
            { label: 'Credit', render: (r) => (num(r.credit) ? money(r.credit) : '—') },
          ]}
        />
      </Panel>
    </>
  ) : (
    <Empty
      title="Financial reports require an authorized role"
      body="Ask your administrator for the appropriate access."
    />
  );
}
