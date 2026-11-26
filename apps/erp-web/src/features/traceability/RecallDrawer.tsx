import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Download, GitBranch, LoaderCircle, Megaphone, RefreshCw, ShieldCheck } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, fmt, labelState, num, text, today } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { dateTime, Lifecycle, QueryState } from '../inventory/shared';
import { ActionError, Facts } from '../quality/shared';
import {
  CONTAINMENT,
  downloadNotifications,
  RECALL_ROLES,
  RELATIONS,
  useInvalidateRecalls,
  useRecall,
  useRecallCommand,
} from './api';
import type { TraceRoot } from './api';
import { TraceBadge } from './shared';

/**
 * One recall: scope, suspects, the affected lots (snapshot and live status, containment and NCR), customers with
 * notification tracking, shipments; contain, notify, export the notification list, close (with the override rule) or
 * cancel. Everyone may read; QUALITY/ADMIN act.
 */
export function RecallDrawer({
  id,
  onClose,
  onTrace,
}: {
  id: string;
  onClose: () => void;
  onTrace: (root: TraceRoot) => void;
}) {
  const { can, user, openForm, notify } = useWorkspace();
  const navigate = useNavigate();
  const detail = useRecall(id);
  const invalidate = useInvalidateRecalls();
  const { run, busy } = useRecallCommand();
  const [section, setSection] = useState('lots');
  const [inStockOnly, setInStockOnly] = useState(true);
  const [error, setError] = useState('');
  const [exporting, setExporting] = useState(false);
  const r = detail.data;
  const status = text(r?.status);
  const actor = can(...RECALL_ROLES);
  const check = r?.closeCheck;
  const outstanding = !!check && (num(check.pendingCustomers) > 0 || num(check.unsettledLots) > 0);

  async function command(url: string, success: string) {
    setError('');
    try {
      await run(url, {}, undefined, success);
    } catch (err) {
      setError((err as Error).message);
    }
  }
  function contain() {
    openForm({
      title: `Contain ${text(r?.number)}`,
      description: `The affected set is recalculated now, then every released lot still in stock is put on hold by a RECALL nonconformance (${text(r?.defect_code)}, ${labelState(r?.severity)}). Lots already on hold, under an open NCR or awaiting inspection are listed but not touched. The affected-item record is frozen afterwards.`,
      submit: 'Contain recall',
      fields: [],
      run: async (_v, key) => {
        const out = await request(`/recalls/${id}/contain`, {}, key);
        await invalidate();
        return out;
      },
    });
  }
  function notifyCustomer(c: Row) {
    openForm({
      title: `Notified ${text(c.customer)}`,
      description: `Record how ${text(c.customer)} was told about ${text(r?.number)}. A notification is recorded once and cannot be changed afterwards.`,
      submit: 'Record notification',
      fields: [
        { name: 'notifiedOn', label: 'Notified on', type: 'date', defaultValue: today() },
        {
          name: 'contact',
          label: 'Contact',
          hint: 'Who was told and how (name, phone or email)',
          defaultValue: [text(c.email), text(c.phone)].filter(Boolean).join(' · '),
        },
        { name: 'notes', label: 'Notes', type: 'textarea', optional: true },
      ],
      run: async (v, key) => {
        const out = await request(
          `/recalls/${id}/customers/${encodeURIComponent(text(c.customer_id))}/notify`,
          { notifiedOn: v.notifiedOn, contact: v.contact, notes: v.notes || null },
          key,
        );
        await invalidate();
        return out;
      },
    });
  }
  function close() {
    const fields = [
      { name: 'note', label: 'Closing note', type: 'textarea' as const, optional: !outstanding },
      ...(outstanding
        ? [
            {
              name: 'overrideReason',
              label: 'Override reason',
              type: 'textarea' as const,
              hint: 'Why the recall may close with customers not notified or held lots without a disposition',
            },
          ]
        : []),
    ];
    openForm({
      title: `Close ${text(r?.number)}`,
      description: outstanding
        ? `${fmt(check?.pendingCustomers)} customer(s) are not notified and ${fmt(check?.unsettledLots)} held lot(s) have no disposition yet. Closing now is an override: it is recorded with your reason and must come from a quality user other than ${text(r?.created_by_name)}, who opened the recall.`
        : 'Every customer is notified and every held lot is dispositioned. The recall record becomes read-only.',
      submit: outstanding ? 'Close with override' : 'Close recall',
      fields,
      run: async (v, key) => {
        const out = await request(
          `/recalls/${id}/close`,
          { note: v.note || null, overrideReason: v.overrideReason || null },
          key,
        );
        await invalidate();
        return out;
      },
    });
  }
  function cancel() {
    openForm({
      title: `Cancel ${text(r?.number)}`,
      description: 'Only an open recall can be cancelled: nothing has been held yet.',
      submit: 'Cancel recall',
      fields: [{ name: 'reason', label: 'Reason', type: 'textarea' }],
      run: async (v, key) => {
        const out = await request(`/recalls/${id}/cancel`, { reason: v.reason }, key);
        await invalidate();
        return out;
      },
    });
  }
  async function exportCsv() {
    setExporting(true);
    setError('');
    try {
      await downloadNotifications(id, text(r?.number));
      notify('Notification list downloaded.');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setExporting(false);
    }
  }

  const steps = ['Open', 'Contained', 'Closed'];
  const index = status === 'CLOSED' ? 2 : status === 'CONTAINED' ? 1 : 0;
  const lots = (r?.lots ?? []).filter((l) => !inStockOnly || num(l.quantity) > 0);
  const customers = r?.customers ?? [];
  const shipments = r?.shipments ?? [];
  return (
    <Overlay
      title={text(r?.number) || 'Recall'}
      subtitle="Recall: affected stock and shipments, containment, customer notification"
      onClose={onClose}
      drawer
      wide
    >
      <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
        {r && (
          <>
            <div className="drawer-body">
              <div className="detail-product">
                <div>
                  <h2>{text(r.title)}</h2>
                  <p>
                    Opened by {text(r.created_by_name)} · {dateTime(r.created_at)}
                  </p>
                </div>
                <span className="qa-badges">
                  <TraceBadge value={r.severity} />
                  <TraceBadge value={r.status} />
                </span>
              </div>
              {status !== 'CANCELLED' ? (
                <Lifecycle steps={steps} index={index} />
              ) : (
                <p className="inv-small muted">
                  Cancelled by {text(r.cancelled_by_name)} · {text(r.cancel_reason)}
                </p>
              )}
              <div className="detail-stats">
                <div>
                  <span>Affected lots</span>
                  <strong>{fmt(r.affected_lots)}</strong>
                </div>
                <div>
                  <span>In stock</span>
                  <strong>{fmt(r.in_stock_lots)}</strong>
                </div>
                <div>
                  <span>Held by recall</span>
                  <strong>{status === 'OPEN' ? '—' : fmt(r.held_lots)}</strong>
                </div>
                <div>
                  <span>Customers to notify</span>
                  <strong className={num(check?.pendingCustomers) ? 'inv-attention' : undefined}>
                    {fmt(check?.pendingCustomers)} <small>of {fmt(r.customers)}</small>
                  </strong>
                </div>
              </div>
              {status === 'OPEN' && (
                <div className="inv-warning">
                  Nothing is on hold yet. Check the affected lots and customers below, then contain the
                  recall.
                </div>
              )}
              {r.truncated === true && (
                <div className="inv-warning">
                  The genealogy was cut at its size limit: some downstream lots may be missing. Trace the
                  suspect lots to review.
                </div>
              )}
              {num(check?.releasedSinceContainment) > 0 && (
                <div className="inv-warning">
                  {fmt(check?.releasedSinceContainment)} lot(s) that were already on hold at containment have
                  been released since. Review them in the affected lots.
                </div>
              )}
              <Facts
                items={[
                  [
                    'Suspect material',
                    r.basis === 'SUPPLIER'
                      ? `${text(r.supplier)} receipts ${date(r.received_from)} – ${date(r.received_to)}${r.item ? ` · ${text(r.item_sku)} ${text(r.item)}` : ''}`
                      : `${fmt(r.suspects.length)} chosen lot(s)`,
                  ],
                  ['Scope', r.scope === 'FORWARD' ? 'Forward' : 'Forward and siblings'],
                  ['Defect', `${text(r.defect_code)} · ${text(r.defect_name)}`],
                  ['Affected set computed', dateTime(r.computed_at)],
                  [
                    'Contained',
                    r.contained_at ? `${dateTime(r.contained_at)} by ${text(r.contained_by_name)}` : '',
                  ],
                  ['Closed', r.closed_at ? `${dateTime(r.closed_at)} by ${text(r.closed_by_name)}` : ''],
                  ['Override', text(r.override_reason)],
                ]}
              />
              <p className="inv-notes">{text(r.reason)}</p>
              {text(r.close_note) && (
                <p className="inv-small">
                  <strong>Closing note:</strong> {text(r.close_note)}
                </p>
              )}
              <h3>Suspect lots</h3>
              <ul className="qa-link-list">
                {r.suspects.map((s) => (
                  <li key={text(s.lot_id)}>
                    <button
                      className="text-button"
                      onClick={() => onTrace({ type: 'LOT', id: text(s.lot_id), label: text(s.number) })}
                    >
                      <GitBranch size={12} /> {text(s.number)}
                    </button>
                    <span>
                      {text(s.sku)} · {text(s.item)} · {labelState(s.source_type)}
                    </span>
                    <TraceBadge value={s.status} />
                  </li>
                ))}
              </ul>
              <Tabs
                compact
                label="Recall sections"
                items={[
                  { value: 'lots', label: 'Affected lots', count: num(r.in_stock_lots) || undefined },
                  {
                    value: 'customers',
                    label: 'Customers',
                    count: num(check?.pendingCustomers) || undefined,
                  },
                  { value: 'shipments', label: 'Shipments', count: shipments.length || undefined },
                ]}
                value={section}
                onChange={setSection}
              />
              {section === 'lots' && (
                <>
                  <label className="trace-toggle">
                    <input
                      type="checkbox"
                      checked={inStockOnly}
                      onChange={(e) => setInStockOnly(e.target.checked)}
                    />
                    In stock only ({fmt(r.in_stock_lots)} of {fmt(r.affected_lots)})
                  </label>
                  {lots.length ? (
                    <Table
                      compact
                      pageSize={Math.min(Math.max(lots.length, 1), 50)}
                      rows={lots.map((l) => ({ ...l, id: text(l.lot_id) }))}
                      label="Affected lots"
                      columns={[
                        {
                          label: 'Lot',
                          render: (l) => (
                            <div className="cell-stack">
                              <button
                                className="text-button"
                                onClick={() => onTrace({ type: 'LOT', id: l.id, label: text(l.number) })}
                              >
                                {text(l.number)}
                              </button>
                              <span>
                                {text(l.sku)} · {text(l.item)}
                              </span>
                            </div>
                          ),
                        },
                        {
                          label: 'Relation',
                          render: (l) => (
                            <TraceBadge
                              value={l.relation}
                              label={RELATIONS[text(l.relation)] ?? labelState(l.relation)}
                            />
                          ),
                        },
                        {
                          label: 'Quantity',
                          render: (l) => (
                            <div className="cell-stack">
                              <strong>
                                {fmt(l.quantity, 3)} {text(l.uom)}
                              </strong>
                              <span>{text(l.warehouse_code) || text(l.warehouse)}</span>
                            </div>
                          ),
                        },
                        {
                          label: 'Status',
                          render: (l) => (
                            <div className="cell-stack">
                              <TraceBadge value={l.current_status} />
                              {l.current_status !== l.lot_status && (
                                <span>was {labelState(l.lot_status).toLowerCase()}</span>
                              )}
                            </div>
                          ),
                        },
                        {
                          label: 'Containment',
                          render: (l) =>
                            l.containment ? (
                              <div className="cell-stack">
                                <TraceBadge value={l.containment} label={CONTAINMENT[text(l.containment)]} />
                                {l.ncr_id && (
                                  <button
                                    className="text-button"
                                    onClick={() =>
                                      navigate(`/quality?tab=ncrs&ncr=${encodeURIComponent(text(l.ncr_id))}`)
                                    }
                                  >
                                    {text(l.ncr)} · {labelState(l.ncr_status)}
                                  </button>
                                )}
                              </div>
                            ) : num(l.quantity) > 0 ? (
                              <span className="muted inv-small">
                                {status === 'OPEN' ? 'Not yet contained' : '—'}
                              </span>
                            ) : (
                              <span className="muted inv-small">Consumed or shipped</span>
                            ),
                        },
                      ]}
                    />
                  ) : (
                    <p className="inv-small muted">No affected lot has stock left.</p>
                  )}
                </>
              )}
              {section === 'customers' &&
                (customers.length ? (
                  <Table
                    compact
                    pageSize={Math.max(customers.length, 1)}
                    rows={customers.map((c) => ({ ...c, id: text(c.customer_id) }))}
                    label="Customers to notify"
                    columns={[
                      {
                        label: 'Customer',
                        render: (c) => (
                          <div className="cell-stack">
                            <strong>{text(c.customer)}</strong>
                            <span>
                              {[text(c.email), text(c.phone)].filter(Boolean).join(' · ') ||
                                'No contact on file'}
                            </span>
                          </div>
                        ),
                      },
                      {
                        label: 'Shipped',
                        render: (c) => (
                          <div className="cell-stack">
                            <span>{fmt(c.deliveries)} deliveries</span>
                            <span>{text(c.sales_orders)}</span>
                          </div>
                        ),
                      },
                      { label: 'Status', render: (c) => <TraceBadge value={c.status} /> },
                      {
                        label: 'Notified',
                        render: (c) =>
                          c.status === 'NOTIFIED' ? (
                            <div className="cell-stack">
                              <span>
                                {date(c.notified_on)} · {text(c.contact)}
                              </span>
                              <span className="notes-cell">
                                {text(c.notes) ? `${text(c.notes)} · ` : ''}by {text(c.notified_by_name)}
                              </span>
                            </div>
                          ) : (
                            '—'
                          ),
                      },
                      {
                        label: '',
                        render: (c) =>
                          c.status === 'PENDING' && status === 'CONTAINED' && actor ? (
                            <button className="button small secondary" onClick={() => notifyCustomer(c)}>
                              <Megaphone size={13} /> Record notification
                            </button>
                          ) : c.status === 'PENDING' && status === 'OPEN' ? (
                            <span className="muted inv-small">After containment</span>
                          ) : (
                            ''
                          ),
                      },
                    ]}
                  />
                ) : (
                  <p className="inv-small muted">
                    Nothing made from the suspect material was shipped to a customer.
                  </p>
                ))}
              {section === 'shipments' &&
                (shipments.length ? (
                  <Table
                    compact
                    pageSize={Math.min(Math.max(shipments.length, 1), 50)}
                    rows={shipments.map((s) => ({ ...s, id: text(s.delivery_id) }))}
                    label="Shipments of affected lots"
                    columns={[
                      {
                        label: 'Delivery',
                        render: (s) => (
                          <div className="cell-stack">
                            <button
                              className="text-button"
                              onClick={() =>
                                onTrace({ type: 'DELIVERY', id: text(s.reference), label: text(s.reference) })
                              }
                            >
                              {text(s.reference)}
                            </button>
                            <span>{dateTime(s.delivered_at)}</span>
                          </div>
                        ),
                      },
                      {
                        label: 'Customer',
                        render: (s) => (
                          <div className="cell-stack">
                            <strong>{text(s.customer)}</strong>
                            <span>{text(s.sales_order)}</span>
                          </div>
                        ),
                      },
                      {
                        label: 'Lot',
                        render: (s) => (
                          <div className="cell-stack">
                            <span>{text(s.lot)}</span>
                            <span>
                              {text(s.sku)} · {text(s.item)}
                            </span>
                          </div>
                        ),
                      },
                      {
                        label: 'Quantity',
                        render: (s) => (
                          <div className="cell-stack">
                            <strong>
                              {fmt(s.quantity, 3)} {text(s.uom)}
                            </strong>
                            {num(s.returned) > 0 && <span>{fmt(s.returned, 3)} returned</span>}
                          </div>
                        ),
                      },
                    ]}
                  />
                ) : (
                  <p className="inv-small muted">No shipments of affected lots.</p>
                ))}
              <ActionError message={error} />
              <p className="inv-small muted">Opened {date(r.created_at)}.</p>
            </div>
            {(actor || customers.length > 0) && status !== 'CANCELLED' && (
              <div className="dialog-footer detail-footer">
                {actor && status === 'OPEN' && (
                  <button className="button danger-ghost" onClick={cancel} disabled={busy}>
                    Cancel recall
                  </button>
                )}
                {actor && status === 'OPEN' && (
                  <button
                    className="button secondary"
                    disabled={busy}
                    onClick={() => command(`/recalls/${id}/refresh`, 'Affected set recalculated.')}
                  >
                    {busy ? <LoaderCircle size={14} className="spin" /> : <RefreshCw size={14} />}
                    Recalculate
                  </button>
                )}
                <span className="grow" />
                {customers.length > 0 && (
                  <button className="button secondary" onClick={exportCsv} disabled={exporting}>
                    {exporting ? <LoaderCircle size={14} className="spin" /> : <Download size={14} />}
                    Export notification list
                  </button>
                )}
                {actor && status === 'CONTAINED' && (
                  <button className="button primary" onClick={close}>
                    {outstanding ? 'Close with override…' : 'Close recall'}
                  </button>
                )}
                {actor && status === 'OPEN' && (
                  <button className="button primary" onClick={contain} disabled={busy}>
                    <ShieldCheck size={15} /> Contain
                  </button>
                )}
              </div>
            )}
            {actor && status === 'CONTAINED' && outstanding && user.id === r.created_by && (
              <p className="inv-small muted trace-override-hint">
                Closing with an override needs another quality user than you, the person who opened this
                recall.
              </p>
            )}
          </>
        )}
      </QueryState>
    </Overlay>
  );
}
