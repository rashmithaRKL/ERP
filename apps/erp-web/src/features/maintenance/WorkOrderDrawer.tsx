import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Check, CirclePlay, ClipboardCheck, LoaderCircle, PackageMinus, Timer, Undo2 } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { codeLabel, fmt, money, num, text } from '../../lib/format';
import { ActivityFeed, Lifecycle, QueryState } from '../inventory/shared';
import {
  duration,
  isoFromLocal,
  localInput,
  MAINTENANCE,
  maintenanceChanged,
  PLANNERS,
  TECHNICIANS,
  useMaintenanceCommand,
  useSpareParts,
  useTechnicians,
  when,
} from './api';
import type { ChecklistItem, WorkOrderDetail } from './api';
import { PriorityText, ReasonChip, StatusChip, TypeChip } from './shared';

const STEPS = ['Requested', 'Approved', 'Scheduled', 'In progress', 'Completed'];
const STEP: Record<string, number> = {
  REQUESTED: 0,
  APPROVED: 1,
  SCHEDULED: 2,
  IN_PROGRESS: 3,
  COMPLETED: 4,
};
const ISSUABLE = ['APPROVED', 'SCHEDULED', 'IN_PROGRESS'];
const RESULTS = [
  { value: 'PASS', label: 'Pass' },
  { value: 'FAIL', label: 'Fail' },
  { value: 'NA', label: 'N/A' },
] as const;

