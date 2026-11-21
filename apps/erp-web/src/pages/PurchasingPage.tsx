import { Plus } from 'lucide-react';
import { useStateData, useWorkspace } from '../app/workspace';
import { Badge, option, Panel, qtyField, selectField, Table } from '../components';
import { createPartner } from '../features/partners/forms';
import { date, fmt, money, num, text } from '../lib/format';

/** Route `/purchasing`: purchase orders, goods receipts / invoice matching and suppliers. */
export default function PurchasingPage() {
  const workspace = useWorkspace();
  const { busy, can, mutate, postForm } = workspace;
  const data = useStateData();
  return (
    <>
      <Panel title="Purchase orders" subtitle="Approved procurement, partial deliveries and incoming quality">
        <Table
          rows={data.purchases}
          search
          columns={[
            {
              label: 'Purchase order',
              key: 'number',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.number)}</strong>
                  <span>{text(r.supplier)}</span>
                </div>
              ),
            },
            { label: 'Material', key: 'item' },
            {
              label: 'Received / ordered',
              render: (r) => `${fmt(r.received)} / ${fmt(r.quantity)} ${r.uom}`,
            },
            { label: 'Unit price', render: (r) => money(r.unit_cost) },
            { label: 'Due', render: (r) => date(r.due_date) },
            { label: 'Status', render: (r) => <Badge value={r.status} /> },
            {
              label: 'Action',
              render: (r) =>
                r.status === 'DRAFT' && can('PLANNER') ? (
                  <button
                    className="button small secondary"
                    disabled={busy}
                    onClick={() => mutate(`/purchases/${r.id}/approve`)}
                  >
                    Approve
                  </button>
                ) : ['APPROVED', 'PARTIAL'].includes(text(r.status)) && can('WAREHOUSE') ? (
                  <button
                    className="button small secondary"
                    onClick={() =>
                      postForm(
                        'Receive purchase delivery',
                        `/purchases/${r.id}/receive`,
                        [
                          qtyField(
                            'quantity',
                            `Quantity (${r.uom})`,
                            text(num(r.quantity) - num(r.received)),
                          ),
                          selectField(
                            'warehouseId',
                            'Raw material warehouse',
                            option(
                              data.warehouses.filter((w) => w.kind === 'RAW'),
                              'name',
                            ),
                            data.warehouses.find((w) => w.kind === 'RAW')?.id,
                          ),
                          { name: 'supplierLot', label: 'Unique supplier lot reference' },
                        ],
                        `${r.number} · Received materials enter quality quarantine.`,
                        'Receive into quarantine',
                      )
                    }
                  >
                    Receive goods
                  </button>
                ) : (
                  <span className="muted">—</span>
                ),
            },
          ]}
        />
      </Panel>
      <Panel
        title="Goods receipts & invoice matching"
        subtitle="Invoices use the accepted receipt quantity and approved purchase price."
        className="spaced-panel"
      >
        <Table
          rows={data.receipts}
          columns={[
            { label: 'Purchase order', key: 'purchase_order' },
            { label: 'Lot', key: 'lot' },
            { label: 'Received', render: (r) => fmt(r.quantity, 3) },
            { label: 'Quality status', render: (r) => <Badge value={r.status} /> },
            {
              label: 'Supplier invoice',
              render: (r) =>
                r.invoice_reference ? (
                  text(r.invoice_reference)
                ) : r.status === 'RELEASED' && can() ? (
                  <button
                    className="button small secondary"
                    onClick={() =>
                      postForm(
                        'Match supplier invoice',
                        `/receipts/${r.id}/invoice`,
                        [{ name: 'reference', label: 'Supplier invoice reference' }],
                        'This posts the receipt value from goods-received clearing to accounts payable.',
                        'Match & post invoice',
                      )
                    }
                  >
                    Match invoice
                  </button>
                ) : (
                  <span className="muted">Awaiting quality release</span>
                ),
            },
          ]}
        />
      </Panel>
      <Panel
        title="Supplier directory"
        className="spaced-panel"
        action={
          can('PLANNER', 'WAREHOUSE') && (
            <button className="button secondary small" onClick={() => createPartner(workspace, 'SUPPLIER')}>
              <Plus size={14} />
              Add supplier
            </button>
          )
        }
      >
        <Table
          rows={data.partners.filter((p) => p.type === 'SUPPLIER')}
          columns={[
            { label: 'Supplier', key: 'name' },
            { label: 'Email', key: 'email' },
            { label: 'Payment terms', render: (r) => `${r.payment_days} days` },
          ]}
        />
      </Panel>
    </>
  );
}
