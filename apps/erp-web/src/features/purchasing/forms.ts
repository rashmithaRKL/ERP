import type { QueryClient } from '@tanstack/react-query';
import type { Workspace } from '../../app/workspace';
import { dateField, option, qtyField, selectField } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { fmt, num, text } from '../../lib/format';
import { invalidatePurchasing } from './api';
import { requestNewPurchaseOrder } from './events';

/**
 * "New purchase order" (page heading) and "Create purchase" (Planning, one MRP shortage row). On the Purchasing page
 * it opens the multi-line editor, prefilled from the shortage. Elsewhere it opens a quick single-line form in the
 * material's base unit that creates the order and submits it for a second person's approval (`POST /api/purchases`).
 */
export function createPurchase(workspace: Workspace, material?: Row) {
  if (requestNewPurchaseOrder(material)) return;
  const { data, postForm } = workspace;
  if (!data) return;
  const item = material ? data.items.find((i) => i.id === material.id) : undefined;
  const shortage = material ? Math.ceil(num(material.shortage) * 1000) / 1000 : undefined;
  postForm(
    'New purchase order',
    '/purchases',
    [
      selectField(
        'supplierId',
        'Supplier',
        option(
          data.partners.filter((p) => p.type === 'SUPPLIER' && p.active !== false),
          'name',
        ),
      ),
      selectField(
        'itemId',
        'Material',
        option(
          data.items.filter((i) => i.type !== 'FINISHED_GOOD' && i.active !== false),
          'name',
        ),
        material?.id,
      ),
      {
        ...qtyField(
          'quantity',
          `Purchase quantity${material ? ` (${text(material.uom)})` : ''}`,
          shortage ? String(shortage) : undefined,
        ),
        step: material?.uom === 'pcs' ? '1' : undefined,
      },
      qtyField('unitCost', 'Unit cost per base unit (LKR)', item ? text(num(item.standard_cost)) : undefined),
      dateField,
    ],
    'One line in the material’s base unit, submitted for a second person’s approval. Use Purchasing for several lines or purchase units such as bags and boxes.',
    'Create and submit',
  );
}

/** Cancel (nothing received) or close short (partially received), with a required reason. */
export function endPurchaseOrder(
  { openForm }: Workspace,
  qc: QueryClient,
  order: Row,
  kind: 'cancel' | 'close',
) {
  openForm({
    title: kind === 'cancel' ? `Cancel ${text(order.number)}` : `Close ${text(order.number)} short`,
    description:
      kind === 'cancel'
        ? 'Nothing has been received. The order leaves material planning supply and cannot be reopened.'
        : 'The remaining quantity is no longer expected and leaves material planning supply. Receipts, invoices and returns are kept.',
    fields: [
      {
        name: 'reason',
        label: kind === 'cancel' ? 'Cancellation reason' : 'Reason for closing short',
        type: 'textarea',
      },
    ],
    submit: kind === 'cancel' ? 'Cancel order' : 'Close order short',
    run: async (values, key) => {
      const result = await request(`/purchases/orders/${order.id}/${kind}`, { reason: values.reason }, key);
      await invalidatePurchasing(qc);
      return result;
    },
  });
}

/** Return released stock of one receipt to the supplier against a debit note. */
export function returnToSupplier({ openForm }: Workspace, qc: QueryClient, receipt: Row) {
  const returnable = num(receipt.returnable);
  openForm({
    title: `Return to supplier · lot ${text(receipt.lot)}`,
    description: `${text(receipt.item)} from ${text(receipt.number)}. The stock leaves the lot at its current value; the part not yet invoiced reverses goods-received clearing, the invoiced part is settled against payables by your debit note.`,
    fields: [
      {
        ...qtyField('quantity', `Return quantity (${text(receipt.uom)})`, text(returnable)),
        step: receipt.uom === 'pcs' ? '1' : undefined,
        hint: `Up to ${fmt(returnable, 3)} ${text(receipt.uom)} is released, unreserved and not yet returned.`,
      },
      { name: 'debitNote', label: 'Debit note reference', defaultValue: '' },
      { name: 'reason', label: 'Reason for the return', type: 'textarea' },
    ],
    submit: 'Post supplier return',
    run: async (values, key) => {
      const result = await request(
        `/purchases/receipts/${receipt.id}/returns`,
        { quantity: values.quantity, reason: values.reason, debitNote: values.debitNote },
        key,
      );
      await invalidatePurchasing(qc);
      return result;
    },
  });
}