/** Maintenance work order: lifecycle actions, checklist, findings, spare parts, labour, downtime and history. */
export function WorkOrderDrawer({ id, onClose }: { id: string; onClose: () => void }) {
  const workspace = useWorkspace();
  const { user, can, openForm } = workspace;
  const detail = useQuery({
    queryKey: [MAINTENANCE, 'work-order', id],
    queryFn: () => request<WorkOrderDetail>(`/maintenance/work-orders/${id}`),
  });
  const { run, busy } = useMaintenanceCommand();
  const [error, setError] = useState('');
  const [checklist, setChecklist] = useState<ChecklistItem[]>([]);
  const [findings, setFindings] = useState('');
  const [actions, setActions] = useState('');
  const d = detail.data?.order;
  const status = text(d?.status);
  const technician = can(...TECHNICIANS);
  const planner = can(...PLANNERS);
  const executing = status === 'IN_PROGRESS' && technician;
  const issuable = ISSUABLE.includes(status) && technician;
  const technicians = useTechnicians(planner || technician);
  const spareParts = useSpareParts(issuable);

  useEffect(() => {
    if (!detail.data) return;
    setChecklist(detail.data.checklist);
    setFindings(text(detail.data.order.findings));
    setActions(text(detail.data.order.actions));
  }, [detail.data]);

  async function act(url: string, body: unknown, success: string) {
    setError('');
    try {
      await run(`/maintenance/work-orders/${id}${url}`, body, success);
      return true;
    } catch (e) {
      setError((e as Error).message);
      return false;
    }
  }

  const results = () =>
    checklist.map((item, index) => ({ index, result: item.result, note: item.note || '' }));
  const technicianOptions = (technicians.data?.items || []).map((t) => ({
    value: t.id,
    label: `${text(t.name)}${t.role === 'ADMIN' ? ' (administrator)' : ''}`,
  }));
  const number = text(d?.number);

  function approve() {
    openForm({
      title: `Approve ${number}`,
      description:
        'Optionally schedule the maintenance window now. Without a window the order stays approved until it is scheduled.',
      submit: 'Approve',
      fields: [
        { name: 'scheduledStart', label: 'Window start', type: 'datetime-local', optional: true },
        { name: 'scheduledEnd', label: 'Window end', type: 'datetime-local', optional: true },
        { name: 'assigneeId', label: 'Technician', options: technicianOptions, optional: true },
      ],
      run: async (v, key) => {
        await request(
          `/maintenance/work-orders/${id}/approve`,
          {
            scheduledStart: isoFromLocal(v.scheduledStart),
            scheduledEnd: isoFromLocal(v.scheduledEnd),
            assigneeId: v.assigneeId,
          },
          key,
        );
        maintenanceChanged();
      },
    });
  }

  function schedule() {
    openForm({
      title: `Schedule ${number}`,
      description: 'The machine is planned to be unavailable for production during this window.',
      submit: 'Save window',
      fields: [
        {
          name: 'scheduledStart',
          label: 'Window start',
          type: 'datetime-local',
          defaultValue: localInput(text(d?.scheduled_start)),
        },
        {
          name: 'scheduledEnd',
          label: 'Window end',
          type: 'datetime-local',
          defaultValue: localInput(text(d?.scheduled_end)),
        },
        {
          name: 'assigneeId',
          label: 'Technician',
          options: technicianOptions,
          optional: true,
          defaultValue: text(d?.assignee_id),
        },
      ],
      run: async (v, key) => {
        await request(
          `/maintenance/work-orders/${id}/schedule`,
          {
            scheduledStart: isoFromLocal(v.scheduledStart),
            scheduledEnd: isoFromLocal(v.scheduledEnd),
            assigneeId: v.assigneeId,
          },
          key,
        );
        maintenanceChanged();
      },
    });
  }

  function cancel() {
    openForm({
      title: `Cancel ${number}`,
      description:
        d?.type === 'PREVENTIVE'
          ? 'Cancelling a preventive order skips this occurrence: the plan’s next due date moves on by one interval.'
          : 'Cancelled orders are kept in the machine history.',
      submit: 'Cancel work order',
      fields: [{ name: 'reason', label: 'Reason' }],
      run: async (v, key) => {
        await request(`/maintenance/work-orders/${id}/cancel`, v, key);
        maintenanceChanged();
      },
    });
  }

  function complete() {
    const open = checklist.filter((i) => !i.result).length;
    if (open) {
      setError(`Record a result for every checklist item first (${open} still open).`);
      return;
    }
    openForm({
      title: `Complete ${number}`,
      description: `${text(d?.machine)} returns to idle and its open downtime closes now.`,
      submit: 'Complete work',
      fields: [
        { name: 'findings', label: 'Findings', type: 'textarea', defaultValue: findings },
        { name: 'actions', label: 'Actions taken', type: 'textarea', optional: true, defaultValue: actions },
        {
          name: 'meterReading',
          label: 'Run-hour meter',
          type: 'number',
          min: '0',
          step: '0.01',
          optional: true,
          hint: 'Restarts the run-hour count of the machine’s plans.',
        },
      ],
      run: async (v, key) => {
        await request(
          `/maintenance/work-orders/${id}/complete`,
          {
            findings: v.findings,
            actions: v.actions,
            checklist: results(),
            meterReading: v.meterReading || undefined,
          },
          key,
        );
        maintenanceChanged();
      },
    });
  }

  const own = !!d && d.requested_by === user.id;
  const openDowntime = detail.data?.downtime.find((e) => e.status === 'OPEN');
  const dirty =
    !!detail.data &&
    (findings !== text(d?.findings) ||
      actions !== text(d?.actions) ||
      JSON.stringify(checklist) !== JSON.stringify(detail.data.checklist));

  return (
    <Overlay
      title={number || 'Maintenance work order'}
      subtitle="Checklist, spare parts, labour, downtime and history"
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
                  <h2>{text(d.title)}</h2>
                  <p>
                    {text(d.machine)} · {text(d.machine_name)} · requested by {text(d.requested_by_name)},{' '}
                    {when(d.created_at)}
                  </p>
                </div>
                <StatusChip value={d.status} />
              </div>
              <div className="mnt-tags">
                <TypeChip type={d.type} />
                <PriorityText value={d.priority} />
                {d.plan && (
                  <span className="mnt-tag">
                    Plan {text(d.plan)} · due {text(d.due_date)}
                  </span>
                )}
                {d.assignee && <span className="mnt-tag">Technician {text(d.assignee)}</span>}
              </div>
              {error && (
                <div className="form-error" role="alert">
                  {error}
                </div>
              )}
              {status !== 'CANCELLED' && <Lifecycle steps={STEPS} index={STEP[status] ?? 0} />}
              <div className="detail-stats">
                <div>
                  <span>Labour</span>
                  <strong>
                    {fmt(d.labour_hours, 2)} <small>h</small>
                  </strong>
                </div>
                <div>
                  <span>Labour cost</span>
                  <strong>{money(d.labour_cost)}</strong>
                </div>
                <div>
                  <span>Spare parts</span>
                  <strong>{money(d.parts_cost)}</strong>
                </div>
                <div>
                  <span>Total cost</span>
                  <strong>{money(d.total_cost)}</strong>
                </div>
              </div>
              <dl className="mnt-facts">
                <div>
                  <dt>Maintenance window</dt>
                  <dd>
                    {d.scheduled_start
                      ? `${when(d.scheduled_start)} – ${when(d.scheduled_end)}`
                      : 'Not scheduled'}
                  </dd>
                </div>
                <div>
                  <dt>Approved</dt>
                  <dd>{d.approved_by_name ? `${text(d.approved_by_name)}, ${when(d.approved_at)}` : '—'}</dd>
                </div>
                <div>
                  <dt>Work</dt>
                  <dd>
                    {d.started_at ? `Started ${when(d.started_at)}` : 'Not started'}
                    {d.completed_at ? ` · completed ${when(d.completed_at)}` : ''}
                  </dd>
                </div>
                <div>
                  <dt>Machine</dt>
                  <dd>
                    <StatusChip value={d.machine_status} />
                    {openDowntime && (
                      <span className="mnt-down">
                        down{' '}
                        {duration((Date.now() - new Date(text(openDowntime.started_at)).getTime()) / 60000)}
                      </span>
                    )}
                  </dd>
                </div>
              </dl>
              {status === 'CANCELLED' && <p className="inv-notes">Cancelled: {text(d.cancel_reason)}</p>}
              {text(d.description) && <p className="inv-notes">{text(d.description)}</p>}

              {checklist.length > 0 && (
                <>
                  <h3 className="mnt-section">
                    <ClipboardCheck size={15} /> Checklist
                    <small>
                      {checklist.filter((i) => i.result).length} of {checklist.length} done
                    </small>
                  </h3>
                  <ol className="mnt-checklist">
                    {checklist.map((item, index) => (
                      <li key={index} className={item.result ? `done ${item.result.toLowerCase()}` : ''}>
                        <span className="mnt-task">{item.task}</span>
                        {executing ? (
                          <div className="mnt-check-edit">
                            <div
                              className="inv-segmented"
                              role="radiogroup"
                              aria-label={`Result: ${item.task}`}
                            >
                              {RESULTS.map((r) => (
                                <button
                                  key={r.value}
                                  type="button"
                                  role="radio"
                                  aria-checked={item.result === r.value}
                                  className={item.result === r.value ? 'active' : ''}
                                  onClick={() =>
                                    setChecklist((list) =>
                                      list.map((x, i) => (i === index ? { ...x, result: r.value } : x)),
                                    )
                                  }
                                >
                                  {r.label}
                                </button>
                              ))}
                            </div>
                            <input
                              aria-label={`Note: ${item.task}`}
                              placeholder="Note"
                              value={item.note || ''}
                              maxLength={400}
                              onChange={(e) =>
                                setChecklist((list) =>
                                  list.map((x, i) => (i === index ? { ...x, note: e.target.value } : x)),
                                )
                              }
                            />
                          </div>
                        ) : (
                          <span className="mnt-check-result">
                            {item.result ? RESULTS.find((r) => r.value === item.result)?.label : 'Open'}
                            {item.note ? ` · ${item.note}` : ''}
                          </span>
                        )}
                      </li>
                    ))}
                  </ol>
                </>
              )}

              <h3 className="mnt-section">Findings and actions</h3>
              {executing ? (
                <div className="mnt-notes-edit">
                  <label className="field">
                    <span>Findings</span>
                    <textarea
                      rows={3}
                      maxLength={4000}
                      value={findings}
                      onChange={(e) => setFindings(e.target.value)}
                    />
                  </label>
                  <label className="field">
                    <span>Actions taken</span>
                    <textarea
                      rows={3}
                      maxLength={4000}
                      value={actions}
                      onChange={(e) => setActions(e.target.value)}
                    />
                  </label>
                  <div>
                    <button
                      className="button secondary small"
                      disabled={busy || !dirty}
                      onClick={() =>
                        act(
                          '/progress',
                          { checklist: results(), findings, actions },
                          `${number} progress saved successfully.`,
                        )
                      }
                    >
                      {busy && <LoaderCircle size={13} className="spin" />}
                      Save progress
                    </button>
                  </div>
                </div>
              ) : text(d.findings) || text(d.actions) ? (
                <dl className="mnt-facts single">
                  <div>
                    <dt>Findings</dt>
                    <dd>{text(d.findings) || '—'}</dd>
                  </div>
                  <div>
                    <dt>Actions taken</dt>
                    <dd>{text(d.actions) || '—'}</dd>
                  </div>
                </dl>
              ) : (
                <p className="muted inv-small">Recorded by the technician while the work is in progress.</p>
              )}

              <PartsSection
                detail={detail.data}
                issuable={issuable}
                parts={spareParts.data?.items}
                act={act}
                busy={busy}
              />
              <LabourSection
                detail={detail.data}
                executing={executing}
                technicians={technicianOptions}
                act={act}
                busy={busy}
              />

              {detail.data.downtime.length > 0 && (
                <>
                  <h3 className="mnt-section">
                    <Timer size={15} /> Downtime
                  </h3>
                  <Table
                    compact
                    label="Downtime covered by this order"
                    pageSize={Math.max(detail.data.downtime.length, 1)}
                    rows={detail.data.downtime}
                    columns={[
                      { label: 'Event', key: 'number' },
                      {
                        label: 'Reason',
                        render: (r) => <ReasonChip reason={r.reason} planned={r.planned} />,
                      },
                      { label: 'From', render: (r) => when(r.started_at) },
                      { label: 'To', render: (r) => (r.ended_at ? when(r.ended_at) : 'Open') },
                      { label: 'Duration', render: (r) => duration(r.minutes) },
                    ]}
                  />
                </>
              )}
              {detail.data.journal.length > 0 && (
                <>
                  <h3 className="mnt-section">Journal</h3>
                  <Table
                    compact
                    label="Journal lines of this order"
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
              <h3 className="mnt-section">Activity</h3>
              <ActivityFeed events={detail.data.events} />
            </div>
            {['REQUESTED', 'APPROVED', 'SCHEDULED', 'IN_PROGRESS'].includes(status) && (
              <div className="dialog-footer detail-footer">
                {planner && status !== 'IN_PROGRESS' && (
                  <button className="button danger-ghost" disabled={busy} onClick={cancel}>
                    Cancel order
                  </button>
                )}
                <span className="grow" />
                {status === 'REQUESTED' &&
                  (planner && !own ? (
                    <button className="button primary" disabled={busy} onClick={approve}>
                      <Check size={15} />
                      Approve
                    </button>
                  ) : (
                    <span className="muted inv-small">
                      {own
                        ? 'Awaiting approval by a different planner or administrator.'
                        : 'Awaiting approval by a planner or administrator.'}
                    </span>
                  ))}
                {['APPROVED', 'SCHEDULED'].includes(status) && planner && (
                  <button className="button secondary" disabled={busy} onClick={schedule}>
                    {status === 'SCHEDULED' ? 'Reschedule' : 'Schedule'}
                  </button>
                )}
                {['APPROVED', 'SCHEDULED'].includes(status) && technician && (
                  <button
                    className="button primary"
                    disabled={busy}
                    onClick={() =>
                      act('/start', {}, `${number} started; ${text(d.machine)} is under maintenance.`)
                    }
                  >
                    {busy ? <LoaderCircle size={15} className="spin" /> : <CirclePlay size={15} />}
                    Start work
                  </button>
                )}
                {['APPROVED', 'SCHEDULED'].includes(status) && !technician && !planner && (
                  <span className="muted inv-small">A maintenance technician starts the work.</span>
                )}
                {status === 'IN_PROGRESS' &&
                  (technician ? (
                    <button className="button primary" disabled={busy} onClick={complete}>
                      <Check size={15} />
                      Complete work
                    </button>
                  ) : (
                    <span className="muted inv-small">In progress · a technician completes the work.</span>
                  ))}
              </div>
            )}
          </>
        )}
      </QueryState>
    </Overlay>
  );
}

