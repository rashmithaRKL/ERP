import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarPlus, Pencil, Plus } from 'lucide-react';
import { useStateData, useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, fmt, money, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { ActivityFeed, QueryState } from '../inventory/shared';
import { MAINTENANCE, maintenanceChanged, PLANNERS, when } from './api';
import type { PlanDetail } from './api';
import { PlanDialog } from './PlanDialog';
import { PriorityText, StatusChip } from './shared';

const TABS = [
  { value: 'due', label: 'Due in 7 days' },
  { value: 'overdue', label: 'Overdue' },
  { value: 'all', label: 'All active' },
  { value: 'inactive', label: 'Inactive' },
];

const interval = (r: Row | Record<string, unknown>) =>
  `Every ${fmt(r.interval_days)} ${r.interval_days === 1 ? 'day' : 'days'}${
    r.interval_hours ? ` or ${fmt(r.interval_hours, 2)} run h` : ''
  }`;

function dueText(r: Row) {
  const days = Number(r.days_until_due);
  if (!r.active) return date(r.next_due_date);
  if (days < 0) return `${date(r.next_due_date)} · ${-days} ${days === -1 ? 'day' : 'days'} overdue`;
  if (days === 0) return `${date(r.next_due_date)} · today`;
  return `${date(r.next_due_date)} · in ${days} ${days === 1 ? 'day' : 'days'}`;
}

/** Preventive plans: due/overdue lists, generation of due work orders, create/edit and the plan drawer. */
export function PlansTab({ onOpenOrder }: { onOpenOrder: (id: string) => void }) {
  const workspace = useWorkspace();
  const data = useStateData();
  const qc = useQueryClient();
  const [tab, setTab] = useState('due');
  const [machine, setMachine] = useState('');
  const [open, setOpen] = useState<string>();
  const [editing, setEditing] = useState<PlanDetail['plan'] | 'new'>();
  const paged = useCursorPage(
    [MAINTENANCE, 'plans'],
    '/maintenance/plans',
    {
      due: tab === 'due' || tab === 'overdue' ? tab : undefined,
      horizon: tab === 'due' ? 7 : undefined,
      active: tab === 'inactive' ? 'false' : undefined,
      machine,
    },
    25,
  );
  const counts = (paged.query.data?.counts || {}) as Record<string, number>;
  const planner = workspace.can(...PLANNERS);

  function generate() {
    workspace.openForm({
      title: 'Generate due work orders',
      description:
        'Creates one preventive work order per active plan due within the horizon, approved and scheduled on its due date at 08:00. Plans that already have an open order are skipped; running it again never duplicates an order.',
      submit: 'Generate orders',
      fields: [
        {
          name: 'horizonDays',
          label: 'Horizon',
          options: [
            { value: '0', label: 'Due today or overdue' },
            { value: '7', label: 'Due within 7 days' },
            { value: '14', label: 'Due within 14 days' },
            { value: '31', label: 'Due within 31 days' },
          ],
          defaultValue: '7',
        },
      ],
      run: async (v, key) => {
        const result = await request<{ created: Row[]; skipped: Row[] }>(
          '/maintenance/plans/generate',
          { horizonDays: Number(v.horizonDays) },
          key,
        );
        maintenanceChanged();
        workspace.notify(
          result.created.length
            ? `${result.created.length} preventive ${result.created.length === 1 ? 'order' : 'orders'} generated successfully: ${result.created.map((c) => c.number).join(', ')}.`
            : `No new orders: ${result.skipped.length ? `${result.skipped.length} due ${result.skipped.length === 1 ? 'plan already has' : 'plans already have'} an order` : 'no plan is due in that horizon'}.`,
        );
      },
    });
  }

  return (
    <>
      <Tabs
        compact
        label="Plan due state"
        items={TABS.map((t) => ({
          ...t,
          count:
            t.value === 'overdue'
              ? counts.overdue || undefined
              : t.value === 'due'
                ? (counts.overdue || 0) + (counts.due_soon || 0) || undefined
                : undefined,
        }))}
        value={tab}
        onChange={setTab}
      />
      <div className="inv-toolbar">
        <div className="inv-filters">
          <select aria-label="Work center" value={machine} onChange={(e) => setMachine(e.target.value)}>
            <option value="">All work centers</option>
            {data.machines.map((m) => (
              <option key={m.id} value={m.id}>
                {text(m.code)}
              </option>
            ))}
          </select>
        </div>
        {planner && (
          <div className="inv-actions">
            <button className="button secondary small" onClick={generate}>
              <CalendarPlus size={14} />
              Generate due orders
            </button>
            <button className="button primary small" onClick={() => setEditing('new')}>
              <Plus size={14} />
              New plan
            </button>
          </div>
        )}
      </div>
      <PageState paged={paged}>
        <Table
          rows={paged.rows}
          compact
          label="Preventive maintenance plans"
          pageSize={Math.max(paged.rows.length, 1)}
          onRow={(r) => setOpen(r.id)}
          columns={[
            {
              label: 'Plan',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.title)}</strong>
                  <span>{text(r.number)}</span>
                </div>
              ),
            },
            {
              label: 'Work center',
              render: (r) => (
                <div className="cell-stack">
                  <strong>{text(r.machine)}</strong>
                  <span>{text(r.machine_name)}</span>
                </div>
              ),
            },
            { label: 'Interval', render: (r) => interval(r) },
            {
              label: 'Next due',
              render: (r) => (
                <div className="cell-stack">
                  <StatusChip value={r.due_state} />
                  <span>{dueText(r)}</span>
                </div>
              ),
            },
            {
              label: 'Order',
              render: (r) =>
                r.open_order ? (
                  <div className="cell-stack">
                    <strong>{text(r.open_order)}</strong>
                    <span>{text(r.open_order_status).toLowerCase().replace('_', ' ')}</span>
                  </div>
                ) : (
                  <span className="muted">None open</span>
                ),
            },
            { label: 'Last done', render: (r) => (r.last_completed_at ? when(r.last_completed_at) : '—') },
          ]}
        />
        <PageFooter paged={paged} label="plans" />
      </PageState>
      <AnimatePresence>
        {open && (
          <PlanDrawer
            key={open}
            id={open}
            onClose={() => setOpen(undefined)}
            onEdit={(plan) => setEditing(plan)}
            onOpenOrder={(id) => {
              setOpen(undefined);
              onOpenOrder(id);
            }}
          />
        )}
        {editing && (
          <PlanDialog
            key="plan-dialog"
            plan={editing === 'new' ? undefined : editing}
            onClose={() => setEditing(undefined)}
            onDone={async (id) => {
              setEditing(undefined);
              await qc.invalidateQueries({ queryKey: [MAINTENANCE] });
              await workspace.refresh();
              workspace.notify('Maintenance plan saved successfully.');
              setOpen(id);
            }}
          />
        )}
      </AnimatePresence>
    </>
  );
}

