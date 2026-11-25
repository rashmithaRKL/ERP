import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { AnimatePresence } from 'motion/react';
import { GraduationCap, LoaderCircle, Plus, Search } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Empty, Overlay, Panel, Table } from '../../components';
import type { Row } from '../../lib/api';
import { date, fmt, text } from '../../lib/format';
import { PageFooter, PageState, useCursorPage } from '../lists/cursorPage';
import { listOf, useRoutingRequest, useSkills } from './api';

type Cell = { user: Row; skill: Row; held?: Row };

/**
 * Skills tab: the skill catalogue (code, name, holders, expiring certifications, routings requiring it) and the user
 * skill matrix (`GET /api/skills/users`, keyset-paged by name). Planners add skills and set a user's level and
 * certification expiry; operations that require a skill start only for qualified users.
 */
export function SkillsTab() {
  const { can, openForm, notify } = useWorkspace();
  const save = useRoutingRequest();
  const skills = useSkills();
  const [input, setInput] = useState('');
  const [q, setQ] = useState('');
  useEffect(() => {
    const timeout = setTimeout(() => setQ(input.trim()), 300);
    return () => clearTimeout(timeout);
  }, [input]);
  // The matrix lists people: ADMIN and PLANNER only (the API answers 403 to other roles).
  const paged = useCursorPage(['skills', 'users'], '/skills/users', { q }, 25, can('PLANNER'));
  const [cell, setCell] = useState<Cell>();
  const planner = can('PLANNER');
  const catalogue = skills.data?.skills || [];
  const active = catalogue.filter((s) => s.active);

  function newSkill() {
    openForm({
      title: 'New skill',
      description: 'Operations of a routing can require a skill and a minimum level.',
      fields: [
        { name: 'code', label: 'Code', hint: 'Letters, digits and hyphens, e.g. SBM.' },
        { name: 'name', label: 'Name' },
        { name: 'description', label: 'Description', type: 'textarea', optional: true },
      ],
      submit: 'Create skill',
      run: (v, key) => save('/skills', v, key).then(() => notify('Skill created successfully.')),
    });
  }

  function editSkill(skill: Row) {
    openForm({
      title: `Edit skill ${text(skill.code)}`,
      description: 'An inactive skill cannot be required by new routings or recorded for users.',
      fields: [
        { name: 'name', label: 'Name', defaultValue: text(skill.name) },
        {
          name: 'active',
          label: 'Status',
          options: [
            { value: 'true', label: 'Active' },
            { value: 'false', label: 'Inactive' },
          ],
          defaultValue: String(!!skill.active),
        },
        {
          name: 'description',
          label: 'Description',
          type: 'textarea',
          optional: true,
          defaultValue: text(skill.description),
        },
      ],
      run: (v, key) =>
        save(
          `/skills/${skill.id}`,
          { name: v.name, description: v.description, active: v.active === 'true' },
          key,
        ),
    });
  }

  return (
    <>
      <Panel
        title="Skill catalogue"
        subtitle="Qualifications that routing operations can require"
        action={
          planner && (
            <button className="button secondary small" onClick={newSkill}>
              <Plus size={14} />
              New skill
            </button>
          )
        }
      >
        {skills.isPending ? (
          <div className="server-loading">
            <div className="skeleton" />
          </div>
        ) : skills.isError ? (
          <div className="form-error server-error" role="alert">
            {(skills.error as Error).message}
          </div>
        ) : (
          <Table
            label="Skills"
            compact
            rows={catalogue}
            onRow={planner ? editSkill : undefined}
            columns={[
              {
                label: 'Skill',
                render: (s) => (
                  <span className="cell-stack">
                    <strong>{text(s.code)}</strong>
                    <small>{text(s.name)}</small>
                  </span>
                ),
              },
              {
                label: 'Description',
                render: (s) => <span className="notes-cell">{text(s.description) || '—'}</span>,
              },
              { label: 'Holders', render: (s) => fmt(s.holders) },
              {
                label: 'Certifications',
                render: (s) =>
                  Number(s.expired) || Number(s.expiring) ? (
                    <span className="cell-stack">
                      {Number(s.expired) > 0 && (
                        <strong className="negative">{fmt(s.expired)} expired</strong>
                      )}
                      {Number(s.expiring) > 0 && <small>{fmt(s.expiring)} expire within 30 days</small>}
                    </span>
                  ) : (
                    <span className="muted">Up to date</span>
                  ),
              },
              { label: 'Routings', render: (s) => fmt(s.routings) },
              {
                label: 'Status',
                render: (s) => (
                  <span className={`badge ${s.active ? 'green' : 'gray'}`}>
                    <i />
                    {s.active ? 'active' : 'inactive'}
                  </span>
                ),
              },
            ]}
          />
        )}
      </Panel>
      {planner && (
        <Panel
          className="server-table skill-matrix"
          title="Skill matrix"
          subtitle="Levels 1 (trainee) to 5 (expert) with certification expiry"
        >
          <div className="server-toolbar">
            <div className="search-input">
              <Search size={16} />
              <input
                aria-label="Search people"
                placeholder="Search name or role…"
                value={input}
                onChange={(e) => setInput(e.target.value)}
              />
            </div>
          </div>
          <PageState paged={paged}>
            {!active.length ? (
              <Empty title="No active skills" body="Add skills to the catalogue to record who holds them." />
            ) : (
              <div className="week-scroll" tabIndex={0} role="region" aria-label="User skill matrix">
                <table className="week-table matrix-table">
                  <thead>
                    <tr>
                      <th scope="col">Person</th>
                      {active.map((s) => (
                        <th scope="col" key={s.id} title={text(s.name)}>
                          {text(s.code)}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {paged.rows.map((u) => {
                      const held = new Map(listOf<Row>(u, 'skills').map((x) => [text(x.skill_id), x]));
                      return (
                        <tr key={u.id}>
                          <th scope="row">
                            <span className="cell-stack">
                              <strong>{text(u.name)}</strong>
                              <small>{text(u.role).toLowerCase()}</small>
                            </span>
                          </th>
                          {active.map((s) => {
                            const h = held.get(s.id);
                            return (
                              <td key={s.id}>
                                <button
                                  className={`skill-cell ${h ? (h.expired ? 'expired' : 'held') : ''}`}
                                  aria-label={`${text(u.name)} · ${text(s.code)}: ${
                                    h ? `level ${text(h.level)}${h.expired ? ', expired' : ''}` : 'not held'
                                  }`}
                                  onClick={() => setCell({ user: u, skill: s, held: h })}
                                >
                                  {h ? (
                                    <>
                                      <strong>L{text(h.level)}</strong>
                                      <small>
                                        {h.certified_until
                                          ? h.expired
                                            ? 'expired'
                                            : date(h.certified_until)
                                          : 'no expiry'}
                                      </small>
                                    </>
                                  ) : (
                                    <span className="muted">—</span>
                                  )}
                                </button>
                              </td>
                            );
                          })}
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </PageState>
          <PageFooter paged={paged} label="people" />
        </Panel>
      )}
      <AnimatePresence>
        {cell && <UserSkillDialog cell={cell} onClose={() => setCell(undefined)} />}
      </AnimatePresence>
    </>
  );
}

/** Set (or remove) one user's level and certification expiry for one skill. */
function UserSkillDialog({ cell, onClose }: { cell: Cell; onClose: () => void }) {
  const { notify } = useWorkspace();
  const save = useRoutingRequest();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const key = useRef(crypto.randomUUID());
  const { user, skill, held } = cell;

  async function run(url: string, body: unknown, message: string) {
    setPending(true);
    setError('');
    try {
      await save(url, body, key.current);
      notify(message);
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPending(false);
    }
  }

  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const v = Object.fromEntries(new FormData(e.currentTarget)) as Record<string, string>;
    void run(
      '/skills/assignments',
      {
        userId: user.id,
        skillId: skill.id,
        level: Number(v.level),
        certifiedUntil: v.certifiedUntil || null,
        notes: v.notes,
      },
      'Skill recorded successfully.',
    );
  }

  return (
    <Overlay
      title={`${text(user.name)} · ${text(skill.code)}`}
      subtitle={`${text(skill.name)}. The certification is valid through its expiry date.`}
      onClose={() => !pending && onClose()}
    >
      <form onSubmit={submit}>
        <div className="form-fields">
          <label className="field">
            <span>Level</span>
            <select name="level" defaultValue={text(held?.level || 1)}>
              {['1 · trainee', '2 · qualified', '3 · experienced', '4 · senior', '5 · expert'].map((l, i) => (
                <option key={l} value={i + 1}>
                  Level {l}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>
              Certified until<small>Optional</small>
            </span>
            <input
              name="certifiedUntil"
              type="date"
              defaultValue={text(held?.certified_until).slice(0, 10)}
            />
          </label>
          <label className="field full">
            <span>
              Notes<small>Optional</small>
            </span>
            <textarea name="notes" rows={2} maxLength={400} defaultValue={text(held?.notes)} />
          </label>
          {error && (
            <div role="alert" className="form-error">
              {error}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          {held && (
            <button
              type="button"
              className="button danger-ghost"
              disabled={pending}
              onClick={() =>
                run(
                  '/skills/assignments/remove',
                  { userId: user.id, skillId: skill.id },
                  'Skill removed successfully.',
                )
              }
            >
              Remove skill
            </button>
          )}
          <span className="grow" />
          <button type="button" className="button secondary" disabled={pending} onClick={onClose}>
            Cancel
          </button>
          <button className="button primary" disabled={pending}>
            {pending ? <LoaderCircle className="spin" size={15} /> : <GraduationCap size={15} />}
            Save skill
          </button>
        </div>
      </form>
    </Overlay>
  );
}
