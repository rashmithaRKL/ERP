import { Check, LoaderCircle, Truck, X } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import type { Field } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, fmt, labelState, money, num, text } from '../../lib/format';
import { ActivityFeed, dateTime, Lifecycle, QueryState } from '../inventory/shared';
import { DECIDERS, DISPOSITIONS, SEVERITIES, useDefectCodes, useQualityQuery } from './api';
import type { NcrDetail } from './api';
import { createCapa } from './CapaTab';
import { Facts, QualityBadge } from './shared';

/**
 * One nonconformance: links, affected quantity, dispositions (with concession approval and the warehouse's supplier
 * return), linked CAPAs and activity. QUALITY/ADMIN decide; WAREHOUSE posts supplier returns; everyone may read.
 */
export function NcrDrawer({
  id,
  onClose,
  onOpenRework,
  onOpenCapa,
  people,
}: {
  id: string;
  people: Row[];
  onClose: () => void;
  onOpenRework: (id: string) => void;
  onOpenCapa: (id: string) => void;
}) {
  const ws = useWorkspace();
  const { can, user, openForm } = ws;
  const detail = useQualityQuery<NcrDetail>('ncr', `/quality/ncrs/${id}`);
  const defects = useDefectCodes();
  const n = detail.data?.ncr;
  const dispositions = detail.data?.dispositions ?? [];
  const status = text(n?.status);
  const quantity = num(n?.quantity);
  const allocated = dispositions
    .filter((d) => d.status !== 'REJECTED')
    .reduce((s, d) => s + num(d.quantity), 0);
  const remaining = Math.max(Number((quantity - allocated).toFixed(6)), 0);
  const decider = can(...DECIDERS);
  const uom = text(n?.uom);

  function disposition() {
    openForm({
      title: `Disposition · ${text(n?.number)}`,
      description: `${fmt(remaining, 3)} ${uom} of lot ${text(n?.lot)} still need a disposition. Postings are immediate and auditable.`,
      submit: 'Record disposition',
      fields: [
        {
          name: 'type',
          label: 'Disposition',
          options: Object.entries(DISPOSITIONS)
            .filter(([k]) => k !== 'RETURN_TO_SUPPLIER' || n?.lot_source_type === 'PURCHASE')
            .map(([value, d]) => ({ value, label: d.label })),
          hint: Object.values(DISPOSITIONS)
            .map((d) => `${d.label}: ${d.hint}`)
            .join(' '),
        },
        {
          name: 'quantity',
          label: `Quantity (${uom})`,
          type: 'number',
          min: '0.000001',
          step: uom === 'pcs' ? '1' : '0.000001',
          defaultValue: String(remaining),
        },
        { name: 'note', label: 'Reason / MRB decision', type: 'textarea' },
        { name: 'instructions', label: 'Rework instructions', type: 'textarea', optional: true },
      ],
      run: (v, key) =>
        request(
          `/quality/ncrs/${id}/dispositions`,
          { type: v.type, quantity: v.quantity, note: v.note, instructions: v.instructions || null },
          key,
        ),
    });
  }
  function decide(d: Row, decision: 'APPROVE' | 'REJECT') {
    openForm({
      title: decision === 'APPROVE' ? 'Approve concession' : 'Reject concession',
      description: `${fmt(d.quantity, 3)} ${uom} used as is · requested by ${text(d.created_by_name)}. ${
        decision === 'APPROVE'
          ? 'The quantity is accepted; the lot is released once the whole NCR is dispositioned.'
          : 'The quantity needs another disposition.'
      }`,
      submit: decision === 'APPROVE' ? 'Approve' : 'Reject',
      fields: [{ name: 'note', label: 'Decision note', type: 'textarea' }],
      run: (v, key) => request(`/quality/dispositions/${d.id}/decision`, { decision, note: v.note }, key),
    });
  }
  function ship(d: Row) {
    openForm({
      title: 'Post supplier return',
      description: `${fmt(d.quantity, 3)} ${uom} of lot ${text(n?.lot)} go back to the supplier against ${text(d.reference)}. Stock, goods-received clearing and payables are reversed like any supplier return.`,
      submit: 'Post return',
      fields: [
        { name: 'debitNote', label: 'Debit note / RMA reference' },
        { name: 'reason', label: 'Reason', type: 'textarea', optional: true },
      ],
      run: (v, key) =>
        request(
          `/quality/dispositions/${d.id}/return`,
          { debitNote: v.debitNote, reason: v.reason || null },
          key,
        ),
    });
  }
  function edit() {
    if (!n) return;
    const fields: Field[] = [
      {
        name: 'severity',
        label: 'Severity',
        options: SEVERITIES.map((s) => ({ value: s, label: labelState(s) })),
        defaultValue: text(n.severity),
      },
      {
        name: 'defectCode',
        label: 'Defect code',
        options: (defects.data?.items ?? [])
          .filter((d) => d.active || d.code === n.defect_code)
          .map((d) => ({ value: text(d.code), label: `${text(d.code)} · ${text(d.name)}` })),
        defaultValue: text(n.defect_code),
      },
      { name: 'title', label: 'Title', defaultValue: text(n.title) },
      { name: 'description', label: 'Description', type: 'textarea', defaultValue: text(n.description) },
      {
        name: 'containment',
        label: 'Containment',
        type: 'textarea',
        optional: true,
        defaultValue: text(n.containment),
      },
    ];
    openForm({
      title: `Edit ${text(n.number)}`,
      submit: 'Save changes',
      fields,
      run: (v, key) => request(`/quality/ncrs/${id}`, { ...v, containment: v.containment || null }, key),
    });
  }
  function close() {
    openForm({
      title: `Close ${text(n?.number)}`,
      description: 'Closing confirms the dispositions are complete and verified. Linked CAPAs stay open.',
      submit: 'Close NCR',
      fields: [{ name: 'note', label: 'Closing note', type: 'textarea' }],
      run: (v, key) => request(`/quality/ncrs/${id}/close`, v, key),
    });
  }
  function cancel() {
    openForm({
      title: `Cancel ${text(n?.number)}`,
      description: n?.hold_applied
        ? 'The containment hold on the lot is lifted.'
        : 'A lot rejected by its inspection stays rejected.',
      submit: 'Cancel NCR',
      fields: [{ name: 'reason', label: 'Reason', type: 'textarea' }],
      run: (v, key) => request(`/quality/ncrs/${id}/cancel`, v, key),
    });
  }

  const steps = ['Open', 'Dispositioned', 'Closed'];
  const index = status === 'CLOSED' ? 2 : status === 'DISPOSITIONED' ? 1 : 0;
  const canDisposition = decider && status === 'OPEN' && !!n?.lot_id && remaining > 0;
  const canClose =
    decider && (status === 'DISPOSITIONED' || (status === 'OPEN' && (!n?.lot_id || quantity === 0)));
  const canCancel = decider && status === 'OPEN' && allocated === 0;
  return (
    <Overlay
      title={text(n?.number) || 'Nonconformance'}
      subtitle="Nonconformance report: affected stock, dispositions, CAPA and history"
      onClose={onClose}
      drawer
      wide
    >
      <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
        {detail.data && n && (
          <>
            <div className="drawer-body">
              <div className="detail-product">
                <div>
                  <h2>{text(n.title)}</h2>
                  <p>
                    {labelState(n.source)} · raised by {text(n.raised_by)} · {dateTime(n.created_at)}
                  </p>
                </div>
                <span className="qa-badges">
                  <QualityBadge value={n.severity} />
                  <QualityBadge value={n.status} />
                </span>
              </div>
              {status !== 'CANCELLED' ? (
                <Lifecycle steps={steps} index={index} />
              ) : (
                <p className="inv-small muted">
                  Cancelled by {text(n.cancelled_by_name)} · {text(n.cancel_reason)}
                </p>
              )}
              <div className="detail-stats">
                <div>
                  <span>Affected</span>
                  <strong>
                    {fmt(quantity, 3)} <small>{uom}</small>
                  </strong>
                </div>
                <div>
                  <span>Dispositioned</span>
                  <strong>{fmt(n.dispositioned, 3)}</strong>
                </div>
                <div>
                  <span>Awaiting decision</span>
                  <strong>{fmt(remaining + num(n.pending), 3)}</strong>
                </div>
                <div>
                  <span>Lot on hand</span>
                  <strong>{n.lot_id ? fmt(n.lot_quantity, 3) : '—'}</strong>
                </div>
              </div>
              <Facts
                items={[
                  ['Defect', `${text(n.defect_code)} · ${text(n.defect_name)}`],
                  ['Item', n.item ? `${text(n.sku)} · ${text(n.item)}` : ''],
                  [
                    'Lot',
                    n.lot_id ? (
                      <span className="qa-inline-badge">
                        {text(n.lot)} <QualityBadge value={n.lot_status} />
                        {n.hold_applied ? ' held by this NCR' : ''}
                      </span>
                    ) : (
                      ''
                    ),
                  ],
                  [
                    'Inspection',
                    n.inspection_id
                      ? `${labelState(n.inspection_type)} · ${labelState(n.inspection_status)} by ${text(n.inspected_by)}`
                      : '',
                  ],
                  ['Manufacturing order', text(n.mo_number)],
                  ['Supplier', text(n.supplier)],
                  ['Customer return', text(n.customer_return)],
                  ['Reworked by', text(n.rework_source)],
                  ['Dispositioned', n.dispositioned_at ? dateTime(n.dispositioned_at) : ''],
                  ['Closed', n.closed_at ? `${dateTime(n.closed_at)} by ${text(n.closed_by_name)}` : ''],
                ]}
              />
              <p className="inv-notes">{text(n.description)}</p>
              {text(n.containment) && (
                <p className="inv-small">
                  <strong>Containment:</strong> {text(n.containment)}
                </p>
              )}
              {text(n.close_note) && (
                <p className="inv-small">
                  <strong>Closing note:</strong> {text(n.close_note)}
                </p>
              )}
              <h3>Dispositions</h3>
              {dispositions.length ? (
                <Table
                  compact
                  pageSize={dispositions.length}
                  rows={dispositions}
                  label="Dispositions"
                  columns={[
                    { label: '#', key: 'line_no' },
                    {
                      label: 'Disposition',
                      render: (d) => (
                        <div className="cell-stack">
                          <strong>{DISPOSITIONS[text(d.type)]?.label ?? labelState(d.type)}</strong>
                          <span className="notes-cell">{text(d.note)}</span>
                        </div>
                      ),
                    },
                    { label: 'Quantity', render: (d) => `${fmt(d.quantity, 3)} ${uom}` },
                    { label: 'Value', render: (d) => (num(d.value_amount) ? money(d.value_amount) : '—') },
                    {
                      label: 'Reference',
                      render: (d) =>
                        d.rework_order_id ? (
                          <button
                            className="text-button"
                            onClick={() => onOpenRework(text(d.rework_order_id))}
                          >
                            {text(d.reference)}
                          </button>
                        ) : (
                          text(d.supplier_return || d.reference) || '—'
                        ),
                    },
                    {
                      label: 'Status',
                      render: (d) => (
                        <div className="cell-stack">
                          <QualityBadge value={d.status} />
                          <span>
                            {d.automatic
                              ? 'Automatic'
                              : `${text(d.created_by_name)}${d.decided_by_name ? ` · ${text(d.decided_by_name)}` : ''}`}
                          </span>
                        </div>
                      ),
                    },
                    {
                      label: '',
                      render: (d) =>
                        d.status === 'PENDING_APPROVAL' && status === 'OPEN' ? (
                          decider && d.created_by !== user.id ? (
                            <span className="qa-row-actions">
                              <button className="button small secondary" onClick={() => decide(d, 'APPROVE')}>
                                <Check size={13} /> Approve
                              </button>
                              <button
                                className="icon-button"
                                aria-label="Reject concession"
                                onClick={() => decide(d, 'REJECT')}
                              >
                                <X size={14} />
                              </button>
                            </span>
                          ) : (
                            <span className="muted inv-small">Needs a different approver</span>
                          )
                        ) : d.status === 'AWAITING_RETURN' && status === 'OPEN' ? (
                          can('WAREHOUSE') ? (
                            <button className="button small secondary" onClick={() => ship(d)}>
                              <Truck size={13} /> Post return
                            </button>
                          ) : (
                            <span className="muted inv-small">Awaiting warehouse</span>
                          )
                        ) : (
                          ''
                        ),
                    },
                  ]}
                />
              ) : (
                <p className="inv-small muted">
                  {n.lot_id
                    ? 'No dispositions yet: decide how the affected quantity is handled.'
                    : 'No lot is affected; close the NCR once the finding is resolved.'}
                </p>
              )}
              {detail.data.capas.length > 0 && (
                <>
                  <h3>Corrective actions</h3>
                  <ul className="qa-link-list">
                    {detail.data.capas.map((c) => (
                      <li key={c.id}>
                        <button className="text-button" onClick={() => onOpenCapa(c.id)}>
                          {text(c.number)}
                        </button>
                        <span>{text(c.title)}</span>
                        <QualityBadge value={c.status} />
                      </li>
                    ))}
                  </ul>
                </>
              )}
              <h3>Activity</h3>
              <ActivityFeed events={detail.data.activity} />
              <p className="inv-small muted">Raised {date(n.created_at)}.</p>
            </div>
            {decider && status !== 'CANCELLED' && (
              <div className="dialog-footer detail-footer">
                {canCancel && (
                  <button className="button danger-ghost" onClick={cancel}>
                    Cancel NCR
                  </button>
                )}
                {status === 'OPEN' && (
                  <button className="button secondary" onClick={edit}>
                    Edit
                  </button>
                )}
                <span className="grow" />
                {status !== 'CANCELLED' && (
                  <button className="button secondary" onClick={() => createCapa(ws, people, n)}>
                    Open CAPA
                  </button>
                )}
                {canClose && (
                  <button className="button secondary" onClick={close}>
                    Close NCR
                  </button>
                )}
                {canDisposition && (
                  <button className="button primary" onClick={disposition}>
                    {detail.isFetching && <LoaderCircle size={15} className="spin" />}
                    Add disposition
                  </button>
                )}
              </div>
            )}
          </>
        )}
      </QueryState>
    </Overlay>
  );
}
