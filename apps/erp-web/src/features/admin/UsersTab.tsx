import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { KeyRound, Plus } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Badge, Panel, selectField, Table } from '../../components';
import type { Row } from '../../lib/api';
import { request } from '../../lib/api';
import { fmt, text } from '../../lib/format';
import { Tabs } from '../../motion/motion';
import {
  dateTime,
  PASSWORD_MIN,
  ROLE_LABELS,
  ROLES,
  useAdminPaged,
  useDebounced,
  useInvalidateAdmin,
} from './api';
import { LoadMore, QueryState, SearchBox, UserStatus } from './shared';
import { UserDialog } from './UserDialog';

const STATUSES = [
  { value: 'all', label: 'All' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'INACTIVE', label: 'Inactive' },
];

/** Server-paged user directory with search, role and status filters; a row opens the user dialog. */
export function UsersTab() {
  const { openForm, user: me } = useWorkspace();
  const invalidate = useInvalidateAdmin();
  const [status, setStatus] = useState('all');
  const [role, setRole] = useState('');
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState<Row>();
  const q = useDebounced(search);
  const { query, rows, summary } = useAdminPaged('users', '/admin/users', {
    q,
    role,
    status: status === 'all' ? undefined : status,
    limit: 50,
  });
  function addUser() {
    openForm({
      title: 'Add team member',
      description: `They sign in with this initial password once and must choose their own (${PASSWORD_MIN}+ characters) before they can work. Share it through your approved secure channel.`,
      submit: 'Create account',
      fields: [
        { name: 'name', label: 'Full name' },
        { name: 'email', label: 'Work email', type: 'email' },
        {
          name: 'password',
          label: `Initial password (${PASSWORD_MIN}+ characters)`,
          type: 'password',
          hint: 'Stored only as a bcrypt hash.',
        },
        selectField(
          'role',
          'Role',
          ROLES.map((r) => ({ value: r, label: ROLE_LABELS[r] })),
        ),
      ],
      run: async (values, key) => {
        await request('/users', values, key);
        await invalidate();
      },
    });
  }
  return (
    <Panel
      title="Users"
      subtitle="Sign-in access, roles and password state for everyone in this workspace."
      className="admin-users"
      action={
        <button className="button primary small" onClick={addUser}>
          <Plus size={14} />
          Add team member
        </button>
      }
    >
      <div className="adm-toolbar">
        <div className="adm-filters">
          <SearchBox value={search} onChange={setSearch} placeholder="Search name or email…" />
          <select aria-label="Role" value={role} onChange={(e) => setRole(e.target.value)}>
            <option value="">Any role</option>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {ROLE_LABELS[r]}
              </option>
            ))}
          </select>
        </div>
        {summary && (
          <div className="adm-actions">
            <span className="adm-chip">
              <strong>{fmt(summary.active)}</strong> active
            </span>
            <span className="adm-chip">
              <strong>{fmt(summary.admins)}</strong> administrators
            </span>
            {!!summary.pending_password && (
              <span className="adm-chip">
                <KeyRound size={12} />
                <strong>{fmt(summary.pending_password)}</strong> password change pending
              </span>
            )}
          </div>
        )}
      </div>
      <Tabs compact label="User status" items={STATUSES} value={status} onChange={setStatus} />
      <QueryState pending={query.isPending} error={query.error} retry={() => query.refetch()}>
        <Table
          rows={rows}
          compact
          pageSize={Math.max(rows.length, 1)}
          onRow={(r) => setOpen(r)}
          columns={[
            {
              label: 'User',
              render: (r) => (
                <div className="cell-stack">
                  <strong>
                    {text(r.name)}
                    {r.id === me.id ? ' (you)' : ''}
                  </strong>
                  <span>{text(r.email)}</span>
                </div>
              ),
            },
            { label: 'Role', render: (r) => <Badge value={ROLE_LABELS[text(r.role)] ?? r.role} /> },
            { label: 'Status', render: (r) => <UserStatus user={r} /> },
            {
              label: 'Last sign-in',
              render: (r) =>
                r.last_login_at ? dateTime(r.last_login_at) : <span className="muted">Never</span>,
            },
          ]}
        />
        <LoadMore query={query} count={rows.length} />
      </QueryState>
      <AnimatePresence>
        {open && (
          <UserDialog
            key={open.id}
            user={open}
            onClose={() => setOpen(undefined)}
            onChanged={(next) => {
              setOpen(next);
              void invalidate();
            }}
          />
        )}
      </AnimatePresence>
    </Panel>
  );
}
