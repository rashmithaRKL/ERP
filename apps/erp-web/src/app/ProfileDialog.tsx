import { useQueryClient } from '@tanstack/react-query';
import { Check, LogOut, Users } from 'lucide-react';
import { Badge, Overlay } from '../components';
import { request, token } from '../lib/api';
import { useWorkspace } from './workspace';

/** Avatar menu: the signed-in user, the demo persona switcher and sign-out. */
export function ProfileDialog({
  onChangeRole,
  onClose,
}: {
  onChangeRole: (role: string) => void;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const { user } = useWorkspace();
  return (
    <Overlay title="Your workspace profile" subtitle={user.email} onClose={onClose}>
      <div className="profile-body">
        <div className="profile-person">
          <span className="avatar large">
            {user.name
              .split(' ')
              .map((s) => s[0])
              .slice(0, 2)
              .join('')}
          </span>
          <div>
            <strong>{user.name}</strong>
            <Badge value={user.role} />
          </div>
        </div>
        {user.tenant.demo && (
          <>
            <h3>Explore as a team member</h3>
            <p className="muted">
              Demo-only role switching. Use a different planner or administrator to approve an order you
              created.
            </p>
            <div className="role-options">
              {[
                { role: 'admin', name: 'Nimal Perera', title: 'Company administrator' },
                { role: 'planner', name: 'Dilini Fernando', title: 'Production planner' },
                { role: 'warehouse', name: 'Amal Jayasinghe', title: 'Warehouse manager' },
                { role: 'operator', name: 'Kasun Silva', title: 'Machine operator' },
                { role: 'quality', name: 'Tharushi Senanayake', title: 'Quality officer' },
              ].map((r) => (
                <button key={r.role} onClick={() => onChangeRole(r.role)}>
                  <Users size={17} />
                  <span>
                    <strong>{r.name}</strong>
                    <small>{r.title}</small>
                  </span>
                  {user.role.toLowerCase() === r.role && <Check size={17} />}
                </button>
              ))}
            </div>
          </>
        )}
        <button
          className="button secondary"
          onClick={async () => {
            await request('/auth/logout', {});
            await token();
            qc.clear();
            onClose();
            window.location.reload();
          }}
        >
          <LogOut size={15} />
          {user.tenant.demo ? 'Restart demo session' : 'Sign out'}
        </button>
      </div>
    </Overlay>
  );
}
