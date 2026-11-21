import type { Workspace } from '../../app/workspace';
import { dateField, option, qtyField, selectField } from '../../components';

/** "New sales order" form (Sales orders). */
export function createSalesOrder({ data, postForm }: Workspace) {
  return (
    data &&
    postForm(
      'New customer order',
      '/sales',
      [
        selectField(
          'customerId',
          'Customer',
          option(
            data.partners.filter((p) => p.type === 'CUSTOMER'),
            'name',
          ),
        ),
        selectField(
          'productId',
          'Product',
          option(
            data.items.filter((i) => i.type === 'FINISHED_GOOD'),
            'name',
          ),
        ),
        qtyField('quantity', 'Order quantity (pieces)'),
        qtyField('unitPrice', 'Unit price (LKR)'),
        dateField,
      ],
      'Confirm customer demand before planning production.',
      'Confirm order',
    )
  );
}
