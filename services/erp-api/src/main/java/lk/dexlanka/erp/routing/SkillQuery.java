package lk.dexlanka.erp.routing;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill read model (V10): the catalogue (every role), the user skill matrix (ADMIN and PLANNER, keyset-paged by name)
 * and the signed-in user's own skills.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SkillQuery {
    private final Store s;

    SkillQuery(Store s) {
        this.s = s;
    }

    /** {@code {skills}} with holder counts and how many certifications are expired or expire within 30 days. */
    public Map<String, Object> catalogue(Actor a) {
        LocalDate today = LocalDate.now(CalendarService.ZONE);
        return Map.of(
                "skills",
                s.list(
                        """
                        SELECT k.*,
                               (SELECT COUNT(*) FROM user_skills u WHERE u.tenant_id=k.tenant_id AND u.skill_id=k.id) AS holders,
                               (SELECT COUNT(*) FROM user_skills u WHERE u.tenant_id=k.tenant_id AND u.skill_id=k.id
                                  AND u.certified_until < ?::date) AS expired,
                               (SELECT COUNT(*) FROM user_skills u WHERE u.tenant_id=k.tenant_id AND u.skill_id=k.id
                                  AND u.certified_until BETWEEN ?::date AND ?::date) AS expiring,
                               (SELECT COUNT(DISTINCT o.routing_id) FROM routing_operations o WHERE o.tenant_id=k.tenant_id AND o.skill_id=k.id) AS routings
                        FROM skills k WHERE k.tenant_id=? ORDER BY k.active DESC,k.code""",
                        today.toString(),
                        today.toString(),
                        today.plusDays(30).toString(),
                        a.tenant()));
    }

    /**
     * One page of active users (by name) with their skills: {@code {items: [{id, name, role, skills: [{skill_id,
     * code, level, certified_until, expired}]}], next}}.
     */
    public Map<String, Object> users(Actor a, String q, int limit, String before) {
        if (!a.role().equals("ADMIN") && !a.role().equals("PLANNER"))
            throw new AccessDeniedException("Your role cannot perform this action");
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        String like = RoutingQuery.like(q);
        String cursor = before == null || before.isBlank() ? null : before;
        var users = s.list("""
                SELECT u.id,u.name,u.role FROM users u
                WHERE u.tenant_id=? AND u.active
                  AND (?::text IS NULL OR u.name ILIKE ? OR u.role ILIKE ?)
                  AND (?::text IS NULL OR (lower(u.name),u.id) >
                       (SELECT lower(c.name),c.id FROM users c WHERE c.tenant_id=u.tenant_id AND c.id=?))
                ORDER BY lower(u.name),u.id
                LIMIT ?""", a.tenant(), like, like, like, cursor, cursor, limit + 1);
        boolean more = users.size() > limit;
        var page = more ? users.subList(0, limit) : users;
        var items = new ArrayList<Map<String, Object>>();
        for (var u : page) {
            var row = new LinkedHashMap<String, Object>(u);
            row.put("skills", skills(a.tenant(), str(u, "id")));
            items.add(row);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? page.getLast().get("id") : "");
        return out;
    }

    /** The signed-in user's skills. */
    public Map<String, Object> mine(Actor a) {
        return Map.of("skills", skills(a.tenant(), a.id()));
    }

    private List<Map<String, Object>> skills(String tenant, String user) {
        return s.list("""
                SELECT us.skill_id,k.code,k.name,k.active,us.level,us.certified_until,us.notes,us.updated_at,
                       (us.certified_until IS NOT NULL AND us.certified_until < (now() AT TIME ZONE 'Asia/Colombo')::date) AS expired
                FROM user_skills us JOIN skills k ON k.tenant_id=us.tenant_id AND k.id=us.skill_id
                WHERE us.tenant_id=? AND us.user_id=?
                ORDER BY k.code""", tenant, user);
    }
}
