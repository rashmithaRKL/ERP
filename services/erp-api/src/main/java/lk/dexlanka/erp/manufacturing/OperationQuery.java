package lk.dexlanka.erp.manufacturing;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.routing.CalendarService;
import lk.dexlanka.erp.routing.SkillRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operation read model (V10): an order's operations, the shop-floor queue of a work center, the work-center picker, one
 * operation with its time logs and reports, and the keyset-paged time log ({@code started_at DESC, id DESC}).
 * Operation rows carry planned minutes and the actual machine minutes per activity ({@code actual_setup_minutes},
 * {@code actual_run_minutes}, {@code actual_labour_minutes}, closed logs only) plus {@code open_since} for a running
 * interval.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class OperationQuery {
    /** Operation columns shared by every read: work centers, skill, operator, actual minutes, open interval. */
    private static final String COLUMNS = """
            op.*,m.code AS machine_code,m.name AS machine_name,m.status AS machine_status,
            pm.code AS planned_machine_code,sk.code AS skill_code,sk.name AS skill_name,u.name AS operator_name,
            COALESCE((SELECT SUM(l.minutes) FROM mo_operation_time_logs l WHERE l.tenant_id=op.tenant_id AND l.operation_id=op.id AND l.activity='SETUP'),0) AS actual_setup_minutes,
            COALESCE((SELECT SUM(l.minutes) FROM mo_operation_time_logs l WHERE l.tenant_id=op.tenant_id AND l.operation_id=op.id AND l.activity='RUN'),0) AS actual_run_minutes,
            COALESCE((SELECT SUM(l.labour_minutes) FROM mo_operation_time_logs l WHERE l.tenant_id=op.tenant_id AND l.operation_id=op.id),0) AS actual_labour_minutes,
            (SELECT l.started_at FROM mo_operation_time_logs l WHERE l.tenant_id=op.tenant_id AND l.operation_id=op.id AND l.ended_at IS NULL) AS open_since,
            COALESCE((SELECT string_agg(am.code, ', ' ORDER BY am.code) FROM routing_operation_alternates x
                      JOIN machines am ON am.tenant_id=x.tenant_id AND am.id=x.machine_id
                      WHERE x.tenant_id=op.tenant_id AND x.operation_id=op.routing_operation_id),'') AS alternate_codes
            """;

    private static final String JOINS = """
             FROM mo_operations op
            JOIN machines m ON m.tenant_id=op.tenant_id AND m.id=op.machine_id
            JOIN machines pm ON pm.tenant_id=op.tenant_id AND pm.id=op.planned_machine_id
            LEFT JOIN skills sk ON sk.tenant_id=op.tenant_id AND sk.id=op.skill_id
            LEFT JOIN users u ON u.id=op.operator_id
            """;

    private final Store s;
    private final SkillRules skills;
    private final CalendarService calendars;

    OperationQuery(Store s, SkillRules skills, CalendarService calendars) {
        this.s = s;
        this.skills = skills;
        this.calendars = calendars;
    }

    /**
     * {@code GET /api/manufacturing/{id}/operations}: {@code {routing, operations, logs}}. {@code routing} is null (and
     * {@code operations} empty) for an order without a routing.
     */
    public Map<String, Object> forOrder(Actor a, String orderId) {
        var order = s.one("SELECT * FROM manufacturing_orders WHERE tenant_id=? AND id=?", a.tenant(), orderId);
        var out = new LinkedHashMap<String, Object>();
        out.put(
                "routing",
                order.get("routing_id") == null
                        ? null
                        : s.one(
                                "SELECT id,number,revision,status,product_id FROM routings WHERE tenant_id=? AND id=?",
                                a.tenant(),
                                order.get("routing_id")));
        out.put(
                "operations",
                s.list(
                        "SELECT " + COLUMNS + JOINS + " WHERE op.tenant_id=? AND op.order_id=? ORDER BY op.seq",
                        a.tenant(),
                        orderId));
        out.put("logs", s.list("""
                        SELECT l.*,op.seq,op.name AS operation,m.code AS machine_code,u.name AS user_name
                        FROM mo_operation_time_logs l
                        JOIN mo_operations op ON op.tenant_id=l.tenant_id AND op.id=l.operation_id
                        JOIN machines m ON m.tenant_id=l.tenant_id AND m.id=l.machine_id
                        LEFT JOIN users u ON u.id=l.user_id
                        WHERE l.tenant_id=? AND l.order_id=?
                        ORDER BY l.started_at,l.id
                        LIMIT 500""", a.tenant(), orderId));
        return out;
    }

    /**
     * {@code GET /api/operations/queue?machineId=}: the work center's READY, IN_PROGRESS and PAUSED operations, plus
     * READY operations it may run as an alternate ({@code alternate = true}), ordered running → paused → ready, then
     * order priority (urgent first), due date, order age and sequence; keyset-paged. Each row carries the order, the
     * input quantity ({@code input_qty}) and the signed-in user's skill check ({@code skill_check}).
     */
    public Map<String, Object> queue(Actor a, String machineId, int limit, String before) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        var machine = s.one(
                "SELECT id,code,name,kind,status FROM machines WHERE tenant_id=? AND id=?", a.tenant(), machineId);
        String cursor = before == null || before.isBlank() ? null : before;
        var rows = s.list(
                """
                WITH q AS (
                  SELECT op.id,
                         CASE op.status WHEN 'IN_PROGRESS' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END AS status_rank,
                         CASE o.priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 ELSE 2 END AS priority_rank,
                         o.due_date,o.created_at AS order_created_at,op.seq
                  FROM mo_operations op
                  JOIN manufacturing_orders o ON o.tenant_id=op.tenant_id AND o.id=op.order_id
                  WHERE op.tenant_id=?
                    AND ((op.machine_id=? AND op.status IN ('READY','IN_PROGRESS','PAUSED'))
                      OR (op.status='READY' AND op.machine_id<>? AND (
                            op.planned_machine_id=?
                            OR EXISTS (SELECT 1 FROM routing_operations ro WHERE ro.tenant_id=op.tenant_id AND ro.id=op.routing_operation_id AND ro.machine_id=?)
                            OR EXISTS (SELECT 1 FROM routing_operation_alternates x WHERE x.tenant_id=op.tenant_id AND x.operation_id=op.routing_operation_id AND x.machine_id=?))))
                )
                SELECT """ + COLUMNS + """
                       ,(op.machine_id<>?) AS alternate,
                       o.number AS order_number,o.status AS order_status,o.priority,o.due_date,o.quantity AS order_quantity,
                       o.good_qty AS order_good_qty,i.name AS product,i.sku,i.uom,
                       COALESCE((SELECT p.good_qty FROM mo_operations p WHERE p.tenant_id=op.tenant_id AND p.order_id=op.order_id
                                 AND p.seq<op.seq AND p.status<>'CANCELLED' ORDER BY p.seq DESC LIMIT 1),o.quantity) AS input_qty,
                       (SELECT COUNT(*) FROM mo_operations c WHERE c.tenant_id=op.tenant_id AND c.order_id=op.order_id AND c.status<>'CANCELLED') AS operation_count
                FROM q
                JOIN mo_operations op ON op.tenant_id=? AND op.id=q.id
                JOIN manufacturing_orders o ON o.tenant_id=op.tenant_id AND o.id=op.order_id
                JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id
                JOIN machines m ON m.tenant_id=op.tenant_id AND m.id=op.machine_id
                JOIN machines pm ON pm.tenant_id=op.tenant_id AND pm.id=op.planned_machine_id
                LEFT JOIN skills sk ON sk.tenant_id=op.tenant_id AND sk.id=op.skill_id
                LEFT JOIN users u ON u.id=op.operator_id
                WHERE (?::text IS NULL OR (q.status_rank,q.priority_rank,q.due_date,q.order_created_at,q.seq,q.id) >
                       (SELECT c.status_rank,c.priority_rank,c.due_date,c.order_created_at,c.seq,c.id FROM q c WHERE c.id=?))
                ORDER BY q.status_rank,q.priority_rank,q.due_date,q.order_created_at,q.seq,q.id
                LIMIT ?""",
                a.tenant(),
                machineId,
                machineId,
                machineId,
                machineId,
                machineId,
                machineId,
                a.tenant(),
                cursor,
                cursor,
                limit + 1);
        boolean more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        var items = new ArrayList<Map<String, Object>>();
        for (var r : page) items.add(withSkillCheck(a, r));
        var out = new LinkedHashMap<String, Object>();
        out.put("machine", machine);
        out.put("items", items);
        out.put("next", more ? page.getLast().get("id") : "");
        return out;
    }

    /**
     * {@code GET /api/operations/work-centers}: every work center with its operation counts (ready, running, paused),
     * running orders without a routing ({@code legacy_running}), today's available minutes and calendar.
     */
    public Map<String, Object> workCenters(Actor a) {
        var machines = s.list("""
                SELECT m.id,m.code,m.name,m.kind,m.status,
                       COUNT(op.id) FILTER (WHERE op.status='READY') AS ready,
                       COUNT(op.id) FILTER (WHERE op.status='IN_PROGRESS') AS running,
                       COUNT(op.id) FILTER (WHERE op.status='PAUSED') AS paused,
                       (SELECT COUNT(*) FROM manufacturing_orders o WHERE o.tenant_id=m.tenant_id AND o.machine_id=m.id
                          AND o.status IN ('RELEASED','IN_PROGRESS','ON_HOLD','PARTIALLY_COMPLETED')
                          AND NOT EXISTS (SELECT 1 FROM mo_operations x WHERE x.tenant_id=o.tenant_id AND x.order_id=o.id)) AS legacy_orders
                FROM machines m
                LEFT JOIN mo_operations op ON op.tenant_id=m.tenant_id AND op.machine_id=m.id AND op.status IN ('READY','IN_PROGRESS','PAUSED')
                WHERE m.tenant_id=?
                GROUP BY m.id
                ORDER BY m.code""", a.tenant());
        LocalDate today = LocalDate.now(CalendarService.ZONE);
        var availability = calendars.availability(
                a.tenant(), machines.stream().map(m -> str(m, "id")).toList(), today, today);
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < machines.size(); i++) {
            var row = new LinkedHashMap<String, Object>(machines.get(i));
            var day = availability.get(i);
            row.put("available_minutes_today", day.minutes());
            row.put("calendar_code", day.calendarCode());
            row.put("calendar_source", day.source());
            items.add(row);
        }
        return Map.of("items", items, "date", today.toString());
    }

    /**
     * {@code GET /api/operations/{id}}: {@code {operation, order, logs, reports, machines, skill_check}}; {@code
     * machines} are the work centers the operation may start on (planned, routing work center, alternates).
     */
    public Map<String, Object> detail(Actor a, String id) {
        var op = s.one("SELECT " + COLUMNS + JOINS + " WHERE op.tenant_id=? AND op.id=?", a.tenant(), id);
        var out = new LinkedHashMap<String, Object>();
        out.put("operation", withSkillCheck(a, op));
        out.put(
                "order",
                s.one(
                        "SELECT o.id,o.number,o.status,o.priority,o.due_date,o.quantity,o.good_qty,o.scrap_qty,i.name AS product,i.sku,i.uom FROM manufacturing_orders o JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id WHERE o.tenant_id=? AND o.id=?",
                        a.tenant(),
                        op.get("order_id")));
        out.put(
                "logs",
                s.list(
                        "SELECT l.*,m.code AS machine_code,u.name AS user_name FROM mo_operation_time_logs l JOIN machines m ON m.tenant_id=l.tenant_id AND m.id=l.machine_id LEFT JOIN users u ON u.id=l.user_id WHERE l.tenant_id=? AND l.operation_id=? ORDER BY l.started_at,l.id",
                        a.tenant(),
                        id));
        out.put(
                "reports",
                s.list(
                        "SELECT r.*,u.name AS reported_by_name FROM mo_operation_reports r LEFT JOIN users u ON u.id=r.reported_by WHERE r.tenant_id=? AND r.operation_id=? ORDER BY r.created_at,r.id",
                        a.tenant(),
                        id));
        out.put(
                "machines",
                s.list(
                        """
                        SELECT m.id,m.code,m.name,m.status FROM machines m
                        WHERE m.tenant_id=? AND (m.id=? OR m.id=?
                           OR m.id IN (SELECT machine_id FROM routing_operations WHERE tenant_id=? AND id=?)
                           OR m.id IN (SELECT machine_id FROM routing_operation_alternates WHERE tenant_id=? AND operation_id=?))
                        ORDER BY (m.id=?) DESC,m.code""",
                        a.tenant(),
                        op.get("machine_id"),
                        op.get("planned_machine_id"),
                        a.tenant(),
                        op.get("routing_operation_id"),
                        a.tenant(),
                        op.get("routing_operation_id"),
                        op.get("machine_id")));
        return out;
    }

    /**
     * {@code GET /api/operations/time-logs}: time log rows newest first, optionally for one work center, order or
     * operation: {@code {items, next}}. Closed rows carry {@code minutes} (machine) and {@code labour_minutes}.
     */
    public Map<String, Object> timeLogs(
            Actor a, String machineId, String orderId, String operationId, int limit, String before) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        String machine = blank(machineId) ? null : machineId;
        String order = blank(orderId) ? null : orderId;
        String operation = blank(operationId) ? null : operationId;
        String cursor = blank(before) ? null : before;
        var rows = s.list(
                """
                SELECT l.*,op.seq,op.name AS operation,o.number AS order_number,m.code AS machine_code,u.name AS user_name
                FROM mo_operation_time_logs l
                JOIN mo_operations op ON op.tenant_id=l.tenant_id AND op.id=l.operation_id
                JOIN manufacturing_orders o ON o.tenant_id=l.tenant_id AND o.id=l.order_id
                JOIN machines m ON m.tenant_id=l.tenant_id AND m.id=l.machine_id
                LEFT JOIN users u ON u.id=l.user_id
                WHERE l.tenant_id=?
                  AND (?::text IS NULL OR l.machine_id=?)
                  AND (?::text IS NULL OR l.order_id=?)
                  AND (?::text IS NULL OR l.operation_id=?)
                  AND (?::text IS NULL OR (l.started_at,l.id) <
                       (SELECT c.started_at,c.id FROM mo_operation_time_logs c WHERE c.tenant_id=l.tenant_id AND c.id=?))
                ORDER BY l.started_at DESC,l.id DESC
                LIMIT ?""", a.tenant(), machine, machine, order, order, operation, operation, cursor, cursor, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        return out;
    }

    private Map<String, Object> withSkillCheck(Actor a, Map<String, Object> op) {
        var row = new LinkedHashMap<String, Object>(op);
        var check = skills.check(
                a.tenant(), a.id(), (String) op.get("skill_id"), ((Number) op.get("skill_level")).intValue());
        row.put("skill_check", check.status());
        row.put("skill_level_held", check.level());
        row.put("skill_certified_until", check.certifiedUntil());
        return row;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
