import { lazy, Suspense } from 'react';
import { LoaderCircle } from 'lucide-react';
import { useTabParam } from '../app/urlState';
import { useWorkspace } from '../app/workspace';
import '../features/admin/admin.css';
import { OrganizationTab } from '../features/admin/OrganizationTab';
import { Tabs } from '../motion/motion';

// Administrator-only sections load on demand, so other roles never download them.
const UsersTab = lazy(() => import('../features/admin/UsersTab').then((m) => ({ default: m.UsersTab })));
const PoliciesTab = lazy(() =>
  import('../features/admin/PoliciesTab').then((m) => ({ default: m.PoliciesTab })),
);
const ImportsTab = lazy(() =>
  import('../features/admin/ImportsTab').then((m) => ({ default: m.ImportsTab })),
);
const AuditTab = lazy(() => import('../features/admin/AuditTab').then((m) => ({ default: m.AuditTab })));
const PeriodsTab = lazy(() =>
  import('../features/admin/PeriodsTab').then((m) => ({ default: m.PeriodsTab })),
);

const TABS = [
  { value: 'organization', label: 'Organization' },
  { value: 'users', label: 'Users' },
  { value: 'policies', label: 'Approval policies' },
  { value: 'imports', label: 'Imports' },
  { value: 'audit', label: 'Audit trail' },
  { value: 'periods', label: 'Accounting periods' },
];

/**
 * Route `/settings`. Everyone sees the organization, connectivity and motion settings (and changes their own password
 * from the profile menu); administrators also get users, approval policies, CSV imports, the audit trail and
 * accounting periods.
 */
export default function AdministrationPage() {
  const { can } = useWorkspace();
  const [tab, setTab] = useTabParam(
    TABS.map((t) => t.value),
    'organization',
  );
  if (!can()) return <OrganizationTab />;
  return (
    <>
      <div className="panel admin-tabbar">
        <Tabs label="Administration sections" items={TABS} value={tab} onChange={setTab} />
      </div>
      {tab === 'organization' && <OrganizationTab />}
      <Suspense
        fallback={
          <div className="panel adm-state" role="status">
            <LoaderCircle className="spin" size={20} />
            <span>Loading…</span>
          </div>
        }
      >
        {tab === 'users' && <UsersTab />}
        {tab === 'policies' && <PoliciesTab />}
        {tab === 'imports' && <ImportsTab />}
        {tab === 'audit' && <AuditTab />}
        {tab === 'periods' && <PeriodsTab />}
      </Suspense>
    </>
  );
}
