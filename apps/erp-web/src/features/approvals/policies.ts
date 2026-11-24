import { useQuery } from '@tanstack/react-query';
import { request } from '../../lib/api';
import type { Value } from '../../lib/api';

/** Documents with a configurable approval policy (Administration → Approval policies). */
export type ApprovalDocument = 'MANUFACTURING_ORDER' | 'PURCHASE_ORDER' | 'STOCK_ADJUSTMENT';

/** One row of `GET /api/approval-policies` (readable by every signed-in user). */
export type ApprovalPolicy = {
  documentType: ApprovalDocument;
  approvalRequired: boolean;
  /** Approval is needed only above this value; null = for every document. */
  amountThreshold: Value;
  /** Roles besides ADMIN that may approve (ADMIN always may; nobody approves their own document). */
  approverRoles: string[];
};

/** Query key of the policy summaries; invalidate it after a policy is saved. */
export const APPROVAL_POLICIES = ['approval-policies'] as const;

/** The built-in approver roles, used until the tenant's policies have loaded. */
const DEFAULT_APPROVERS: Record<ApprovalDocument, string[]> = {
  MANUFACTURING_ORDER: ['PLANNER'],
  PURCHASE_ORDER: ['PLANNER'],
  STOCK_ADJUSTMENT: ['PLANNER', 'WAREHOUSE'],
};

const ROLE_NOUNS: Record<string, string> = {
  PLANNER: 'planner',
  WAREHOUSE: 'warehouse user',
  OPERATOR: 'operator',
  QUALITY: 'quality inspector',
  SALES: 'sales user',
};

/** The tenant's approval policy of one document type and its approver roles (ADMIN implied). */
export function useApprovalPolicy(type: ApprovalDocument) {
  const query = useQuery({
    queryKey: APPROVAL_POLICIES,
    queryFn: () => request<{ items: ApprovalPolicy[] }>('/approval-policies'),
    staleTime: 60_000,
  });
  const policy = query.data?.items.find((p) => p.documentType === type);
  return { policy, approverRoles: policy?.approverRoles ?? DEFAULT_APPROVERS[type] };
}

/** "an administrator, planner or warehouse user": who may approve, in words. */
export function approversText(roles: string[]) {
  const nouns = ['administrator', ...roles.map((r) => ROLE_NOUNS[r] || r.toLowerCase())];
  return `an ${nouns.length === 1 ? nouns[0] : `${nouns.slice(0, -1).join(', ')} or ${nouns[nouns.length - 1]}`}`;
}
