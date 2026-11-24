import { useState } from 'react';
import { ArrowRight, Check, LoaderCircle, ShieldCheck } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import { codeLabel, labelState, money, num, text } from '../../lib/format';
import { approversText, useApprovalPolicy } from '../approvals/policies';
import { OPERATORS, useDetail, useInventoryCommand } from './api';
import type { AdjustmentDetail } from './api';
import { ActivityFeed, dateTime, Delta, Lifecycle, MoneyDelta, QueryState, StatusBadge } from './shared';

const KIND: Record<string, string> = { DECREASE: 'Decrease', INCREASE: 'Increase', FOUND: 'Found stock' };

/** Stock adjustment details with post / approve / cancel actions. */
export function AdjustmentDrawer({ id, onClose }: { id: string; onClose: () => void }) {
  const { user, can } = useWorkspace();
  const detail = useDetail<AdjustmentDetail>('adjustment', `/inventory/adjustments/${id}`);
  const { run, busy } = useInventoryCommand();
  // Approvers follow the STOCK_ADJUSTMENT approval policy; the creator and the submitter never approve.
  const { approverRoles } = useApprovalPolicy('STOCK_ADJUSTMENT');
  const [error, setError] = useState('');
  const d = detail.data?.adjustment;
  async function act(action: string, success: string) {
    setError('');
    try {
      await run(`/inventory/adjustments/${id}/${action}`, {}, undefined, success);
    } catch (e) {
      setError((e as Error).message);
    }
  }
  const status = text(d?.status);
  const ownDocument = !!d && (d.created_by === user.id || d.submitted_by === user.id);
  const posted = status === 'POSTED';
  return (
    <Overlay
      title={text(d?.number) || 'Stock adjustment'}
      subtitle="Adjustment lines, postings, approval and audit history"
      onClose={onClose}
      drawer
      wide
    >
      <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
        {detail.data && d && (
          <>
            <div className="drawer-body">
              <div className="detail-product">
                <div>
                  <h2>{labelState(d.reason).replace(/^./, (c) => c.toUpperCase())} adjustment</h2>
                  <p>
                    Created by {text(d.created_by_name)} · {dateTime(d.created_at)}
                    {d.count_number ? ` · from cycle count ${text(d.count_number)}` : ''}
                  </p>
                </div>
                <StatusBadge value={d.status} />
              </div>
              {error && (
                <div className="form-error" role="alert">
                  {error}
                </div>
              )}
              {status !== 'CANCELLED' && (
                <Lifecycle
                  steps={['Draft', 'Approval', 'Posted']}
                  index={status === 'POSTED' ? 2 : status === 'APPROVAL_REQUIRED' ? 1 : 0}
                />
              )}
              <div className="detail-stats">
                <div>
                  <span>Increase</span>
                  <strong className="positive">{money(d.value_increase)}</strong>
                </div>
                <div>
                  <span>Decrease</span>
                  <strong className="negative">{money(d.value_decrease)}</strong>
                </div>
                <div>
                  <span>Net</span>
                  <strong>
                    <MoneyDelta value={d.net_value} />
                  </strong>
                </div>
                <div>
                  <span>Approval threshold</span>
                  <strong>{d.approval_threshold === null ? '—' : money(d.approval_threshold)}</strong>
                </div>
              </div>
              {!posted && (
                <p className="inv-small muted">
                  {status === 'CANCELLED'
                    ? `Cancelled by ${text(d.cancelled_by_name)}; no stock or journal was changed.`
                    : 'Values are estimates until posting, which uses each lot’s current balance value.'}
                </p>
              )}
              {posted && d.approved_by_name && (
                <p className="inv-small inv-approved">
                  <ShieldCheck size={14} /> Approved by {text(d.approved_by_name)} · posted{' '}
                  {dateTime(d.posted_at)}
                </p>
              )}
              {text(d.notes) && <p className="inv-notes">{text(d.notes)}</p>}
              <h3>Lines</h3>
              <Table
                compact
                pageSize={Math.max(detail.data.lines.length, 1)}
                rows={detail.data.lines}
                columns={[
                  { label: '#', key: 'line_no' },
                  { label: 'Type', render: (r) => KIND[text(r.kind)] },
                  {
                    label: 'Lot',
                    render: (r) => (
                      <div className="cell-stack">
                        <strong>{text(r.lot) || `New lot ${text(r.lot_number) || '(generated)'}`}</strong>
                        <span>{text(r.warehouse)}</span>
                      </div>
                    ),
                  },
                  {
                    label: 'Item',
                    render: (r) => (
                      <div className="cell-stack">
                        <strong>{text(r.item)}</strong>
                        <span>{text(r.sku)}</span>
                      </div>
                    ),
                  },
                  {
                    label: 'Quantity',
                    render: (r) => (
                      <Delta value={r.kind === 'DECREASE' ? -num(r.quantity) : r.quantity} uom={r.uom} />
                    ),
                  },
                  { label: 'Unit cost', render: (r) => money(r.unit_cost) },
                  {
                    label: posted ? 'Posted value' : 'On hand now',
                    render: (r) =>
                      posted ? (
                        <MoneyDelta value={r.value_delta} />
                      ) : r.kind === 'FOUND' ? (
                        '—'
                      ) : (
                        `${num(r.on_hand).toLocaleString('en-LK')} (${num(r.reserved).toLocaleString('en-LK')} reserved)`
                      ),
                  },
                ]}
              />
              {detail.data.journal.length > 0 && (
                <>
                  <h3>Journal</h3>
                  <Table
                    compact
                    pageSize={detail.data.journal.length}
                    rows={detail.data.journal}
                    columns={[
                      { label: 'Account', render: (r) => codeLabel(r.account) },
                      { label: 'Debit', render: (r) => (num(r.debit) ? money(r.debit) : '') },
                      { label: 'Credit', render: (r) => (num(r.credit) ? money(r.credit) : '') },
                      { label: 'Entry', key: 'description' },
                    ]}
                  />
                </>
              )}
              <h3>Document activity</h3>
              <ActivityFeed events={detail.data.events} />
            </div>
            {['DRAFT', 'APPROVAL_REQUIRED'].includes(status) && (
              <div className="dialog-footer detail-footer">
                {can(...OPERATORS) && (
                  <button
                    className="button danger-ghost"
                    disabled={busy}
                    onClick={() => act('cancel', `${text(d.number)} cancelled successfully.`)}
                  >
                    {status === 'APPROVAL_REQUIRED' ? 'Reject & cancel' : 'Cancel adjustment'}
                  </button>
                )}
                <span className="grow" />
                {status === 'DRAFT' && can(...OPERATORS) && (
                  <button
                    className="button primary"
                    disabled={busy}
                    onClick={() => act('post', `${text(d.number)} submitted successfully.`)}
                  >
                    {busy && <LoaderCircle size={15} className="spin" />}
                    Post adjustment
                    <ArrowRight size={15} />
                  </button>
                )}
                {status === 'APPROVAL_REQUIRED' &&
                  (can(...approverRoles) && !ownDocument ? (
                    <button
                      className="button primary"
                      disabled={busy}
                      onClick={() => act('approve', `${text(d.number)} approved and posted successfully.`)}
                    >
                      {busy ? <LoaderCircle size={15} className="spin" /> : <Check size={15} />}
                      Approve & post
                    </button>
                  ) : (
                    <span className="muted inv-small">
                      {ownDocument
                        ? 'Awaiting approval by a different authorized person.'
                        : `Awaiting approval by ${approversText(approverRoles)}.`}
                    </span>
                  ))}
              </div>
            )}
          </>
        )}
      </QueryState>
    </Overlay>
  );
}
