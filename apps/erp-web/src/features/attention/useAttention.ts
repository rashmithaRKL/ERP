import { findRoute } from '../../app/routes';
import { useWorkspace } from '../../app/workspace';
import { today } from '../../lib/format';
import { useApprovalPolicy } from '../approvals/policies';
import { buildAttention } from './attention';
import type { Attention } from './attention';

/** True when the role sees the page behind `path` in its navigation (pages without `roles` are for everyone). */
export function roleCanOpen(role: string, path: string) {
  const route = findRoute(path);
  return !route?.roles || route.roles.includes(role);
}

/** The signed-in user's attention model (overview panel, attention center, header bell). */
export function useAttention(): Attention {
  const { data, user } = useWorkspace();
  const { approverRoles } = useApprovalPolicy('PURCHASE_ORDER');
  if (!data) return { groups: [], total: 0, caughtUp: true };
  return buildAttention({
    data,
    user,
    today: today(),
    purchaseApprovers: approverRoles,
    canOpen: (path) => roleCanOpen(user.role, path),
  });
}
