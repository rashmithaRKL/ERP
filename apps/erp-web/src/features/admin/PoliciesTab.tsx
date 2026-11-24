import { useState } from 'react';
import type { FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Info, LoaderCircle, ShieldCheck } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Panel } from '../../components';
import type { Row } from '../../lib/api';
import { request } from '../../lib/api';
import { money, num, text } from '../../lib/format';
import { APPROVAL_POLICIES } from '../approvals/policies';
import { ADMIN, APPROVER_ROLES, dateTime, ROLE_LABELS, useInvalidateAdmin } from './api';
import { QueryState, Tone } from './shared';

const DOCUMENTS: Record<string, { title: string; description: string; amount: string }> = {
  MANUFACTURING_ORDER: {
    title: 'Manufacturing orders',
    description: 'Approval of a new order before materials can be reserved.',
    amount: 'estimated order cost',
  },
  PURCHASE_ORDER: {
    title: 'Purchase orders',
    description: 'Approval of a submitted purchase before goods can be received.',
    amount: 'order value',
  },
  STOCK_ADJUSTMENT: {
    title: 'Stock adjustments',
    description: 'A second person posts adjustments and count variances above the threshold.',
    amount: 'gross adjustment value',
  },
};

type Policy = Row & { approverRoles: string[] };

/** Approval policy per document type: required, threshold and approver roles. ADMIN only. */
export function PoliciesTab() {
  const policies = useQuery({
    queryKey: [ADMIN, 'policies'],
    queryFn: () => request<{ items: Policy[] }>('/admin/approval-policies'),
  });
  return (
    <div className="admin-section">
      <QueryState pending={policies.isPending} error={policies.error} retry={() => policies.refetch()}>
        <div className="adm-grid">
          {policies.data?.items.map((p) => (
            <PolicyCard key={`${p.documentType}-${text(p.updatedAt)}`} policy={p} />
          ))}
        </div>
      </QueryState>
      <div className="adm-note" style={{ marginTop: 20 }}>
        <Info size={16} />
        <span>
          <strong>Nobody approves their own document</strong> — not even an administrator. Administrators can
          always approve documents created by someone else. A blank threshold means every document needs a
          second person.
        </span>
      </div>
    </div>
  );
}

function PolicyCard({ policy }: { policy: Policy }) {
  const { notify } = useWorkspace();
  const invalidate = useInvalidateAdmin();
  const qc = useQueryClient();
  const doc = DOCUMENTS[text(policy.documentType)];
  const adjustments = policy.documentType === 'STOCK_ADJUSTMENT';
  const [required, setRequired] = useState(!!policy.approvalRequired);
  const [threshold, setThreshold] = useState(
    policy.amountThreshold === null || policy.amountThreshold === undefined
      ? ''
      : String(num(policy.amountThreshold)),
  );
  const [roles, setRoles] = useState<string[]>(policy.approverRoles);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [key, setKey] = useState(() => crypto.randomUUID());

  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await request(
        `/admin/approval-policies/${text(policy.documentType)}`,
        {
          approvalRequired: required,
          amountThreshold: threshold.trim() === '' ? null : threshold.trim(),
          approverRoles: roles,
        },
        key,
      );
      setKey(crypto.randomUUID());
      notify(`${doc.title} approval policy saved successfully.`);
      await Promise.all([
        invalidate(),
        qc.invalidateQueries({ queryKey: APPROVAL_POLICIES }),
        qc.invalidateQueries({ queryKey: ['inventory', 'settings'] }),
      ]);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const toggle = (role: string) =>
    setRoles((current) => (current.includes(role) ? current.filter((r) => r !== role) : [...current, role]));

  return (
    <Panel
      title={doc.title}
      subtitle={doc.description}
      className="adm-policy"
      action={<Tone tone="green">In effect</Tone>}
    >
      <form className="adm-policy-body" onSubmit={save}>
        <label className="adm-switch">
          <input type="checkbox" checked={required} onChange={(e) => setRequired(e.target.checked)} />
          <span>
            Require a second person’s approval
            <small>
              {required
                ? 'Another authorized person must approve.'
                : 'Documents are auto-approved by policy when they are ' +
                  (adjustments ? 'posted.' : doc === DOCUMENTS.PURCHASE_ORDER ? 'submitted.' : 'created.')}
            </small>
          </span>
        </label>
        <label className="field">
          <span>
            Only when the {doc.amount} exceeds (LKR)
            <small>Optional</small>
          </span>
          <input
            type="number"
            min="0"
            step="0.01"
            inputMode="decimal"
            placeholder="Every document"
            value={threshold}
            disabled={!required}
            onChange={(e) => setThreshold(e.target.value)}
          />
          <em>
            {!required
              ? 'Not used while approval is off.'
              : threshold.trim() === ''
                ? 'Every document needs approval.'
                : `Documents above ${money(threshold)} need approval; others are auto-approved by policy.`}
            {adjustments && ' The Inventory page’s approval chip edits this same threshold.'}
          </em>
        </label>
        <div>
          <span className="adm-label">Who may approve</span>
          <div className="adm-roles">
            <label className="adm-role on locked" title="Administrators can always approve">
              <input type="checkbox" checked readOnly disabled />
              {ROLE_LABELS.ADMIN}
            </label>
            {APPROVER_ROLES.map((r) => (
              <label key={r} className={`adm-role ${roles.includes(r) ? 'on' : ''}`}>
                <input type="checkbox" checked={roles.includes(r)} onChange={() => toggle(r)} />
                {ROLE_LABELS[r]}
              </label>
            ))}
          </div>
        </div>
        {error && (
          <div className="form-error" role="alert">
            {error}
          </div>
        )}
        <div className="adm-policy-foot">
          <span className="adm-muted">
            {policy.customized ? (
              <>
                <ShieldCheck size={11} /> Updated {dateTime(policy.updatedAt)}
                {policy.updatedBy ? ` by ${text(policy.updatedBy)}` : ''}
              </>
            ) : (
              'Built-in default'
            )}
          </span>
          <button className="button primary small" disabled={busy}>
            {busy && <LoaderCircle className="spin" size={13} />}
            Save policy
          </button>
        </div>
      </form>
    </Panel>
  );
}
