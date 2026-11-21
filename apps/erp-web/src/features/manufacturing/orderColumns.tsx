import { useWorkspace } from '../../app/workspace';
import { Badge, Progress } from '../../components';
import type { Column } from '../../components';
import { date, fmt, num, text, today } from '../../lib/format';
import { isActive } from '../../lib/rows';
import { ProductMark } from '../products/ProductMark';

/** Manufacturing order table columns (Overview, Manufacturing orders). The order number opens the drawer. */
export function useOrderColumns(): Column[] {
  const { openOrder } = useWorkspace();
  return [
    {
      label: 'Manufacturing order',
      key: 'number',
      render: (r) => (
        <div className="cell-stack">
          <button
            className="text-button order-number"
            onClick={(e) => {
              e.stopPropagation();
              openOrder(r.id);
            }}
          >
            {text(r.number)}
          </button>
          <span>{text(r.customer) || 'Make to stock'}</span>
        </div>
      ),
    },
    {
      label: 'Product',
      render: (r) => (
        <div className="product-cell">
          <ProductMark kind={r.product} small />
          <div className="cell-stack">
            <strong>{text(r.product)}</strong>
            <span>
              {text(r.sku)} · {text(r.machine)}
            </span>
          </div>
        </div>
      ),
    },
    {
      label: 'Progress',
      render: (r) => (
        <div className="table-progress">
          <span>
            {fmt(r.good_qty)} <em>/ {fmt(r.quantity)}</em>
          </span>
          <Progress value={((num(r.good_qty) + num(r.scrap_qty)) / num(r.quantity)) * 100} />
        </div>
      ),
    },
    { label: 'Status', key: 'status', render: (r) => <Badge value={r.status} /> },
    {
      label: 'Due date',
      key: 'due_date',
      render: (r) => (
        <span className={text(r.due_date) < today() && isActive(r) ? 'overdue' : ''}>{date(r.due_date)}</span>
      ),
    },
  ];
}
