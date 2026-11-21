import type { Workspace } from '../../app/workspace';
import { qtyField, selectField } from '../../components';
import { labelState } from '../../lib/format';

/** "New item" form (Products & materials). */
export function createItem({ postForm }: Workspace) {
  postForm(
    'Create product or material',
    '/items',
    [
      { name: 'sku', label: 'Unique SKU' },
      { name: 'name', label: 'Item name' },
      selectField(
        'type',
        'Item type',
        ['RAW_MATERIAL', 'FINISHED_GOOD', 'PACKAGING'].map((x) => ({
          value: x,
          label: labelState(x),
        })),
      ),
      selectField('uom', 'Base unit', [
        { value: 'kg', label: 'Kilograms (kg)' },
        { value: 'pcs', label: 'Pieces (pcs)' },
      ]),
      { ...qtyField('standardCost', 'Standard unit cost (LKR)'), min: '0' },
      { ...qtyField('reorderLevel', 'Safety stock / reorder level'), min: '0' },
    ],
    'Base units are explicit. No automatic conversion between kilograms and pieces.',
    'Create item',
  );
}
