import type { Workspace } from '../../app/workspace';
import { option, selectField } from '../../components';
import type { Field } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { text } from '../../lib/format';
import { isoFromLocal, localInput, maintenanceChanged, PRIORITIES, REASON_LABELS, REASONS } from './api';

const priorityOptions = PRIORITIES.map((p) => ({ value: p, label: p.charAt(0) + p.slice(1).toLowerCase() }));
const machineOptions = (machines: Row[]) =>
  option(machines, 'code').map((o) => ({
    ...o,
    label: `${o.label} · ${text(machines.find((m) => m.id === o.value)?.name)}`,
  }));

/**
 * "Request maintenance" / "Report breakdown": a corrective work order; with "Machine stopped" it also opens a
 * BREAKDOWN downtime event (from now, or from the time entered).
 */
export function requestMaintenance(workspace: Workspace, machineId?: string, breakdown = false) {
  const { data, openForm } = workspace;
  if (!data) return;
  openForm({
    title: breakdown ? 'Report breakdown' : 'Request maintenance',
    description: breakdown
      ? 'Records the stop as open breakdown downtime and asks a planner to approve the repair.'
      : 'A planner other than you approves the request before a technician starts the work.',
    submit: breakdown ? 'Report breakdown' : 'Send request',
    fields: [
      selectField('machineId', 'Work center', machineOptions(data.machines), machineId),
      { name: 'title', label: 'Problem', hint: 'For example “Hydraulic leak at the clamp unit”.' },
      { name: 'description', label: 'Details', type: 'textarea', optional: true },
      selectField('priority', 'Priority', priorityOptions, breakdown ? 'HIGH' : 'NORMAL'),
      selectField(
        'breakdown',
        'Machine stopped?',
        [
          { value: 'yes', label: 'Yes – record open breakdown downtime' },
          { value: 'no', label: 'No – it can keep running for now' },
        ],
        breakdown ? 'yes' : 'no',
      ),
      {
        name: 'downtimeStartedAt',
        label: 'Stopped at',
        type: 'datetime-local',
        optional: true,
        hint: 'Leave empty for now. Only used when the machine stopped.',
      },
    ],
    run: async (v, key) => {
      const result = await request<Row>(
        '/maintenance/work-orders',
        {
          machineId: v.machineId,
          title: v.title,
          description: v.description,
          priority: v.priority,
          breakdown: v.breakdown === 'yes',
          downtimeStartedAt: v.breakdown === 'yes' ? isoFromLocal(v.downtimeStartedAt) : undefined,
        },
        key,
      );
      maintenanceChanged();
      return result;
    },
  });
}

/** "Report downtime": open (no end) or already over (with an end time). */
export function reportDowntime(workspace: Workspace, machineId?: string) {
  const { data, openForm } = workspace;
  if (!data) return;
  const fields: Field[] = [
    selectField('machineId', 'Work center', machineOptions(data.machines), machineId),
    selectField(
      'reason',
      'Reason',
      REASONS.map((r) => ({ value: r, label: REASON_LABELS[r] })),
    ),
    {
      ...selectField('planned', 'Planned stop', [
        { value: 'yes', label: 'Planned' },
        { value: 'no', label: 'Unplanned' },
      ]),
      optional: true,
      hint: 'Empty: by reason (breakdowns are unplanned, changeovers planned).',
    },
    {
      name: 'startedAt',
      label: 'Started',
      type: 'datetime-local',
      defaultValue: localInput(new Date()),
    },
    {
      name: 'endedAt',
      label: 'Ended',
      type: 'datetime-local',
      optional: true,
      hint: 'Leave empty while the machine is still down.',
    },
    { name: 'notes', label: 'Notes', type: 'textarea', optional: true },
  ];
  openForm({
    title: 'Report downtime',
    description: 'Downtime of one machine never overlaps another stop of the same machine.',
    submit: 'Report downtime',
    fields,
    run: async (v, key) => {
      const result = await request<Row>(
        '/maintenance/downtime',
        {
          machineId: v.machineId,
          reason: v.reason,
          planned: v.planned === 'yes' ? true : v.planned === 'no' ? false : undefined,
          startedAt: isoFromLocal(v.startedAt),
          endedAt: isoFromLocal(v.endedAt),
          notes: v.notes,
        },
        key,
      );
      maintenanceChanged();
      return result;
    },
  });
}

