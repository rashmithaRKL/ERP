import { Plus, Truck } from 'lucide-react';
import { useStateData, useWorkspace } from '../app/workspace';
import { Badge, Panel, Progress, qtyField, selectField, Table } from '../components';
import { createOrder } from '../features/manufacturing/forms';
import { createPartner } from '../features/partners/forms';
import type { Row } from '../lib/api';
import { date, fmt, num, text } from '../lib/format';

/** Route `/sales`: customer orders and the customer directory. */
export default function SalesPage() {
  const workspace = useWorkspace();
  const { can, postForm } = workspace;
  const data = useStateData();
  function dispatch(sale: Row) {
    if (!data) return;
    postForm(
      'Dispatch finished goods',
      `/sales/${sale.id}/dispatch`,
      [
        selectField(
          'lotId',
          'Released finished lot',
          data.lots
            .filter(
              (l) =>
                l.item_id === sale.product_id &&
                l.status === 'RELEASED' &&
                num(l.quantity) - num(l.reserved) > 0,
            )
            .map((l) => ({
              value: l.id,
              label: `${l.number} · ${fmt(num(l.quantity) - num(l.reserved))} available`,
            })),
        ),
        qtyField('quantity', 'Dispatch quantity (pieces)'),
        { name: 'reference', label: 'Delivery note reference' },
      ],
      `${sale.customer} · ${fmt(num(sale.quantity) - num(sale.delivered))} pieces outstanding. Quality-held lots are excluded.`,
      'Post dispatch & invoice',
    );
  }
  return (
    <>
      <Panel title="Customer orders" subtitle="Confirmed demand, production links and delivery progress">
        <Table
          rows={data.sales}
          search
          columns={[
            {
              label: 'Order',
              key: 'number',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.number)}</strong>
                  <span>{text(r.customer)}</span>
                </div>
              ),
            },
            { label: 'Product', key: 'product' },
            {
              label: 'Fulfilment',
              render: (r) => (
                <div className="table-progress">
                  <span>
                    {fmt(r.delivered)} / {fmt(r.quantity)} pcs
                  </span>
                  <Progress value={(num(r.delivered) / num(r.quantity)) * 100} />
                </div>
              ),
            },
            { label: 'Due', key: 'due_date', render: (r) => date(r.due_date) },
            { label: 'Status', render: (r) => <Badge value={r.status} /> },
            {
              label: 'Actions',
              render: (r) => (
                <div className="row-actions">
                  {can('PLANNER') && r.status !== 'DELIVERED' && (
                    <button className="button small secondary" onClick={() => createOrder(workspace, r)}>
                      Plan
                    </button>
                  )}
                  {can('WAREHOUSE') && r.status !== 'DELIVERED' && (
                    <button className="button small secondary" onClick={() => dispatch(r)}>
                      <Truck size={13} />
                      Dispatch
                    </button>
                  )}
                </div>
              ),
            },
          ]}
        />
      </Panel>
      <Panel
        title="Customer directory"
        className="spaced-panel"
        action={
          can('PLANNER', 'SALES') && (
            <button className="button secondary small" onClick={() => createPartner(workspace, 'CUSTOMER')}>
              <Plus size={14} />
              Add customer
            </button>
          )
        }
      >
        <Table
          rows={data.partners.filter((p) => p.type === 'CUSTOMER')}
          columns={[
            { label: 'Customer', key: 'name' },
            { label: 'Email', key: 'email' },
            { label: 'Payment terms', render: (r) => `${r.payment_days} days` },
          ]}
        />
      </Panel>
    </>
  );
}
