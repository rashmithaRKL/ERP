package lk.dexlanka.erp.quality;

import static lk.dexlanka.erp.quality.QualityPaging.args;
import static lk.dexlanka.erp.quality.QualityPaging.code;
import static lk.dexlanka.erp.quality.QualityPaging.like;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read model of CAPAs and their actions (V12); an action is overdue while open after its due date. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CapaQuery {
    static final List<String> STATUSES = List.of("OPEN", "IN_PROGRESS", "VERIFICATION", "CLOSED", "CANCELLED");

    private final Store s;

    CapaQuery(Store s) {
        this.s = s;
    }

    /**
     * CAPAs newest first: {@code {items, next, total, counts}}. {@code status}: {@code all}, {@code active} (not closed
     * or cancelled) or a status; {@code overdue=true} keeps CAPAs with an overdue action or past their due date.
     */
    public Map<String, Object> page(Actor a, String status, String q, boolean overdue, int limit, String before) {
        boolean active = "active".equalsIgnoreCase(status);
        String st = active ? null : code(status, STATUSES, "Unknown CAPA status filter");
        String like = like(q);
        String from = """
                 FROM capas c
                JOIN users o ON o.id=c.owner_id
                WHERE c.tenant_id=?
                  AND (?::text IS NULL OR c.status=?)
                  AND (NOT ?::boolean OR c.status NOT IN ('CLOSED','CANCELLED'))
                  AND (NOT ?::boolean OR (c.status NOT IN ('CLOSED','CANCELLED') AND (c.due_date<CURRENT_DATE
                       OR EXISTS(SELECT 1 FROM capa_actions x WHERE x.tenant_id=c.tenant_id AND x.capa_id=c.id
                                 AND x.status='OPEN' AND x.due_date<CURRENT_DATE))))
                  AND (?::text IS NULL OR c.number ILIKE ? OR c.title ILIKE ? OR o.name ILIKE ?)""";
        var page = QualityPaging.page(
                s,
                """
                SELECT c.*,o.name AS owner,
                       (SELECT COUNT(*) FROM capa_actions x WHERE x.tenant_id=c.tenant_id AND x.capa_id=c.id
                          AND x.status<>'CANCELLED') AS actions,
                       (SELECT COUNT(*) FROM capa_actions x WHERE x.tenant_id=c.tenant_id AND x.capa_id=c.id
                          AND x.status='DONE') AS actions_done,
                       (SELECT COUNT(*) FROM capa_actions x WHERE x.tenant_id=c.tenant_id AND x.capa_id=c.id
                          AND x.status='OPEN' AND x.due_date<CURRENT_DATE) AS overdue_actions,
                       (c.status NOT IN ('CLOSED','CANCELLED') AND c.due_date<CURRENT_DATE) AS overdue,
                       (SELECT string_agg(n.number,', ' ORDER BY n.number) FROM capa_ncrs k
                        JOIN ncrs n ON n.tenant_id=k.tenant_id AND n.id=k.ncr_id
                        WHERE k.tenant_id=c.tenant_id AND k.capa_id=c.id) AS ncrs""",
                from,
                "c",
                "capas",
                args(a.tenant(), st, st, active, overdue, like, like, like, like),
                limit,
                before);
        var counts = new LinkedHashMap<String, Object>();
        for (String code : STATUSES) counts.put(code, 0L);
        for (var r : s.list("SELECT status,COUNT(*) AS n FROM capas WHERE tenant_id=? GROUP BY status", a.tenant()))
            counts.put((String) r.get("status"), r.get("n"));
        counts.put("OVERDUE_ACTIONS", overdueActions(a));
        page.put("counts", counts);
        return page;
    }

    /** One CAPA with its NCRs, actions (with overdue flags) and activity. */
    public Map<String, Object> capa(Actor a, String id) {
        var capa = s.one(
                """
                SELECT c.*,o.name AS owner,o.role AS owner_role,u.name AS created_by_name,v.name AS verified_by_name,
                       x.name AS cancelled_by_name,
                       (c.status NOT IN ('CLOSED','CANCELLED') AND c.due_date<CURRENT_DATE) AS overdue
                FROM capas c
                JOIN users o ON o.id=c.owner_id
                LEFT JOIN users u ON u.id=c.created_by
                LEFT JOIN users v ON v.id=c.verified_by
                LEFT JOIN users x ON x.id=c.cancelled_by
                WHERE c.tenant_id=? AND c.id=?""",
                a.tenant(),
                id);
        var out = new LinkedHashMap<String, Object>();
        out.put("capa", capa);
        out.put(
                "ncrs",
                s.list(
                        """
                        SELECT n.id,n.number,n.title,n.status,n.severity,n.defect_code FROM capa_ncrs k
                        JOIN ncrs n ON n.tenant_id=k.tenant_id AND n.id=k.ncr_id
                        WHERE k.tenant_id=? AND k.capa_id=? ORDER BY n.number""",
                        a.tenant(),
                        id));
        out.put(
                "actions",
                s.list(
                        """
                        SELECT x.*,o.name AS owner,v.name AS completed_by_name,
                               (x.status='OPEN' AND x.due_date<CURRENT_DATE) AS overdue
                        FROM capa_actions x
                        JOIN users o ON o.id=x.owner_id
                        LEFT JOIN users v ON v.id=x.completed_by
                        WHERE x.tenant_id=? AND x.capa_id=? ORDER BY x.seq""",
                        a.tenant(),
                        id));
        out.put(
                "activity",
                s.list(
                        "SELECT id,actor,action,detail,created_at FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at DESC,id DESC LIMIT 50",
                        a.tenant(),
                        capa.get("number")));
        return out;
    }

    /**
     * CAPA actions newest first: {@code {items, next, total}}. {@code status} {@code all}, OPEN, DONE, CANCELLED;
     * {@code overdue=true} keeps open actions past their due date; {@code mine=true} keeps the caller's actions.
     */
    public Map<String, Object> actions(
            Actor a, String status, boolean overdue, boolean mine, int limit, String before) {
        String st = code(status, List.of("OPEN", "DONE", "CANCELLED"), "Unknown action status filter");
        String from = """
                 FROM capa_actions x
                JOIN capas c ON c.tenant_id=x.tenant_id AND c.id=x.capa_id
                JOIN users o ON o.id=x.owner_id
                WHERE x.tenant_id=?
                  AND (?::text IS NULL OR x.status=?)
                  AND (NOT ?::boolean OR (x.status='OPEN' AND x.due_date<CURRENT_DATE))
                  AND (NOT ?::boolean OR x.owner_id=?)""";
        return QualityPaging.page(
                s,
                """
                SELECT x.*,c.number AS capa,c.title AS capa_title,c.status AS capa_status,o.name AS owner,
                       (x.status='OPEN' AND x.due_date<CURRENT_DATE) AS overdue""",
                from,
                "x",
                "capa_actions",
                args(a.tenant(), st, st, overdue, mine, a.id()),
                limit,
                before);
    }

    long overdueActions(Actor a) {
        return s.db()
                .queryForObject(
                        "SELECT COUNT(*) FROM capa_actions x JOIN capas c ON c.tenant_id=x.tenant_id AND c.id=x.capa_id WHERE x.tenant_id=? AND x.status='OPEN' AND x.due_date<CURRENT_DATE AND c.status NOT IN ('CLOSED','CANCELLED')",
                        Long.class,
                        a.tenant());
    }
}
