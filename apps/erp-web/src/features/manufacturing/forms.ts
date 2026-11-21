import type { Workspace } from '../../app/workspace';
import { dateField, option, qtyField, selectField } from '../../components';
import type { Row } from '../../lib/api';
import { fmt, labelState, num, text } from '../../lib/format';

/** "New manufacturing order" form, optionally planned from a sales order (Overview, MOs, Shop floor, Sales). */
export function createOrder({ data, postForm }: Workspace, sale?: Row) {
  if (!data) return;
  postForm(
    'New manufacturing order',
    '/manufacturing',
    [
      selectField(
        'bomId',
        'Product & approved BOM',
        data.boms
          .filter((b) => b.status === 'APPROVED')
          .map((b) => ({ value: b.id, label: `${b.product} · ${b.revision}` })),
        sale ? data.boms.find((b) => b.product_id === sale.product_id)?.id : undefined,
      ),
      qtyField(
        'quantity',
        'Planned quantity (pieces)',
        sale ? text(num(sale.quantity) - num(sale.delivered)) : undefined,
      ),
      selectField(
        'machineId',
        'Work center',
        option(
          data.machines.filter((m) => m.status !== 'MAINTENANCE'),
          'name',
        ),
      ),
      selectField(
        'priority',
        'Priority',
        ['NORMAL', 'HIGH', 'URGENT'].map((x) => ({ value: x, label: labelState(x) })),
        'NORMAL',
      ),
      {
        ...selectField(
          'salesOrderId',
          'Customer order',
          data.sales
            .filter((s) => s.status !== 'DELIVERED')
            .map((s) => ({ value: s.id, label: `${s.number} · ${s.customer}` })),
          sale?.id,
        ),
        optional: true,
      },
      dateField,
    ],
    'Material requirements are snapshotted from this BOM revision. An independent approval is required.',
    'Create order',
  );
}

/** "Record production output" form (Shop floor, order drawer). */
export function recordOutput({ postForm }: Workspace, order: Row) {
  postForm(
    'Record production output',
    `/manufacturing/${order.id}/complete`,
    [
      { ...qtyField('goodQty', 'Good quantity (pieces)'), min: '0' },
      { ...qtyField('scrapQty', 'Scrap quantity (pieces)', '0'), min: '0' },
      { ...qtyField('labourCost', 'Additional labour cost (LKR)', '0'), min: '0' },
      { ...qtyField('machineCost', 'Additional machine cost (LKR)', '0'), min: '0' },
      {
        name: 'notes',
        label: 'Production notes / scrap reason',
        type: 'textarea',
        defaultValue: '',
        optional: true,
      },
    ],
    `${order.number} · ${fmt(num(order.quantity) - num(order.good_qty) - num(order.scrap_qty))} pieces remaining. Output is held for quality review.`,
    'Post output',
  );
}