function PlanDrawer({
  id,
  onClose,
  onEdit,
  onOpenOrder,
}: {
  id: string;
  onClose: () => void;
  onEdit: (plan: PlanDetail['plan']) => void;
  onOpenOrder: (id: string) => void;
}) {
  const { can } = useWorkspace();
  const detail = useQuery({
    queryKey: [MAINTENANCE, 'plan', id],
    queryFn: () => request<PlanDetail>(`/maintenance/plans/${id}`),
  });
  const p = detail.data?.plan;
  const tasks = (p && Array.isArray(p.checklist) ? p.checklist : []) as string[];
  return (
    <Overlay title={text(p?.number) || 'Maintenance plan'} subtitle="Preventive maintenance plan" onClose={onClose} drawer>
      <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
        {detail.data && p && (
          <>
            <div className="drawer-body">
              <div className="detail-product">
                <div>
                  <h2>{text(p.title)}</h2>
                  <p>
                    {text(p.machine)} · {text(p.machine_name)}
                  </p>
                </div>
                <StatusChip value={p.due_state} />
              </div>
              <dl className="mnt-facts">
                <div>
                  <dt>Interval</dt>
                  <dd>{interval(p)}</dd>
                </div>
                <div>
                  <dt>Next due</dt>
                  <dd>{dueText(p as Row)}</dd>
                </div>
                <div>
                  <dt>Duration · priority</dt>
                  <dd>
                    {fmt(p.estimated_hours, 2)} h · <PriorityText value={p.priority} />
                  </dd>
                </div>
                <div>
                  <dt>Run hours</dt>
                  <dd>
                    {p.meter_reading == null
                      ? 'No meter reading'
                      : `${fmt(p.meter_reading, 2)} h${
                          p.meter_at_last_service == null
                            ? ''
                            : ` · ${fmt(Number(p.meter_reading) - Number(p.meter_at_last_service), 2)} h since service`
                        }`}
                  </dd>
                </div>
              </dl>
              {text(p.description) && <p className="inv-notes">{text(p.description)}</p>}
              <h3 className="mnt-section">Checklist</h3>
              {tasks.length ? (
                <ol className="mnt-checklist">
                  {tasks.map((task, i) => (
                    <li key={i}>
                      <span className="mnt-task">{task}</span>
                    </li>
                  ))}
                </ol>
              ) : (
                <p className="muted inv-small">No checklist items.</p>
              )}
              <h3 className="mnt-section">Work orders</h3>
              <Table
                compact
                label="Work orders of this plan"
                pageSize={Math.max(detail.data.orders.length, 1)}
                rows={detail.data.orders}
                onRow={(r) => onOpenOrder(r.id)}
                columns={[
                  { label: 'Order', key: 'number' },
                  { label: 'Due', render: (r) => date(r.due_date) },
                  { label: 'Status', render: (r) => <StatusChip value={r.status} /> },
                  { label: 'Done', render: (r) => (r.completed_at ? when(r.completed_at) : '—') },
                  { label: 'Cost', render: (r) => money(r.total_cost) },
                ]}
              />
              <h3 className="mnt-section">Activity</h3>
              <ActivityFeed events={detail.data.events} />
            </div>
            {can(...PLANNERS) && (
              <div className="dialog-footer detail-footer">
                <span className="grow" />
                <button className="button secondary" onClick={() => onEdit(p)}>
                  <Pencil size={14} />
                  Edit plan
                </button>
              </div>
            )}
          </>
        )}
      </QueryState>
    </Overlay>
  );
}
