import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import {
  ArrowUpRight,
  CalendarDays,
  CheckCheck,
  ClipboardCheck,
  Flag,
  Pause,
  Play,
  Plus,
  Timer,
  Wrench,
} from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import type { Workspace } from '../../app/workspace';
import { Badge, Empty, Panel, Progress } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, fmt, num, text } from '../../lib/format';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { invalidateRoutingData } from './api';
import { PlannedActual, SkillHint, StatusChip, useElapsedMinutes } from './shared';

const PAGE = 12;

type Act = (url: string, body: unknown, done: string) => Promise<void>;

/**
 * The shop-floor queue of one work center (`GET /api/operations/queue`): running, paused and ready operations by order
 * priority and due date, with start/pause/resume/setup-done/report/finish actions for operators and planners.
 */
export function OperationQueue({ machine }: { machine: Row }) {
  const workspace = useWorkspace();
  const { refresh, notify } = workspace;
  const qc = useQueryClient();
  const paged = useCursorPage(
    ['operations', 'queue', machine.id],
    '/operations/queue',
    { machineId: machine.id },
    PAGE,
  );
  const [busy, setBusy] = useState(false);
  const act: Act = async (url, body, done) => {
    if (busy) return;
    setBusy(true);
    try {
      await request(url, body);
      await Promise.all([invalidateRoutingData(qc), refresh()]);
      notify(done);
    } catch (e) {
      notify((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <PageState paged={paged}>
        {paged.rows.length ? (
          <div className="job-grid">
            {paged.rows.map((op) => (
              <OperationCard
                key={op.id}
                op={op}
                machine={machine}
                busy={busy}
                act={act}
                workspace={workspace}
                qc={qc}
              />
            ))}
          </div>
        ) : (
          <Panel>
            <Empty
              title={`Nothing queued on ${text(machine.code)}`}
              body="Operations appear here once their order is released and the previous operation is finished."
            />
          </Panel>
        )}
      </PageState>
      {(paged.hasNext || paged.hasPrevious) && <PageFooter paged={paged} label="operations" />}
    </>
  );
}

function OperationCard({
  op,
  machine,
  busy,
  act,
  workspace,
  qc,
}: {
  op: Row;
  machine: Row;
  busy: boolean;
  act: Act;
  workspace: Workspace;
  qc: QueryClient;
}) {
  const { can, openForm, openOrder } = workspace;
  const elapsed = useElapsedMinutes(op.open_since);
  const actual = num(op.actual_setup_minutes) + num(op.actual_run_minutes) + (elapsed || 0);
  const input = num(op.input_qty);
  const reported = num(op.good_qty) + num(op.scrap_qty);
  const uom = text(op.uom) || 'pcs';
  const runner = can('OPERATOR', 'PLANNER');
  const supervisor = can('PLANNER');
  const skillOk = !op.skill_id || op.skill_check === 'QUALIFIED';
  const onHold = op.order_status === 'ON_HOLD';
  const label = `operation ${text(op.seq)} of ${text(op.order_number)}`;
  const url = (action: string) => `/operations/${op.id}/${action}`;
  const setupFirst = num(op.setup_minutes) > 0 && num(op.actual_setup_minutes) === 0;

  /** Start or resume; a supervisor without the skill gives an override reason, an operator cannot start. */
  function begin(action: 'start' | 'resume', activity?: 'RUN') {
    const body =
      action === 'start'
        ? { machineId: op.alternate ? machine.id : null, activity: activity ?? null }
        : { activity: activity ?? null };
    const done =
      action === 'start' ? `Operation ${text(op.seq)} started.` : `Operation ${text(op.seq)} resumed.`;
    if (skillOk) return act(url(action), { ...body, overrideReason: null }, done);
    openForm({
      title: `Override the ${text(op.skill_code)} requirement`,
      description: `${text(op.name)} requires ${text(op.skill_name)} level ${text(op.skill_level)}. Your override and reason are recorded on the time log and in the audit trail.`,
      fields: [{ name: 'overrideReason', label: 'Reason for the override', type: 'textarea' }],
      submit: action === 'start' ? 'Start with override' : 'Resume with override',
      run: async (v, key) => {
        await request(url(action), { ...body, overrideReason: v.overrideReason }, key);
        await invalidateRoutingData(qc);
      },
    });
  }

  function quantities(action: 'report' | 'finish') {
    const remaining = Math.max(0, input - reported);
    openForm({
      title: action === 'finish' ? `Finish operation ${text(op.seq)}` : `Report operation ${text(op.seq)}`,
      description:
        action === 'finish'
          ? `${text(op.name)} · ${fmt(reported)} of ${fmt(input)} ${uom} already reported. Finishing closes the time log and readies the next operation.`
          : `${text(op.name)} · ${fmt(remaining)} ${uom} still to process. Stock and costs post when the order's output is recorded.`,
      fields: [
        {
          name: 'goodQty',
          label: `Good quantity (${uom})`,
          type: 'number',
          min: '0',
          defaultValue: action === 'finish' ? String(remaining) : '',
        },
        { name: 'scrapQty', label: `Scrap quantity (${uom})`, type: 'number', min: '0', defaultValue: '0' },
        { name: 'notes', label: 'Notes / scrap reason', type: 'textarea', optional: true, defaultValue: '' },
      ],
      submit: action === 'finish' ? 'Finish operation' : 'Report quantity',
      run: async (v, key) => {
        await request(
          url(action),
          { goodQty: v.goodQty || '0', scrapQty: v.scrapQty || '0', notes: v.notes },
          key,
        );
        await invalidateRoutingData(qc);
      },
    });
  }

  return (
    <Panel className="job-card op-card">
      <div className="job-top">
        <span>
          <Flag size={15} />
          Step {text(op.operation_position || op.seq)} of {text(op.operation_count)} · Op {text(op.seq)}
          {op.alternate && <span className="bom-chip inline-chip">alternate</span>}
        </span>
        <span className="op-status">
          {op.status === 'IN_PROGRESS' && (
            <span className={`bom-chip ${op.activity === 'SETUP' ? 'warn' : 'level'}`}>
              {op.activity === 'SETUP' ? <Wrench size={11} /> : <Play size={11} />}
              {text(op.activity).toLowerCase()}
            </span>
          )}
          <StatusChip value={op.status} />
        </span>
      </div>
      <h2>{text(op.name)}</h2>
      <button
        className="text-button"
        onClick={() => openOrder(text(op.order_id))}
        aria-label={`Open ${text(op.order_number)}`}
      >
        {text(op.order_number)} · {text(op.product)}
        <ArrowUpRight size={13} />
      </button>
      <div className="job-quantity">
        <strong>{fmt(op.good_qty)}</strong>
        <span>
          / {fmt(input)} {uom}
        </span>
      </div>
      <Progress value={input ? (reported / input) * 100 : 0} />
      <div className="job-footer">
        <span>
          <Timer size={14} />
          <PlannedActual planned={op.planned_minutes} actual={actual} />
        </span>
        <span>{fmt(op.scrap_qty)} scrap</span>
      </div>
      <div className="op-meta">
        <span>
          <CalendarDays size={13} />
          Due {date(op.due_date)}
        </span>
        <Badge value={op.priority} />
        <SkillHint row={op} />
        {op.inspection_point && (
          <span className="bom-chip warn">
            <ClipboardCheck size={11} />
            inspection point
          </span>
        )}
        {op.operator_name && op.status !== 'READY' && <span className="muted">{text(op.operator_name)}</span>}
      </div>
      {op.instructions && <p className="op-instructions">{text(op.instructions)}</p>}
      <div className="job-actions">
        {!runner ? (
          <span className="muted">View-only for your role</span>
        ) : onHold ? (
          <span className="muted">Order on hold · resume the order first</span>
        ) : machine.status === 'MAINTENANCE' && op.status === 'READY' ? (
          <span className="muted">{text(machine.code)} is under maintenance</span>
        ) : op.status === 'READY' ? (
          !skillOk && !supervisor ? (
            <span className="muted">A qualified operator must start this operation</span>
          ) : (
            <>
              <button
                className="button primary"
                disabled={busy}
                aria-label={`Start ${label}`}
                onClick={() => begin('start')}
              >
                {setupFirst ? <Wrench size={17} /> : <Play size={17} />}
                {setupFirst ? 'Start setup' : 'Start'}
                {op.alternate ? ` on ${text(machine.code)}` : ''}
              </button>
              {setupFirst && (
                <button
                  className="button secondary"
                  disabled={busy}
                  aria-label={`Start ${label} without setup`}
                  onClick={() => begin('start', 'RUN')}
                >
                  Skip setup
                </button>
              )}
            </>
          )
        ) : op.status === 'IN_PROGRESS' ? (
          <>
            {op.activity === 'SETUP' ? (
              <button
                className="button primary"
                disabled={busy}
                onClick={() =>
                  act(url('setup-done'), {}, `Setup done; operation ${text(op.seq)} is running.`)
                }
              >
                <CheckCheck size={17} />
                Setup done
              </button>
            ) : (
              <button className="button primary" disabled={busy} onClick={() => quantities('report')}>
                <Plus size={17} />
                Report
              </button>
            )}
            <button className="button secondary" disabled={busy} onClick={() => quantities('finish')}>
              Finish
            </button>
            <button
              className="button secondary"
              aria-label={`Pause ${label}`}
              disabled={busy}
              onClick={() => act(url('pause'), { reason: '' }, `Operation ${text(op.seq)} paused.`)}
            >
              <Pause size={17} />
            </button>
          </>
        ) : op.status === 'PAUSED' ? (
          !skillOk && !supervisor ? (
            <span className="muted">A qualified operator must resume this operation</span>
          ) : (
            <>
              <button
                className="button primary"
                disabled={busy || machine.status === 'MAINTENANCE'}
                title={
                  machine.status === 'MAINTENANCE' ? `${text(machine.code)} is under maintenance` : undefined
                }
                onClick={() => begin('resume')}
              >
                <Play size={17} />
                Resume
              </button>
              <button className="button secondary" disabled={busy} onClick={() => quantities('finish')}>
                Finish
              </button>
            </>
          )
        ) : null}
      </div>
    </Panel>
  );
}
