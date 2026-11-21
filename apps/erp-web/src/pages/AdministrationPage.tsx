import { ArrowUpRight, Factory, Plus, Wifi } from 'lucide-react';
import { useStateData, useWorkspace } from '../app/workspace';
import { Badge, Panel, selectField, Table } from '../components';
import { auditLabel } from '../features/audit/auditLabel';
import { date, fmt, labelState, text } from '../lib/format';
import { MotionSettings } from '../motion/motion';

/** Route `/settings`: organization, connectivity, motion preference, team and audit trail. */
export default function AdministrationPage() {
  const { can, postForm, user } = useWorkspace();
  const data = useStateData();
  return (
    <>
      <div className="settings-grid">
        <Panel
          title="Organization"
          subtitle="Current operational workspace"
          action={
            can() && (
              <button
                className="text-button"
                onClick={() =>
                  postForm('Organization settings', '/organization', [
                    {
                      name: 'name',
                      label: 'Organization name',
                      defaultValue: text(data.company.name),
                    },
                    {
                      name: 'factoryName',
                      label: 'Factory name',
                      defaultValue: text(data.factories[0]?.name),
                    },
                    {
                      name: 'location',
                      label: 'Factory location',
                      defaultValue: text(data.factories[0]?.location),
                    },
                  ])
                }
              >
                Edit settings
                <ArrowUpRight size={13} />
              </button>
            )
          }
        >
          <div className="settings-body">
            <div className="organization-card">
              <span className="workspace-logo large">
                <Factory size={24} />
              </span>
              <div>
                <strong>{text(data.company.name)}</strong>
                <p>Plastics & packaging manufacturing</p>
                <Badge value={data.company.demo ? 'DEMO' : 'PRODUCTION'} />
              </div>
            </div>
            <dl>
              <dt>Factory</dt>
              <dd>{text(data.factories[0]?.name)}</dd>
              <dt>Location</dt>
              <dd>{text(data.factories[0]?.location)}</dd>
              <dt>Base currency</dt>
              <dd>{text(data.company.currency)} · Sri Lankan rupee</dd>
              <dt>Deployment</dt>
              <dd>Local server · PostgreSQL</dd>
              <dt>Current role</dt>
              <dd>{labelState(user.role)}</dd>
            </dl>
          </div>
        </Panel>
        <Panel
          title="Synchronization & connectivity"
          subtitle="Local transactions are durable; cloud synchronization is not configured"
        >
          <div className="settings-body">
            <div className="sync-state">
              <Wifi size={22} />
              <div>
                <strong>Local server connected</strong>
                <span>Cloud synchronization: not configured</span>
              </div>
            </div>
            <dl>
              <dt>Durable local events</dt>
              <dd>{fmt(data.sync.local_events)}</dd>
              <dt>Cloud acknowledgements</dt>
              <dd>0</dd>
              <dt>Last cloud sync</dt>
              <dd>Not connected</dd>
              <dt>Authoritative writer</dt>
              <dd>This local deployment</dd>
            </dl>
            <p className="settings-note">
              Internet-free operations require this server and database to remain available. Browser-only
              offline posting and cloud reconciliation are future milestones.
            </p>
          </div>
        </Panel>
      </div>
      <MotionSettings />
      {can() && (
        <Panel
          title="Workspace team"
          subtitle="Access is granted through backend roles."
          className="spaced-panel"
          action={
            <button
              className="button secondary small"
              onClick={() =>
                postForm(
                  'Add team member',
                  '/users',
                  [
                    { name: 'name', label: 'Full name' },
                    { name: 'email', label: 'Work email', type: 'email' },
                    {
                      name: 'password',
                      label: 'Initial password (16+ characters)',
                      type: 'password',
                    },
                    selectField(
                      'role',
                      'Role',
                      ['ADMIN', 'PLANNER', 'WAREHOUSE', 'OPERATOR', 'QUALITY', 'SALES', 'VIEWER'].map(
                        (x) => ({ value: x, label: labelState(x) }),
                      ),
                    ),
                  ],
                  'Share credentials through your approved secure channel. Passwords are stored as bcrypt hashes.',
                  'Create account',
                )
              }
            >
              <Plus size={14} />
              Add team member
            </button>
          }
        >
          <Table
            rows={data.users || []}
            columns={[
              { label: 'Name', key: 'name' },
              { label: 'Email', key: 'email' },
              { label: 'Role', render: (r) => <Badge value={r.role} /> },
              {
                label: 'Status',
                render: (r) => <Badge value={r.active ? 'ACTIVE' : 'INACTIVE'} />,
              },
            ]}
          />
        </Panel>
      )}
      <Panel
        title="Audit trail"
        subtitle="Who changed what, and why. Posted history is immutable."
        className="spaced-panel"
      >
        <Table
          rows={data.audit}
          search
          columns={[
            {
              label: 'Action',
              render: (r) => <span className="audit-action">{auditLabel(r.action)}</span>,
            },
            { label: 'Document', key: 'reference' },
            { label: 'Actor', key: 'actor' },
            {
              label: 'Detail',
              render: (r) => (
                <span className="notes-cell" title={text(r.detail)}>
                  {text(r.detail)}
                </span>
              ),
            },
            { label: 'Date', render: (r) => date(r.created_at) },
          ]}
        />
      </Panel>
    </>
  );
}
