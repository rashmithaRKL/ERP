import { useState } from 'react';
import { Check, Copy, GitCompare, LoaderCircle, Pencil, Star } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import type { Row } from '../../lib/api';
import { date, fmt, humanizeText, num, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import { auditLabel } from '../audit/auditLabel';
import { nextRevision } from '../boms/api';
import { listOf, minutesText, rateText, useRoutingDetail, useRoutingRequest } from './api';
import type { MachineRef, RoutingDetail, RoutingOperation } from './api';
import { StatusChip } from './shared';

/**
 * Routing revision side drawer (V10): operations with work centers, alternates, skills and standard minutes;
 * the product's revisions and the document activity. Actions follow the approval rules: planners edit and approve
 * drafts (never their own), copy approved revisions into a new draft and choose the current revision.
 */
export function RoutingDrawer({
  routingId,
  onClose,
  onEdit,
  onOpen,
  onCompare,
}: {
  routingId: string;
  onClose: () => void;
  onEdit: (detail: RoutingDetail) => void;
  onOpen: (id: string) => void;
  onCompare: (from: string, to: string) => void;
}) {
  const { can, user, openForm, notify } = useWorkspace();
  const detail = useRoutingDetail(routingId);
  const save = useRoutingRequest();
  const [tab, setTab] = useState('operations');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const d = detail.data;
  const r = d?.routing;
  const planner = can('PLANNER');
  const author = !!r && (r.created_by === user.id || r.updated_by === user.id);

  async function act(url: string, done: string) {
    setBusy(true);
    setError('');
    try {
      await save(url, {});
      notify(done);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function copy() {
    if (!d || !r) return;
    openForm({
      title: `Copy ${text(r.revision)} into a new draft`,
      description: `${text(r.product)} · the draft starts with the same operations. Edit it, then ask another planner to approve it.`,
      fields: [
        {
          name: 'revision',
          label: 'New revision reference',
          defaultValue: nextRevision(d.revisions.map((x) => text(x.revision))),
          hint: 'Unique for this product, e.g. R02.',
        },
      ],
      submit: 'Create draft',
      run: async (values, key) => {
        const result = await save<Row>(`/routings/${r.id}/copy`, { revision: values.revision }, key);
        onOpen(text(result.id));
      },
    });
  }

  const other =
    d?.revisions.find((x) => x.id !== routingId && x.status === 'APPROVED') ||
    d?.revisions.find((x) => x.id !== routingId);
  return (
    <Overlay
      title={r ? `${text(r.product)} · ${text(r.revision)}` : 'Routing'}
      subtitle="Operations, work centers, skills and standard minutes"
      onClose={onClose}
      drawer
      wide
    >
      {detail.isPending ? (
        <div className="drawer-body">
          <LoaderCircle className="spin" />
        </div>
      ) : detail.isError || !d || !r ? (
        <div className="drawer-body">
          <div className="form-error" role="alert">
            {(detail.error as Error)?.message || 'This routing could not be loaded.'}
          </div>
        </div>
      ) : (
        <>
          <div className="drawer-body">
            <div className="item-drawer-meta">
              <StatusChip value={r.status} />
              {r.current && <span className="bom-chip level">current</span>}
              <span>
                {text(r.sku)} · {text(r.number)}
                {r.source_revision ? ` · copied from ${text(r.source_revision)}` : ''}
              </span>
            </div>
            <div className="routing-facts">
              <div>
                <span>Operations</span>
                <strong>{d.operations.length}</strong>
              </div>
              <div>
                <span>Setup per order</span>
                <strong>{minutesText(d.operations.reduce((s, o) => s + num(o.setup_minutes), 0))}</strong>
              </div>
              <div>
                <span>Run per 1,000 {text(r.uom)}</span>
                <strong>
                  {minutesText(d.operations.reduce((s, o) => s + num(o.run_minutes_per_unit), 0) * 1000)}
                </strong>
              </div>
              <div>
                <span>Orders using it</span>
                <strong>{fmt(d.orders)}</strong>
              </div>
            </div>
            {r.notes && <p className="routing-notes">{text(r.notes)}</p>}
            <div className="drawer-tabs">
              <Tabs
                compact
                label="Routing sections"
                value={tab}
                onChange={setTab}
                items={[
                  { value: 'operations', label: 'Operations', count: d.operations.length },
                  { value: 'revisions', label: 'Revisions', count: d.revisions.length },
                  { value: 'activity', label: 'Activity' },
                ]}
              />
            </div>
            {tab === 'operations' && <OperationsTable operations={d.operations} uom={text(r.uom)} />}
            {tab === 'revisions' && (
              <Table
                label="Revisions of this product"
                rows={d.revisions}
                compact
                onRow={(x) => x.id !== routingId && onOpen(x.id)}
                columns={[
                  {
                    label: 'Revision',
                    render: (x) => (
                      <span className="cell-stack">
                        <strong>{text(x.revision)}</strong>
                        <small>{text(x.number)}</small>
                      </span>
                    ),
                  },
                  {
                    label: 'Status',
                    render: (x) => (
                      <>
                        <StatusChip value={x.status} />{' '}
                        {x.current && <span className="bom-chip level">current</span>}
                      </>
                    ),
                  },
                  { label: 'Created', render: (x) => date(x.created_at) },
                  { label: 'Approved', render: (x) => date(x.approved_at) },
                ]}
              />
            )}
            {tab === 'activity' && (
              <div className="activity-feed">
                {d.events.map((e) => (
                  <div key={e.id}>
                    <span className="activity-dot" />
                    <div>
                      <strong>{auditLabel(e.action)}</strong>
                      <p>{humanizeText(e.detail)}</p>
                      <small>
                        {text(e.actor)} · {date(e.created_at)}
                      </small>
                    </div>
                  </div>
                ))}
              </div>
            )}
            {error && (
              <div className="form-error" role="alert">
                {error}
              </div>
            )}
          </div>
          <div className="dialog-footer detail-footer">
            {other && (
              <button className="button secondary" onClick={() => onCompare(other.id, routingId)}>
                <GitCompare size={15} />
                Compare
              </button>
            )}
            <span className="grow" />
            {planner && r.status === 'APPROVED' && !r.current && (
              <button
                className="button secondary"
                disabled={busy}
                onClick={() =>
                  act(`/routings/${r.id}/make-current`, `${text(r.revision)} is now the current routing.`)
                }
              >
                <Star size={15} />
                Make current
              </button>
            )}
            {planner && r.status === 'APPROVED' && (
              <button className="button secondary" disabled={busy} onClick={copy}>
                <Copy size={15} />
                Copy to new draft
              </button>
            )}
            {planner && r.status === 'DRAFT' && (
              <>
                <button className="button secondary" disabled={busy} onClick={() => onEdit(d)}>
                  <Pencil size={15} />
                  Edit draft
                </button>
                {author ? (
                  <span className="muted">You authored this draft, so another planner approves it.</span>
                ) : (
                  <button
                    className="button primary"
                    disabled={busy}
                    onClick={() =>
                      act(`/routings/${r.id}/approve`, `Routing ${text(r.revision)} approved successfully.`)
                    }
                  >
                    {busy ? <LoaderCircle className="spin" size={15} /> : <Check size={15} />}
                    Approve routing
                  </button>
                )}
              </>
            )}
          </div>
        </>
      )}
    </Overlay>
  );
}

/** The operations of a revision (drawer and comparison). */
export function OperationsTable({ operations, uom }: { operations: RoutingOperation[]; uom: string }) {
  return (
    <Table
      label="Routing operations"
      rows={operations}
      compact
      columns={[
        { label: 'Seq', key: 'seq' },
        {
          label: 'Operation',
          render: (o) => (
            <span className="cell-stack">
              <strong>
                {text(o.name)}
                {o.inspection_point && <span className="bom-chip warn inline-chip">inspection</span>}
              </strong>
              {o.instructions && <small className="notes-cell">{text(o.instructions)}</small>}
            </span>
          ),
        },
        {
          label: 'Work center',
          render: (o) => (
            <span className="cell-stack">
              <strong>{text(o.machine_code)}</strong>
              {listOf<MachineRef>(o, 'alternates').length > 0 && (
                <small>
                  or{' '}
                  {listOf<MachineRef>(o, 'alternates')
                    .map((m) => m.code)
                    .join(', ')}
                </small>
              )}
            </span>
          ),
        },
        { label: 'Setup', render: (o) => minutesText(o.setup_minutes) },
        {
          label: 'Run',
          render: (o) => (
            <span className="cell-stack">
              <strong>{fmt(o.run_minutes_per_unit, 6)} min</strong>
              <small>{rateText(o.run_minutes_per_unit, uom)}</small>
            </span>
          ),
        },
        { label: 'Crew', render: (o) => fmt(o.crew_size, 2) },
        {
          label: 'Skill',
          render: (o) =>
            o.skill_code
              ? `${text(o.skill_code)}${num(o.skill_level) > 1 ? ` L${text(o.skill_level)}+` : ''}`
              : '—',
        },
      ]}
    />
  );
}
