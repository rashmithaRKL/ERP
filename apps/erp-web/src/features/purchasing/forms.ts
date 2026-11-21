import type { Workspace } from '../../app/workspace';
import { dateField, option, qtyField, selectField } from '../../components';
import type { Row } from '../../lib/api';
import { text } from '../../lib/format';

/** "New purchase order" form, optionally prefilled from an MRP shortage line (Purchasing, Planning). */
export function createPurchase({ data, postForm }: Workspace, material?: Row) {
  if (!data) return;
  postForm(
    'New purchase order',
    '/purchases',
    [
      selectField(
        'supplierId',
        'Supplier',
        option(
          data.partners.filter((p) => p.type === 'SUPPLIER'),
          'name',
        ),
      ),
      selectField(
        'itemId',
        'Material',
        option(
          data.items.filter((i) => i.type !== 'FINISHED_GOOD'),
          'name',
        ),
        material?.id,
      ),
      qtyField('quantity', 'Purchase quantity', material ? text(material.shortage) : undefined),
      qtyField('unitCost', 'Unit cost (LKR)'),
      dateField,
    ],
    'Quantities use the material’s base unit. Purchases require a second person’s approval.',
    'Create purchase',
  );
}
