package lk.dexlanka.erp.admin;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Passwords;
import lk.dexlanka.erp.platform.Store;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN user lifecycle: edit name and role, deactivate and reactivate, issue a temporary password. Account creation stays
 * {@code POST /api/users} ({@code catalog.CatalogService#user}).
 *
 * <p>Rules: a tenant always keeps at least one active ADMIN (also a database trigger); administrators cannot deactivate
 * themselves or reset their own password (they use the self-service change). Deactivation and resets increment {@code
 * users.session_version}, which ends the user's existing sessions on their next request ({@code platform.SessionGuard});
 * a role or name change takes effect on the user's next request without signing them out.
 */
@Service
public class UserAdminService {
    private final Store s;
    private final PasswordEncoder passwords;

    UserAdminService(Store s, PasswordEncoder passwords) {
        this.s = s;
        this.passwords = passwords;
    }

    @Transactional
    public Map<String, Object> update(Actor a, String id, AdminCommands.UserUpdate c, String key) {
        a.allow();
        return s.once(a, key, "user-update:" + id, c, () -> {
            var u = user(a, id);
            String name = c.name().strip();
            require(!name.isEmpty(), "Enter the user's name");
            var changes = new ArrayList<String>();
            if (!name.equals(u.get("name"))) changes.add("name " + u.get("name") + " → " + name);
            if (!c.role().equals(u.get("role"))) {
                if ("ADMIN".equals(u.get("role")) && Boolean.TRUE.equals(u.get("active")))
                    requireAnotherAdmin(a, id, "demote");
                changes.add("role " + label(u.get("role")) + " → " + label(c.role()));
            }
            s.db().update(
                            "UPDATE users SET name=?,role=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            name,
                            c.role(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "USER_UPDATED",
                    str(u, "email"),
                    changes.isEmpty() ? "Saved without changes" : String.join("; ", changes));
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> deactivate(Actor a, String id, String key) {
        a.allow();
        return s.once(a, key, "user-deactivate:" + id, Map.of(), () -> {
            var u = user(a, id);
            require(!a.id().equals(id), "You cannot deactivate your own account");
            require(Boolean.TRUE.equals(u.get("active")), "This user is already inactive");
            if ("ADMIN".equals(u.get("role"))) requireAnotherAdmin(a, id, "deactivate");
            s.db().update(
                            "UPDATE users SET active=false,deactivated_at=now(),session_version=session_version+1,updated_at=now() WHERE tenant_id=? AND id=?",
                            a.tenant(),
                            id);
            s.audit(a, "USER_DEACTIVATED", str(u, "email"), "Sign-in blocked; open sessions signed out");
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> reactivate(Actor a, String id, String key) {
        a.allow();
        return s.once(a, key, "user-reactivate:" + id, Map.of(), () -> {
            var u = user(a, id);
            require(!Boolean.TRUE.equals(u.get("active")), "This user is already active");
            s.db().update(
                            "UPDATE users SET active=true,deactivated_at=NULL,updated_at=now() WHERE tenant_id=? AND id=?",
                            a.tenant(),
                            id);
            s.audit(a, "USER_REACTIVATED", str(u, "email"), "Sign-in allowed again as " + label(u.get("role")));
            return result(a, id);
        });
    }

    /**
     * Issues a one-time temporary password, shown once in this response: the user must replace it at the next sign-in
     * and their open sessions end. The password is never stored in clear text, also not in the idempotency record, so
     * an idempotent replay answers {@code temporaryPassword: null} (issue a new reset to get another password).
     */
    @Transactional
    public Map<String, Object> resetPassword(Actor a, String id, String key) {
        a.allow();
        var issued = new String[1];
        var stored = s.once(a, key, "user-reset-password:" + id, Map.of(), () -> {
            var u = user(a, id);
            require(!a.id().equals(id), "Use Change password in your profile menu to change your own password");
            require(Boolean.TRUE.equals(u.get("active")), "Reactivate this user before resetting the password");
            String temporary = Passwords.temporary();
            s.db().update(
                            "UPDATE users SET password_hash=?,must_change_password=true,password_changed_at=now(),session_version=session_version+1,updated_at=now() WHERE tenant_id=? AND id=?",
                            passwords.encode(temporary),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "USER_PASSWORD_RESET",
                    str(u, "email"),
                    "Temporary password issued; must be changed at the next sign-in; open sessions signed out");
            issued[0] = temporary;
            return result(a, id);
        });
        var response = new LinkedHashMap<>(stored);
        response.put("temporaryPassword", issued[0]);
        return response;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> user(Actor a, String id) {
        return s.one("SELECT * FROM users WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    private void requireAnotherAdmin(Actor a, String id, String verb) {
        require(
                !s.list(
                                "SELECT 1 FROM users WHERE tenant_id=? AND id<>? AND role='ADMIN' AND active LIMIT 1",
                                a.tenant(),
                                id)
                        .isEmpty(),
                "You cannot " + verb + " the last active administrator; make another user an administrator first");
    }

    private Map<String, Object> result(Actor a, String id) {
        return new LinkedHashMap<>(s.one(UserQuery.COLUMNS + " WHERE u.tenant_id=? AND u.id=?", a.tenant(), id));
    }

    static String label(Object role) {
        String r = String.valueOf(role);
        return r.charAt(0) + r.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
