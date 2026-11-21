import type { Workspace } from '../../app/workspace';
import { qtyField } from '../../components';

/** "New work center" form (Work centers). */
export function createWorkCenter({ postForm }: Workspace) {
  postForm(
    'Register work center',
    '/machines',
    [
      { name: 'code', label: 'Machine identifier' },
      { name: 'name', label: 'Work-center name' },
      { name: 'kind', label: 'Process / machine type' },
      { ...qtyField('hourlyRate', 'Hourly operating rate (LKR)'), min: '0' },
    ],
    'New work centers begin in the idle state.',
    'Register work center',
  );
}
