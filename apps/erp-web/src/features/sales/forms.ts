import type { QueryClient } from '@tanstack/react-query';
import type { Workspace } from '../../app/workspace';
import { qtyField } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { fmt, num, text } from '../../lib/format';
import { invalidateSales } from './api';
import { requestNewSalesOrder } from './events';

/** "New sales order" (page heading action): opens the multi-line order editor on the Sales page. */
export function createSalesOrder(_workspace?: Workspace) {
  requestNewSalesOrder();
}

/** Cancel (draft/confirmed, nothing delivered) or close short (partially delivered), with a required reason. */
export function endSalesOrder(
  { openForm }: Workspace,
  qc: QueryClient,
  order: Row,
  kind: 'cancel' | 'close',
) {
  openForm({
    title: kind === 'cancel' ? `Cancel ${text(order.number)}` : `Close ${text(order.number)} short`,
    description:
      kind === 'cancel'
        ? 'Nothing has been delivered. Linked manufacturing orders that have not started stay open for review.'
        : 'The undelivered remainder is no longer due. Delivered quantities, invoices and returns are kept.',
    fields: [
      {
        name: 'reason',
        label: kind === 'cancel' ? 'Cancellation reason' : 'Reason for closing short',
        type: 'textarea',
      },
    ],
    submit: kind === 'cancel' ? 'Cancel order' : 'Close order short',
    run: async (values, key) => {
      const result = await request(`/sales/orders/${order.id}/${kind}`, { reason: values.reason }, key);
      await invalidateSales(qc);
      return result;
    },
  });
}

/** Customer return against one delivery: quantity (up to what is still out) and a reason. */
export function returnDelivery({ openForm }: Workspace, qc: QueryClient, delivery: Row) {
  const returnable = num(delivery.returnable);
  openForm({
    title: `Customer return · ${text(delivery.reference)}`,
    description: `${text(delivery.product)} from lot ${text(delivery.lot)}. Returned goods enter a quarantine lot and wait for a quality decision; revenue, receivable and cost of goods sold are reversed for the returned quantity.`,
    fields: [
      {
        ...qtyField('quantity', `Return quantity (${text(delivery.uom)})`, text(returnable)),
        step: delivery.uom === 'pcs' ? '1' : undefined,
        hint: `Up to ${fmt(returnable, 3)} ${text(delivery.uom)} can still be returned.`,
      },
      { name: 'reason', label: 'Reason for the return', type: 'textarea' },
    ],
    submit: 'Post customer return',
    run: async (values, key) => {
      const result = await request(
        `/sales/deliveries/${delivery.id}/returns`,
        { quantity: values.quantity, reason: values.reason },
        key,
      );
      await invalidateSales(qc);
      return result;
    },
  });
}
