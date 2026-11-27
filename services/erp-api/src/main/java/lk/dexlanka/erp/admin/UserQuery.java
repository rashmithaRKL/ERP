package lk.dexlanka.erp.admin;

import static lk.dexlanka.erp.platform.Store.require;

import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** ADMIN user directory: server-paged (keyset on {@code lower(name), id}), filtered by text, role and status. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class UserQuery {
    static final String COLUMNS = """
            SELECT u.id,u.email,u.name,u.role,u.active,u.created_at,u.updated_at,u.last_login_at,
                   u.password_changed_at,u.deactivated_at,u.must_change_password,
                   u.mfa_enabled_at IS NOT NULL AS mfa_enabled,u.mfa_enabled_at
            FROM users u""";

    private final Store s;

    UserQuery(Store s) {
        this.s = s;
    }

    /**
     * {@code {items, next, summary}}. {@code status} is {@code ACTIVE} or {@code INACTIVE}; {@code q} matches name or
     * email; pass {@code next} as {@code before} for the following page.
     */
    public Map<String, Object> page(Actor a, String q, String role, String status, int limit, String before) {
        a.allow();
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        String like = Queries.like(q);
        String r = Queries.blankToNull(role);
        Boolean active = status == null || status.isBlank() ? null : status.equals("ACTIVE");
        String cursor = Queries.blankToNull(before);
        var rows = s.list(COLUMNS + """
                 WHERE u.tenant_id=?
                  AND (?::text IS NULL OR u.name ILIKE ? OR u.email ILIKE ?)
                  AND (?::text IS NULL OR u.role=?)
                  AND (?::boolean IS NULL OR u.active=?)
                  AND (?::text IS NULL OR (lower(u.name),u.id) >
                       (SELECT lower(c.name),c.id FROM users c WHERE c.tenant_id=u.tenant_id AND c.id=?))
                ORDER BY lower(u.name),u.id
                LIMIT ?""", a.tenant(), like, like, like, r, r, active, active, cursor, cursor, limit + 1);
        var result = new LinkedHashMap<>(Queries.page(rows, limit));
        result.put("summary", s.one("""
                        SELECT COUNT(*) AS total,COUNT(*) FILTER (WHERE active) AS active,
                               COUNT(*) FILTER (WHERE NOT active) AS inactive,
                               COUNT(*) FILTER (WHERE active AND role='ADMIN') AS admins,
                               COUNT(*) FILTER (WHERE active AND must_change_password) AS pending_password
                        FROM users WHERE tenant_id=?""", a.tenant()));
        return result;
    }
}
