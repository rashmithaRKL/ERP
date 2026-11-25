import { Check, X } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay, Table } from '../../components';
import { request } from '../../lib/api';
import type { Row } from '../../lib/api';
import { date, labelState, text, today } from '../../lib/format';
import { ActivityFeed, dateTime, Lifecycle, QueryState } from '../inventory/shared';
import { DECIDERS, ROOT_CAUSES, useQualityQuery } from './api';
import type { CapaDetail } from './api';
import { QualityBadge } from './shared';

const STEPS = ['Open', 'In progress', 'Verification', 'Closed'];
const INDEX: Record<string, number> = { OPEN: 0, IN_PROGRESS: 1, VERIFICATION: 2, CLOSED: 3 };

/**
 * One CAPA: problem, root-cause analysis (category and five whys), actions with owners and due dates, lifecycle
 * (start → verification → independent effectiveness check) and history.
 */
export function CapaDrawer({
  id,
  people,
  onClose,
  onOpenNcr,
}: {
  id: string;
  people: Row[];
  onClose: () => void;
  onOpenNcr: (id: string) => void;
}) {
  const { can, user, openForm } = useWorkspace();
  const detail = useQualityQuery<CapaDetail>('capa', `/quality/capas/${id}`);
  const c = detail.data?.capa;
  const actions = detail.data?.actions ?? [];
  const status = text(c?.status);
  const decider = can(...DECIDERS);
  const owner = c?.owner_id === user.id;
  const live = ['OPEN', 'IN_PROGRESS'].includes(status);
  const whys = c ? [c.why_1, c.why_2, c.why_3, c.why_4, c.why_5].map(text) : [];
  const ownerOptions = people.map((p) => ({ value: p.id, label: `${text(p.name)} · ${labelState(p.role)}` }));

  function analysis() {
    if (!c) return;
    openForm({
      title: `Root-cause analysis · ${text(c.number)}`,
      description: 'Ask “why?” until you reach a cause you can act on. Leave unused whys empty.',
      submit: 'Save analysis',
      fields: [
        { name: 'title', label: 'Title', defaultValue: text(c.title) },
        { name: 'problem', label: 'Problem statement', type: 'textarea', defaultValue: text(c.problem) },
        { name: 'ownerId', label: 'Owner', options: ownerOptions, defaultValue: text(c.owner_id) },
        {
          name: 'dueDate',
          label: 'Target date',
          type: 'date',
          optional: true,
          defaultValue: text(c.due_date),
        },
        {
          name: 'rootCauseCategory',
          label: 'Root-cause category',
          options: ROOT_CAUSES.map((r) => ({ value: r, label: labelState(r) })),
          defaultValue: text(c.root_cause_category),
        },
        ...whys.map((w, i) => ({
          name: `why${i + 1}`,
          label: `Why ${i + 1}`,
          optional: true,
          defaultValue: w,
        })),
        { name: 'rootCause', label: 'Root cause', type: 'textarea', defaultValue: text(c.root_cause) },
      ],
      run: (v, key) =>
        request(
          `/quality/capas/${id}`,
          {
            title: v.title,
            problem: v.problem,
            ownerId: v.ownerId,
            dueDate: v.dueDate || null,
            rootCauseCategory: v.rootCauseCategory || null,
            whys: [v.why1, v.why2, v.why3, v.why4, v.why5].map((w) => w || ''),
            rootCause: v.rootCause,
            ncrIds: detail.data!.ncrs.map((n) => n.id),
          },
          key,
        ),
    });
  }
  function addAction() {
    openForm({
      title: `Add action · ${text(c?.number)}`,
      submit: 'Add action',
      fields: [
        {
          name: 'type',
          label: 'Type',
          options: [
            { value: 'CORRECTIVE', label: 'Corrective — remove the cause of this problem' },
            { value: 'PREVENTIVE', label: 'Preventive — stop it happening elsewhere' },
          ],
          defaultValue: 'CORRECTIVE',
        },
        { name: 'description', label: 'Action', type: 'textarea' },
        { name: 'ownerId', label: 'Owner', options: ownerOptions },
        { name: 'dueDate', label: 'Due date', type: 'date', defaultValue: today() },
      ],
      run: (v, key) => request(`/quality/capas/${id}/actions`, v, key),
    });
  }
  function note(title: string, url: string, submit: string, label = 'Note', description?: string) {
    openForm({
      title,
      description,
      submit,
      fields: [{ name: 'note', label, type: 'textarea' }],
      run: (v, key) => request(url, v, key),
    });
  }
  function verify() {
    openForm({
      title: `Verify effectiveness · ${text(c?.number)}`,
      description:
        'Check the evidence that the problem has not recurred. Effective closes the CAPA; not effective returns it to work.',
      submit: 'Record verification',
      fields: [
        {
          name: 'effective',
          label: 'Result',
          options: [
            { value: 'true', label: 'Effective — close the CAPA' },
            { value: 'false', label: 'Not effective — more action needed' },
          ],
        },
        { name: 'note', label: 'Evidence', type: 'textarea' },
      ],
      run: (v, key) =>
        request(`/quality/capas/${id}/verify`, { effective: v.effective === 'true', note: v.note }, key),
    });
  }

  return (
    <Overlay
      title={text(c?.number) || 'CAPA'}
      subtitle="Corrective and preventive action: root cause, actions and effectiveness"
      onClose={onClose}
      drawer
      wide
    >
      <QueryState pending={detail.isPending} error={detail.error} retry={() => detail.refetch()}>
        {detail.data && c && (
          <>
            <div className="drawer-body">
              <div className="detail-product">
                <div>
                  <h2>{text(c.title)}</h2>
                  <p>
                    {labelState(c.source)} · opened by {text(c.created_by_name)} · {dateTime(c.created_at)}
                  </p>
                </div>
                <span className="qa-badges">
                  <QualityBadge value={c.status} />
                  {c.overdue && <QualityBadge value="OVERDUE" label="Past target" />}
                </span>
              </div>
              {status === 'CANCELLED' ? (
                <p className="inv-small muted">Cancelled · {text(c.cancel_reason)}</p>
              ) : (
                <Lifecycle steps={STEPS} index={INDEX[status] ?? 0} />
              )}
              <div className="detail-stats">
                <div>
                  <span>Owner</span>
                  <strong className="inv-stat-text">{text(c.owner)}</strong>
                </div>
                <div>
                  <span>Actions done</span>
                  <strong>
                    {actions.filter((a) => a.status === 'DONE').length} /{' '}
                    {actions.filter((a) => a.status !== 'CANCELLED').length}
                  </strong>
                </div>
                <div>
                  <span>Overdue actions</span>
                  <strong className={actions.some((a) => a.overdue) ? 'negative' : undefined}>
                    {actions.filter((a) => a.overdue).length}
                  </strong>
                </div>
                <div>
                  <span>Target date</span>
                  <strong className="inv-stat-text">{c.due_date ? date(c.due_date) : '—'}</strong>
                </div>
              </div>
              <p className="inv-notes">{text(c.problem)}</p>
              {detail.data.ncrs.length > 0 && (
                <ul className="qa-link-list">
                  {detail.data.ncrs.map((n) => (
                    <li key={n.id}>
                      <button className="text-button" onClick={() => onOpenNcr(n.id)}>
                        {text(n.number)}
                      </button>
                      <span>{text(n.title)}</span>
                      <QualityBadge value={n.severity} />
                    </li>
                  ))}
                </ul>
              )}
              <h3>Root cause</h3>
              {c.root_cause_category || text(c.root_cause) ? (
                <div className="qa-rca">
                  <p>
                    <strong>Category:</strong> {labelState(c.root_cause_category) || 'not set'}
                  </p>
                  <ol>
                    {whys
                      .filter((w) => w.trim())
                      .map((w, i) => (
                        <li key={i}>{w}</li>
                      ))}
                  </ol>
                  {text(c.root_cause) && (
                    <p>
                      <strong>Root cause:</strong> {text(c.root_cause)}
                    </p>
                  )}
                </div>
              ) : (
                <p className="inv-small muted">No root-cause analysis recorded yet.</p>
              )}
              <h3>Actions</h3>
              {actions.length ? (
                <Table
                  compact
                  pageSize={actions.length}
                  rows={actions}
                  label="CAPA actions"
                  columns={[
                    { label: '#', key: 'seq' },
                    {
                      label: 'Action',
                      render: (a) => (
                        <div className="cell-stack">
                          <strong>{labelState(a.type)}</strong>
                          <span className="notes-cell">{text(a.description)}</span>
                        </div>
                      ),
                    },
                    { label: 'Owner', key: 'owner' },
                    {
                      label: 'Due',
                      render: (a) => (
                        <span className={a.overdue ? 'negative' : undefined}>{date(a.due_date)}</span>
                      ),
                    },
                    {
                      label: 'Status',
                      render: (a) => (
                        <div className="cell-stack">
                          {a.overdue ? (
                            <QualityBadge value="OVERDUE" label="Overdue" />
                          ) : (
                            <QualityBadge value={a.status} />
                          )}
                          {text(a.completion_note) && (
                            <span className="notes-cell">{text(a.completion_note)}</span>
                          )}
                        </div>
                      ),
                    },
                    {
                      label: '',
                      render: (a) =>
                        a.status === 'OPEN' && live ? (
                          <span className="qa-row-actions">
                            {(decider || a.owner_id === user.id) && (
                              <button
                                className="button small secondary"
                                onClick={() =>
                                  note(
                                    `Complete action ${text(a.seq)}`,
                                    `/quality/capa-actions/${a.id}/complete`,
                                    'Mark done',
                                    'What was done',
                                  )
                                }
                              >
                                <Check size={13} /> Done
                              </button>
                            )}
                            {decider && (
                              <button
                                className="icon-button"
                                aria-label={`Cancel action ${text(a.seq)}`}
                                onClick={() =>
                                  note(
                                    `Cancel action ${text(a.seq)}`,
                                    `/quality/capa-actions/${a.id}/cancel`,
                                    'Cancel action',
                                    'Reason',
                                  )
                                }
                              >
                                <X size={14} />
                              </button>
                            )}
                          </span>
                        ) : (
                          ''
                        ),
                    },
                  ]}
                />
              ) : (
                <p className="inv-small muted">No actions planned yet.</p>
              )}
              {(c.verified_by_name || text(c.verification_note)) && (
                <p className="inv-small">
                  <strong>Verification:</strong> {c.effective ? 'effective' : 'not effective'} ·{' '}
                  {text(c.verification_note)}
                  {c.verified_by_name ? ` · ${text(c.verified_by_name)}, ${dateTime(c.verified_at)}` : ''}
                </p>
              )}
              <h3>Activity</h3>
              <ActivityFeed events={detail.data.activity} />
            </div>
            {(decider || owner) && !['CLOSED', 'CANCELLED'].includes(status) && (
              <div className="dialog-footer detail-footer">
                {decider && (
                  <button
                    className="button danger-ghost"
                    onClick={() =>
                      note(`Cancel ${text(c.number)}`, `/quality/capas/${id}/cancel`, 'Cancel CAPA', 'Reason')
                    }
                  >
                    Cancel CAPA
                  </button>
                )}
                {decider && live && (
                  <button className="button secondary" onClick={analysis}>
                    Root cause
                  </button>
                )}
                {decider && live && (
                  <button className="button secondary" onClick={addAction}>
                    Add action
                  </button>
                )}
                <span className="grow" />
                {status === 'OPEN' && decider && (
                  <button
                    className="button primary"
                    onClick={() =>
                      note(
                        `Start ${text(c.number)}`,
                        `/quality/capas/${id}/start`,
                        'Start work',
                        'Note',
                        'Needs the root-cause category and analysis and at least one action.',
                      )
                    }
                  >
                    Start
                  </button>
                )}
                {status === 'IN_PROGRESS' && (
                  <button
                    className="button primary"
                    onClick={() =>
                      note(
                        `Send ${text(c.number)} to verification`,
                        `/quality/capas/${id}/submit`,
                        'Send to verification',
                        'Note',
                        'Every action must be done or cancelled.',
                      )
                    }
                  >
                    Send to verification
                  </button>
                )}
                {status === 'VERIFICATION' &&
                  (decider && !owner ? (
                    <button className="button primary" onClick={verify}>
                      Verify effectiveness
                    </button>
                  ) : (
                    <span className="muted inv-small">
                      Verification by a different person than the owner.
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
