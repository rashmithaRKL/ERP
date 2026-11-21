import { useStateData } from '../app/workspace';
import { Badge, Panel, Table } from '../components';
import { ProductMark } from '../features/products/ProductMark';
import { fmt, labelState, money, text } from '../lib/format';

/** Route `/products`: product & material master. */
export default function ProductsPage() {
  const data = useStateData();
  return (
    <Panel
      title="Product & material master"
      subtitle="A single source for base units, standard costs and stock availability"
    >
      <Table
        rows={data.items}
        search
        columns={[
          {
            label: 'Item',
            render: (r) => (
              <div className="product-cell">
                <ProductMark kind={r.name} />
                <div className="cell-stack">
                  <strong>{text(r.name)}</strong>
                  <span>{text(r.sku)}</span>
                </div>
              </div>
            ),
          },
          {
            label: 'Type',
            render: (r) => <span className="capitalize">{labelState(r.type)}</span>,
          },
          { label: 'Base unit', key: 'uom' },
          { label: 'Available', render: (r) => `${fmt(r.available, 3)} ${r.uom}` },
          { label: 'Safety stock', render: (r) => fmt(r.reorder_level, 3) },
          { label: 'Standard cost', render: (r) => money(r.standard_cost) },
          {
            label: 'State',
            render: (r) => <Badge value={r.active ? 'ACTIVE' : 'INACTIVE'} />,
          },
        ]}
      />
    </Panel>
  );
}
