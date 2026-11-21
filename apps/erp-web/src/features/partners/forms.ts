import type { Workspace } from '../../app/workspace';
import { selectField } from '../../components';
import { labelState } from '../../lib/format';

/** "Add customer" / "Add supplier" form (Sales orders, Purchasing). */
export function createPartner({ postForm }: Workspace, kind: 'CUSTOMER' | 'SUPPLIER') {
  postForm(
    kind === 'CUSTOMER' ? 'Add customer' : 'Add supplier',
    '/partners',
    [
      { name: 'name', label: 'Company name' },
      selectField('type', 'Partner type', [{ value: kind, label: labelState(kind) }], kind),
      { name: 'email', label: 'Contact email', type: 'email', optional: true },
      {
        name: 'paymentDays',
        label: 'Payment terms (days)',
        type: 'number',
        min: '0',
        step: '1',
        defaultValue: '30',
      },
    ],
    'Create a business partner in your organization.',
    'Add partner',
  );
}