/** Close an open downtime event (now, or at the time entered). */
export function closeDowntime(workspace: Workspace, event: Row) {
  workspace.openForm({
    title: `Close ${text(event.number)}`,
    description: `${text(event.machine)} · ${REASON_LABELS[text(event.reason)] || text(event.reason)} since ${new Date(text(event.started_at)).toLocaleString('en-GB')}`,
    submit: 'Close downtime',
    fields: [
      {
        name: 'endedAt',
        label: 'Ended',
        type: 'datetime-local',
        optional: true,
        defaultValue: localInput(new Date()),
      },
      { name: 'notes', label: 'Notes', type: 'textarea', optional: true },
    ],
    run: async (v, key) => {
      const result = await request(
        `/maintenance/downtime/${event.id}/close`,
        { endedAt: isoFromLocal(v.endedAt), notes: v.notes },
        key,
      );
      maintenanceChanged();
      return result;
    },
  });
}

/** Planner: replace a closed downtime event by a corrected one, or withdraw it. */
export function correctDowntime(workspace: Workspace, event: Row) {
  workspace.openForm({
    title: `Correct ${text(event.number)}`,
    description:
      'Closed downtime is never edited: the event is marked corrected and the corrected values are recorded as a new event.',
    submit: 'Record correction',
    fields: [
      selectField(
        'action',
        'Correction',
        [
          { value: 'correct', label: 'Correct reason or times' },
          { value: 'withdraw', label: 'Withdraw (it never happened)' },
        ],
        'correct',
      ),
      selectField(
        'reason',
        'Reason',
        REASONS.map((r) => ({ value: r, label: REASON_LABELS[r] })),
        text(event.reason),
      ),
      { name: 'startedAt', label: 'Started', type: 'datetime-local', defaultValue: localInput(text(event.started_at)) },
      { name: 'endedAt', label: 'Ended', type: 'datetime-local', defaultValue: localInput(text(event.ended_at)) },
      { name: 'correctionReason', label: 'Why is it corrected?' },
    ],
    run: async (v, key) => {
      const result = await request(
        `/maintenance/downtime/${event.id}/correct`,
        {
          reason: v.reason,
          startedAt: isoFromLocal(v.startedAt),
          endedAt: isoFromLocal(v.endedAt),
          correctionReason: v.correctionReason,
          withdraw: v.action === 'withdraw',
        },
        key,
      );
      maintenanceChanged();
      return result;
    },
  });
}

/** Planner: correct a wrong machine status (never out of an active maintenance order). */
export function correctMachineStatus(workspace: Workspace, machine: Row) {
  const current = text(machine.status);
  workspace.openForm({
    title: `Correct the status of ${text(machine.code)}`,
    description:
      'Use this when the recorded status is wrong. Maintenance status is set and cleared by work orders only.',
    submit: 'Correct status',
    fields: [
      selectField(
        'status',
        'Actual status',
        [
          { value: 'IDLE', label: 'Idle' },
          { value: 'RUNNING', label: 'Running' },
        ].filter((o) => o.value !== current),
      ),
      { name: 'reason', label: 'Reason', hint: 'Kept in the machine history and the audit trail.' },
    ],
    run: async (v, key) => {
      const result = await request(`/maintenance/machines/${machine.id}/status`, v, key);
      maintenanceChanged();
      return result;
    },
  });
}

/** Run-hour meter reading of a machine (drives run-hour preventive plans). */
export function recordMeter(workspace: Workspace, machine: Row, last?: unknown) {
  workspace.openForm({
    title: `Meter reading · ${text(machine.code)}`,
    description: last
      ? `The last reading was ${text(last)} h. Readings never go backwards.`
      : 'The first reading starts the run-hour count of the machine’s plans.',
    submit: 'Record reading',
    fields: [
      { name: 'reading', label: 'Run hours', type: 'number', min: '0', step: '0.01' },
      { name: 'notes', label: 'Notes', optional: true },
    ],
    run: async (v, key) => {
      const result = await request(`/maintenance/machines/${machine.id}/meter`, v, key);
      maintenanceChanged();
      return result;
    },
  });
}