type Act = (url: string, body: unknown, success: string) => Promise<boolean>;

/** Spare parts issued to the order, returns, and the issue form. */
function PartsSection({
  detail,
  issuable,
  parts,
  act,
  busy,
}: {
  detail: WorkOrderDetail;
  issuable: boolean;
  parts: Row[] | undefined;
  act: Act;
  busy: boolean;
}) {
  const { openForm } = useWorkspace();
  const [itemId, setItemId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const item = parts?.find((p) => p.id === itemId);
  const number = text(detail.order.number);
  async function issue(e: FormEvent) {
    e.preventDefault();
    if (
      await act('/parts', { itemId, quantity, notes: '' }, `Spare parts issued to ${number} successfully.`)
    ) {
      setQuantity('1');
    }
  }
  function giveBack(movement: Row) {
    openForm({
      title: `Return ${text(movement.sku)}`,
      description: `Back into lot ${text(movement.lot)} at the issue’s unit value. Up to ${fmt(movement.returnable, 3)} ${text(movement.uom)}.`,
      submit: 'Return parts',
      fields: [
        {
          name: 'quantity',
          label: `Quantity (${text(movement.uom)})`,
          type: 'number',
          min: '0.000001',
          defaultValue: text(num(movement.returnable)),
          step: movement.uom === 'pcs' ? '1' : undefined,
        },
        { name: 'notes', label: 'Notes', optional: true },
      ],
      run: async (v, key) => {
        await request(`/maintenance/work-orders/${detail.order.id}/parts/${movement.id}/return`, v, key);
        maintenanceChanged();
      },
    });
  }
  if (!detail.parts.length && !issuable) return null;
  return (
    <>
      <h3 className="mnt-section">
        <PackageMinus size={15} /> Spare parts
      </h3>
      {detail.parts.length > 0 ? (
        <Table
          compact
          label="Spare parts of this order"
          pageSize={Math.max(detail.parts.length, 1)}
          rows={detail.parts}
          columns={[
            {
              label: 'Part',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.item)}</strong>
                  <span>
                    {text(r.sku)} · lot {text(r.lot)}
                  </span>
                </div>
              ),
            },
            {
              label: 'Movement',
              render: (r) =>
                r.kind === 'ISSUE' ? (
                  <span className="negative">
                    −{fmt(r.quantity, 3)} {text(r.uom)} issued
                  </span>
                ) : (
                  <span className="positive">
                    +{fmt(r.quantity, 3)} {text(r.uom)} returned
                  </span>
                ),
            },
            { label: 'Value', render: (r) => money(r.value_amount) },
            { label: 'By', render: (r) => `${text(r.created_by_name)} · ${when(r.created_at)}` },
            {
              label: '',
              render: (r) =>
                issuable && r.kind === 'ISSUE' && num(r.returnable) > 0 ? (
                  <button className="text-button" disabled={busy} onClick={() => giveBack(r)}>
                    <Undo2 size={13} />
                    Return
                  </button>
                ) : null,
            },
          ]}
        />
      ) : (
        <p className="muted inv-small">No spare parts issued yet.</p>
      )}
      {issuable && (
        <form className="mnt-inline-form" onSubmit={issue}>
          <label className="field">
            <span>Spare part</span>
            <select required value={itemId} onChange={(e) => setItemId(e.target.value)}>
              <option value="" disabled>
                {parts ? (parts.length ? 'Select a spare part' : 'No spare parts in stock') : 'Loading…'}
              </option>
              {(parts || []).map((p) => (
                <option key={p.id} value={p.id} disabled={num(p.available) <= 0}>
                  {text(p.sku)} · {text(p.name)} ({fmt(p.available, 3)} {text(p.uom)} available)
                </option>
              ))}
            </select>
          </label>
          <label className="field mnt-qty">
            <span>Quantity{item ? ` (${text(item.uom)})` : ''}</span>
            <input
              type="number"
              required
              min="0.000001"
              step={item?.uom === 'pcs' ? '1' : '0.000001'}
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
            />
          </label>
          <button className="button secondary" disabled={busy || !itemId}>
            {busy && <LoaderCircle size={14} className="spin" />}
            Issue parts
          </button>
        </form>
      )}
    </>
  );
}

/** Labour entries and the log form (in progress only). */
function LabourSection({
  detail,
  executing,
  technicians,
  act,
  busy,
}: {
  detail: WorkOrderDetail;
  executing: boolean;
  technicians: { value: string; label: string }[];
  act: Act;
  busy: boolean;
}) {
  const [hours, setHours] = useState('');
  const [technicianId, setTechnicianId] = useState('');
  const [notes, setNotes] = useState('');
  async function log(e: FormEvent) {
    e.preventDefault();
    if (
      await act(
        '/labour',
        { hours, technicianId: technicianId || undefined, notes },
        `Labour logged on ${text(detail.order.number)} successfully.`,
      )
    ) {
      setHours('');
      setNotes('');
    }
  }
  if (!detail.labour.length && !executing) return null;
  return (
    <>
      <h3 className="mnt-section">Labour</h3>
      {detail.labour.length > 0 ? (
        <Table
          compact
          label="Labour on this order"
          pageSize={Math.max(detail.labour.length, 1)}
          rows={detail.labour}
          columns={[
            { label: 'Technician', render: (r) => text(r.technician) },
            { label: 'Hours', render: (r) => fmt(r.hours, 2) },
            { label: 'Rate', render: (r) => `${money(r.rate)} / h` },
            { label: 'Cost', render: (r) => money(r.cost) },
            {
              label: 'Notes',
              render: (r) => (
                <div className="cell-stack">
                  <span>{text(r.notes) || '—'}</span>
                  <span>{when(r.created_at)}</span>
                </div>
              ),
            },
          ]}
        />
      ) : (
        <p className="muted inv-small">No labour logged yet.</p>
      )}
      {executing && (
        <form className="mnt-inline-form" onSubmit={log}>
          <label className="field mnt-qty">
            <span>Hours</span>
            <input
              type="number"
              required
              step="0.25"
              min="-1000"
              max="1000"
              value={hours}
              onChange={(e) => setHours(e.target.value)}
            />
          </label>
          <label className="field">
            <span>Technician</span>
            <select value={technicianId} onChange={(e) => setTechnicianId(e.target.value)}>
              <option value="">Me</option>
              {technicians.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Notes</span>
            <input value={notes} maxLength={400} onChange={(e) => setNotes(e.target.value)} />
          </label>
          <button className="button secondary" disabled={busy || !hours}>
            {busy && <LoaderCircle size={14} className="spin" />}
            Log labour
          </button>
        </form>
      )}
      {executing && (
        <p className="muted inv-small">
          Negative hours reverse an earlier entry. Labour is costed at the maintenance labour rate.
        </p>
      )}
    </>
  );
}
