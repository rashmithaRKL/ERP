package lk.dexlanka.erp.maintenance;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Maintenance read model: keyset-paged work orders, plans, downtime events and per-machine history, plus document and
 * machine details. Every list returns {@code {items, next}}; pass {@code next} as {@code before} for the following
 * page. Readable by every signed-in role of the tenant.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MaintenanceQuery {
    private static final Set<String> STATUSES =
            Set.of("OPEN", "REQUESTED", "APPROVED", "SCHEDULED", "IN_PROGRESS", "COMPLETED", "CANCELLED");

    private final Store s;
    private final MaintenanceRules rules;
    private final MaintenanceMetrics metrics;

    MaintenanceQuery(Store s, MaintenanceRules rules, MaintenanceMetrics metrics) {
        this.s = s;
        this.rules = rules;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ work orders

    private static final String ORDER_COLUMNS = """
            SELECT w.id,w.number,w.type,w.status,w.priority,w.title,w.description,w.machine_id,m.code AS machine,
                   m.name AS machine_name,m.status AS machine_status,w.plan_id,p.number AS plan,w.due_date,
                   w.scheduled_start,w.scheduled_end,w.assignee_id,au.name AS assignee,w.requested_by,
                   ru.name AS requested_by_name,w.approved_by,apu.name AS approved_by_name,w.created_at,w.approved_at,
                   w.started_at,w.completed_at,w.cancelled_at,w.cancel_reason,w.labour_hours,w.labour_cost,w.parts_cost,
                   w.labour_cost+w.parts_cost AS total_cost,w.findings,w.actions,w.meter_reading,
                   jsonb_array_length(w.checklist) AS checklist_items,
                   (SELECT COUNT(*) FROM jsonb_array_elements(w.checklist) e WHERE e->>'result' IS NOT NULL) AS checklist_done,
                   (SELECT d.number FROM downtime_events d WHERE d.tenant_id=w.tenant_id AND d.work_order_id=w.id
                    AND d.status='OPEN' LIMIT 1) AS open_downtime
            FROM maintenance_work_orders w
            JOIN machines m ON m.tenant_id=w.tenant_id AND m.id=w.machine_id
            LEFT JOIN maintenance_plans p ON p.tenant_id=w.tenant_id AND p.id=w.plan_id
            LEFT JOIN users au ON au.id=w.assignee_id
            LEFT JOIN users ru ON ru.id=w.requested_by
            LEFT JOIN users apu ON apu.id=w.approved_by
            """;

    public Map<String, Object> workOrders(
            Actor a, String status, String type, String machine, String q, int limit, String before) {
        pageSize(limit);
        String st = blank(status);
        if (st != null && !STATUSES.contains(st))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown work order status filter");
        String ty = blank(type);
        if (ty != null && !Set.of("CORRECTIVE", "PREVENTIVE").contains(ty))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown work order type filter");
        String text = blank(q) == null ? null : "%" + q.strip().toLowerCase() + "%";
        var rows = s.list(
                ORDER_COLUMNS + """
                WHERE w.tenant_id=?
                  AND (?::text IS NULL OR (?='OPEN' AND w.status IN ('REQUESTED','APPROVED','SCHEDULED','IN_PROGRESS')) OR w.status=?)
                  AND (?::text IS NULL OR w.type=?)
                  AND (?::text IS NULL OR w.machine_id=?)
                  AND (?::text IS NULL OR lower(w.number) LIKE ? OR lower(w.title) LIKE ? OR lower(m.code) LIKE ?)
                  AND (?::text IS NULL OR (w.created_at,w.id) <
                       (SELECT c.created_at,c.id FROM maintenance_work_orders c WHERE c.tenant_id=w.tenant_id AND c.id=?))
                ORDER BY w.created_at DESC,w.id DESC
                LIMIT ?""",
                a.tenant(),
                st,
                st,
                st,
                ty,
                ty,
                blank(machine),
                blank(machine),
                text,
                text,
                text,
                text,
                blank(before),
                blank(before),
                limit + 1);
        var counts = new LinkedHashMap<String, Object>();
        for (String code : WorkOrderService.statuses()) counts.put(code, 0L);
        for (var r : s.list(
                "SELECT status,COUNT(*) AS n FROM maintenance_work_orders WHERE tenant_id=? GROUP BY status",
                a.tenant())) counts.put(str(r, "status"), r.get("n"));
        var page = page(rows, limit);
        page.put("counts", counts);
        return page;
    }

    public Map<String, Object> workOrder(Actor a, String id) {
        var order = new LinkedHashMap<>(s.one(ORDER_COLUMNS + " WHERE w.tenant_id=? AND w.id=?", a.tenant(), id));
        var raw = s.one("SELECT checklist FROM maintenance_work_orders WHERE tenant_id=? AND id=?", a.tenant(), id);
        var result = new LinkedHashMap<String, Object>();
        result.put("order", order);
        result.put("checklist", rules.readChecklist(raw.get("checklist")));
        result.put("parts", s.list("""
                SELECT p.id,p.kind,p.quantity,p.value_amount,p.notes,p.created_at,p.returns_id,l.number AS lot,
                       i.id AS item_id,i.sku,i.name AS item,i.uom,u.name AS created_by_name,
                       CASE WHEN p.kind='ISSUE' THEN p.quantity-COALESCE((SELECT SUM(r.quantity) FROM maintenance_part_movements r
                         WHERE r.tenant_id=p.tenant_id AND r.returns_id=p.id),0) END AS returnable
                FROM maintenance_part_movements p
                JOIN lots l ON l.tenant_id=p.tenant_id AND l.id=p.lot_id
                JOIN items i ON i.tenant_id=p.tenant_id AND i.id=p.item_id
                LEFT JOIN users u ON u.id=p.created_by
                WHERE p.tenant_id=? AND p.work_order_id=?
                ORDER BY p.created_at,p.id""", a.tenant(), id));
        result.put("labour", s.list("""
                SELECT l.id,l.hours,l.rate,l.cost,l.notes,l.created_at,t.name AS technician,u.name AS created_by_name
                FROM maintenance_labour l
                LEFT JOIN users t ON t.id=l.technician_id
                LEFT JOIN users u ON u.id=l.created_by
                WHERE l.tenant_id=? AND l.work_order_id=?
                ORDER BY l.created_at,l.id""", a.tenant(), id));
        result.put("downtime", s.list(DOWNTIME_COLUMNS + """
                WHERE d.tenant_id=? AND d.work_order_id=?
                ORDER BY d.started_at DESC,d.id DESC""", MaintenanceRules.now(), a.tenant(), id));
        result.put("journal", s.list("""
                SELECT l.id,e.description,l.account,l.debit,l.credit,e.created_at
                FROM journal_entries e JOIN journal_lines l ON l.tenant_id=e.tenant_id AND l.entry_id=e.id
                WHERE e.tenant_id=? AND e.reference=?
                ORDER BY e.created_at,e.id,l.debit DESC""", a.tenant(), order.get("number")));
        result.put("events", s.list("""
                SELECT id,actor,action,reference,detail,created_at FROM audit_events
                WHERE tenant_id=? AND reference=?
                ORDER BY created_at DESC,id DESC LIMIT 100""", a.tenant(), order.get("number")));
        return result;
    }

    // ------------------------------------------------------------------ plans

    private static final String PLAN_COLUMNS = """
            SELECT p.id,p.number,p.machine_id,m.code AS machine,m.name AS machine_name,p.title,p.description,
                   p.checklist,p.interval_days,p.interval_hours,p.estimated_hours,p.priority,p.next_due_date,
                   p.meter_at_last_service,p.last_completed_at,p.active,p.created_at,p.updated_at,
                   (p.next_due_date - ?::date) AS days_until_due,
                   CASE WHEN NOT p.active THEN 'INACTIVE' WHEN p.next_due_date < ?::date THEN 'OVERDUE'
                        WHEN p.next_due_date = ?::date THEN 'DUE' ELSE 'UPCOMING' END AS due_state,
                   o.id AS open_order_id,o.number AS open_order,o.status AS open_order_status,
                   (SELECT r.reading FROM machine_meter_readings r WHERE r.tenant_id=p.tenant_id AND r.machine_id=p.machine_id
                    ORDER BY r.recorded_at DESC,r.id DESC LIMIT 1) AS meter_reading
            FROM maintenance_plans p
            JOIN machines m ON m.tenant_id=p.tenant_id AND m.id=p.machine_id
            LEFT JOIN LATERAL (SELECT w.id,w.number,w.status FROM maintenance_work_orders w
                               WHERE w.tenant_id=p.tenant_id AND w.plan_id=p.id
                                 AND w.status IN ('REQUESTED','APPROVED','SCHEDULED','IN_PROGRESS')
                               ORDER BY w.created_at DESC LIMIT 1) o ON true
            """;

    /**
     * Plans ordered by next due date. {@code due}: {@code overdue} (before today), {@code due} (on or before today plus
     * {@code horizon} days, default 7), anything else = all; inactive plans only with {@code active=all|false}.
     */
    public Map<String, Object> plans(
            Actor a, String machine, String due, Integer horizon, String active, int limit, String before) {
        pageSize(limit);
        LocalDate today = MaintenanceRules.today();
        int days = horizon == null ? 7 : horizon;
        require(days >= 0 && days <= 365, "Choose a horizon between 0 and 365 days");
        String filter = blank(due) == null ? "all" : due;
        LocalDate limitDate = switch (filter) {
            case "overdue" -> today.minusDays(1);
            case "due" -> today.plusDays(days);
            default -> null;
        };
        String activeFilter = blank(active) == null ? "true" : active;
        var rows = s.list(
                PLAN_COLUMNS + """
                WHERE p.tenant_id=?
                  AND (?::text IS NULL OR p.machine_id=?)
                  AND (?::date IS NULL OR p.next_due_date<=?::date)
                  AND (?='all' OR p.active=(?='true'))
                  AND (?::text IS NULL OR (p.next_due_date,p.id) >
                       (SELECT c.next_due_date,c.id FROM maintenance_plans c WHERE c.tenant_id=p.tenant_id AND c.id=?))
                ORDER BY p.next_due_date,p.id
                LIMIT ?""",
                today,
                today,
                today,
                a.tenant(),
                blank(machine),
                blank(machine),
                limitDate,
                limitDate,
                activeFilter,
                activeFilter,
                blank(before),
                blank(before),
                limit + 1);
        var page = page(planRows(rows), limit);
        var counts = s.one("""
                SELECT COUNT(*) FILTER (WHERE active AND next_due_date<?::date) AS overdue,
                       COUNT(*) FILTER (WHERE active AND next_due_date>=?::date AND next_due_date<=?::date) AS due_soon,
                       COUNT(*) FILTER (WHERE active) AS active
                FROM maintenance_plans WHERE tenant_id=?""", today, today, today.plusDays(days), a.tenant());
        page.put("counts", counts);
        page.put("today", today.toString());
        return page;
    }

    public Map<String, Object> plan(Actor a, String id) {
        LocalDate today = MaintenanceRules.today();
        var plan = planRows(List.of(
                        s.one(PLAN_COLUMNS + " WHERE p.tenant_id=? AND p.id=?", today, today, today, a.tenant(), id)))
                .getFirst();
        var result = new LinkedHashMap<String, Object>();
        result.put("plan", plan);
        result.put("orders", s.list("""
                SELECT id,number,status,due_date,scheduled_start,completed_at,labour_cost+parts_cost AS total_cost
                FROM maintenance_work_orders WHERE tenant_id=? AND plan_id=?
                ORDER BY created_at DESC,id DESC LIMIT 20""", a.tenant(), id));
        result.put("events", s.list("""
                SELECT id,actor,action,reference,detail,created_at FROM audit_events
                WHERE tenant_id=? AND reference=?
                ORDER BY created_at DESC,id DESC LIMIT 50""", a.tenant(), plan.get("number")));
        return result;
    }

    private List<Map<String, Object>> planRows(List<Map<String, Object>> rows) {
        var out = new ArrayList<Map<String, Object>>();
        for (var r : rows) {
            var m = new LinkedHashMap<>(r);
            m.put("checklist", rules.readTasks(r.get("checklist")));
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ downtime

    /** Duration in minutes up to now for open events (parameter 1: the current instant). */
    private static final String DOWNTIME_COLUMNS = """
            SELECT d.id,d.number,d.machine_id,m.code AS machine,m.name AS machine_name,d.reason,d.planned,d.started_at,
                   d.ended_at,d.status,d.notes,d.manufacturing_order_id,mo.number AS manufacturing_order,d.work_order_id,
                   w.number AS work_order,w.status AS work_order_status,d.corrects_id,c.number AS corrects,
                   d.correction_reason,ru.name AS reported_by_name,cu.name AS closed_by_name,d.created_at,d.closed_at,
                   ROUND(EXTRACT(EPOCH FROM (COALESCE(d.ended_at,?::timestamptz)-d.started_at))/60.0,1) AS minutes,
                   (SELECT n.number FROM downtime_events n WHERE n.tenant_id=d.tenant_id AND n.corrects_id=d.id) AS corrected_by
            FROM downtime_events d
            JOIN machines m ON m.tenant_id=d.tenant_id AND m.id=d.machine_id
            LEFT JOIN manufacturing_orders mo ON mo.tenant_id=d.tenant_id AND mo.id=d.manufacturing_order_id
            LEFT JOIN maintenance_work_orders w ON w.tenant_id=d.tenant_id AND w.id=d.work_order_id
            LEFT JOIN downtime_events c ON c.tenant_id=d.tenant_id AND c.id=d.corrects_id
            LEFT JOIN users ru ON ru.id=d.reported_by
            LEFT JOIN users cu ON cu.id=d.closed_by
            """;

    /**
     * Downtime events, newest start first. {@code from}/{@code to} are Asia/Colombo calendar days matched against the
     * start; {@code status} OPEN or CLOSED; {@code history=true} adds superseded and withdrawn events.
     */
    public Map<String, Object> downtime(
            Actor a,
            String machine,
            String reason,
            String from,
            String to,
            String status,
            boolean history,
            int limit,
            String before) {
        pageSize(limit);
        String r = blank(reason);
        if (r != null && !r.matches(MaintenanceCommands.REASONS))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown downtime reason filter");
        String st = blank(status);
        if (st != null && !Set.of("OPEN", "CLOSED").contains(st))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown downtime status filter");
        OffsetDateTime start = blank(from) == null ? null : dayStart(MaintenanceRules.date(from, "from date"));
        OffsetDateTime end = blank(to) == null
                ? null
                : dayStart(MaintenanceRules.date(to, "to date").plusDays(1));
        var rows = s.list(
                DOWNTIME_COLUMNS + """
                WHERE d.tenant_id=?
                  AND (?::boolean OR d.status IN ('OPEN','CLOSED'))
                  AND (?::text IS NULL OR d.status=?)
                  AND (?::text IS NULL OR d.machine_id=?)
                  AND (?::text IS NULL OR d.reason=?)
                  AND (?::timestamptz IS NULL OR d.started_at>=?)
                  AND (?::timestamptz IS NULL OR d.started_at<?)
                  AND (?::text IS NULL OR (d.started_at,d.id) <
                       (SELECT x.started_at,x.id FROM downtime_events x WHERE x.tenant_id=d.tenant_id AND x.id=?))
                ORDER BY d.started_at DESC,d.id DESC
                LIMIT ?""",
                MaintenanceRules.now(),
                a.tenant(),
                history,
                st,
                st,
                blank(machine),
                blank(machine),
                r,
                r,
                start,
                start,
                end,
                end,
                blank(before),
                blank(before),
                limit + 1);
        return page(rows, limit);
    }

    // ------------------------------------------------------------------ machines

    /** Every work center with its maintenance situation (open downtime, open orders, next preventive due date). */
    public Map<String, Object> machines(Actor a) {
        LocalDate today = MaintenanceRules.today();
        return Map.of("items", s.list("""
                SELECT m.id,m.code,m.name,m.kind,m.status,m.hourly_rate,
                       od.id AS open_downtime_id,od.number AS open_downtime,od.reason AS open_downtime_reason,
                       od.planned AS open_downtime_planned,od.started_at AS down_since,
                       (SELECT COUNT(*) FROM maintenance_work_orders w WHERE w.tenant_id=m.tenant_id AND w.machine_id=m.id
                        AND w.status IN ('REQUESTED','APPROVED','SCHEDULED','IN_PROGRESS')) AS open_orders,
                       (SELECT w.number FROM maintenance_work_orders w WHERE w.tenant_id=m.tenant_id AND w.machine_id=m.id
                        AND w.status='IN_PROGRESS' LIMIT 1) AS in_progress_order,
                       (SELECT MIN(p.next_due_date) FROM maintenance_plans p WHERE p.tenant_id=m.tenant_id AND p.machine_id=m.id
                        AND p.active) AS next_pm_due,
                       (SELECT COUNT(*) FROM maintenance_plans p WHERE p.tenant_id=m.tenant_id AND p.machine_id=m.id
                        AND p.active AND p.next_due_date<?::date) AS overdue_plans
                FROM machines m
                LEFT JOIN downtime_events od ON od.tenant_id=m.tenant_id AND od.machine_id=m.id AND od.status='OPEN'
                WHERE m.tenant_id=?
                ORDER BY m.code""", today, a.tenant()));
    }

    /** One machine: status, open downtime, open orders, plans, meter, MTBF/MTTR for the last 30 days, recent history. */
    public Map<String, Object> machine(Actor a, String id) {
        LocalDate today = MaintenanceRules.today();
        var machine = s.one(
                "SELECT id,code,name,kind,status,hourly_rate FROM machines WHERE tenant_id=? AND id=?", a.tenant(), id);
        var result = new LinkedHashMap<String, Object>();
        result.put("machine", machine);
        var open = s.list(
                DOWNTIME_COLUMNS + " WHERE d.tenant_id=? AND d.machine_id=? AND d.status='OPEN'",
                MaintenanceRules.now(),
                a.tenant(),
                id);
        result.put("openDowntime", open.isEmpty() ? null : open.getFirst());
        result.put("orders", s.list(ORDER_COLUMNS + """
                WHERE w.tenant_id=? AND w.machine_id=? AND w.status IN ('REQUESTED','APPROVED','SCHEDULED','IN_PROGRESS')
                ORDER BY COALESCE(w.scheduled_start,w.created_at),w.id""", a.tenant(), id));
        result.put("plans", planRows(s.list(PLAN_COLUMNS + """
                WHERE p.tenant_id=? AND p.machine_id=? AND p.active ORDER BY p.next_due_date,p.id""", today, today, today, a.tenant(), id)));
        var meter = s.list(
                "SELECT reading,recorded_at FROM machine_meter_readings WHERE tenant_id=? AND machine_id=? ORDER BY recorded_at DESC,id DESC LIMIT 1",
                a.tenant(),
                id);
        result.put("meter", meter.isEmpty() ? null : meter.getFirst());
        result.put("runningOrders", rules.runningOrders(a, id));
        var m = metrics.metrics(a, today.minusDays(29).toString(), today.toString(), id);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) m.get("machines");
        var summary = new LinkedHashMap<String, Object>(rows.isEmpty() ? Map.of() : rows.getFirst());
        summary.put("from", m.get("from"));
        summary.put("to", m.get("to"));
        result.put("metrics", summary);
        result.put("history", history(a, id, 15, null).get("items"));
        return result;
    }

    /**
     * A machine's maintenance history, newest first: downtime events, work orders, status changes and meter readings.
     */
    public Map<String, Object> history(Actor a, String machineId, int limit, String before) {
        pageSize(limit);
        s.one("SELECT id FROM machines WHERE tenant_id=? AND id=?", a.tenant(), machineId);
        var rows = s.list(
                """
                WITH h AS (
                  SELECT d.id,'DOWNTIME' AS kind,d.started_at AS at,d.number AS reference,d.reason AS code,d.status,
                         d.notes AS detail,ROUND(EXTRACT(EPOCH FROM (COALESCE(d.ended_at,?::timestamptz)-d.started_at))/60.0,1) AS value,
                         d.planned AS flag,u.name AS actor
                  FROM downtime_events d LEFT JOIN users u ON u.id=d.reported_by
                  WHERE d.tenant_id=? AND d.machine_id=?
                  UNION ALL
                  SELECT w.id,'WORK_ORDER',w.created_at,w.number,w.type,w.status,w.title,w.labour_cost+w.parts_cost,
                         w.type='PREVENTIVE',u.name
                  FROM maintenance_work_orders w LEFT JOIN users u ON u.id=w.requested_by
                  WHERE w.tenant_id=? AND w.machine_id=?
                  UNION ALL
                  SELECT c.id,'STATUS',c.changed_at,c.reference,c.to_status,c.from_status,c.reason,NULL,c.source='MANUAL',u.name
                  FROM machine_status_changes c LEFT JOIN users u ON u.id=c.changed_by
                  WHERE c.tenant_id=? AND c.machine_id=?
                  UNION ALL
                  SELECT r.id,'METER',r.recorded_at,NULL,NULL,NULL,r.notes,r.reading,NULL,u.name
                  FROM machine_meter_readings r LEFT JOIN users u ON u.id=r.recorded_by
                  WHERE r.tenant_id=? AND r.machine_id=?
                )
                SELECT * FROM h
                WHERE (?::text IS NULL OR (h.at,h.id) < (SELECT x.at,x.id FROM h x WHERE x.id=?))
                ORDER BY h.at DESC,h.id DESC
                LIMIT ?""",
                MaintenanceRules.now(),
                a.tenant(),
                machineId,
                a.tenant(),
                machineId,
                a.tenant(),
                machineId,
                a.tenant(),
                machineId,
                blank(before),
                blank(before),
                limit + 1);
        return page(rows, limit);
    }

    // ------------------------------------------------------------------ pickers and settings

    /** Active spare-part items with their released, unreserved stock (the parts-issue picker). */
    public Map<String, Object> spareParts(Actor a) {
        return Map.of("items", s.list("""
                SELECT i.id,i.sku,i.name,i.uom,i.standard_cost,i.reorder_level,
                       COALESCE(SUM(b.quantity-b.reserved) FILTER (WHERE l.status='RELEASED'),0) AS available,
                       COALESCE(SUM(b.quantity),0) AS on_hand
                FROM items i
                LEFT JOIN lots l ON l.tenant_id=i.tenant_id AND l.item_id=i.id
                LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                WHERE i.tenant_id=? AND i.type='SPARE_PART' AND i.active
                GROUP BY i.id
                ORDER BY i.sku""", a.tenant()));
    }

    /** Active users who may be assigned maintenance work (MAINTENANCE and ADMIN). */
    public Map<String, Object> technicians(Actor a) {
        return Map.of(
                "items",
                s.list(
                        "SELECT id,name,role FROM users WHERE tenant_id=? AND active AND role IN ('MAINTENANCE','ADMIN') ORDER BY role DESC,name",
                        a.tenant()));
    }

    public Map<String, Object> settings(Actor a) {
        var rows = s.list(
                "SELECT m.labour_rate,m.updated_at,u.name AS updated_by FROM maintenance_settings m LEFT JOIN users u ON u.id=m.updated_by WHERE m.tenant_id=?",
                a.tenant());
        var result = new LinkedHashMap<String, Object>();
        result.put(
                "labourRate",
                rows.isEmpty()
                        ? MaintenanceRules.DEFAULT_LABOUR_RATE
                        : rows.getFirst().get("labour_rate"));
        result.put("updatedAt", rows.isEmpty() ? null : rows.getFirst().get("updated_at"));
        result.put("updatedBy", rows.isEmpty() ? null : rows.getFirst().get("updated_by"));
        result.put("customized", !rows.isEmpty());
        return result;
    }

    // ------------------------------------------------------------------ helpers

    static OffsetDateTime dayStart(LocalDate day) {
        return day.atStartOfDay(MaintenanceRules.BUSINESS_ZONE).toOffsetDateTime();
    }

    private static void pageSize(int limit) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
    }

    private static LinkedHashMap<String, Object> page(List<Map<String, Object>> rows, int limit) {
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        var result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("next", more ? items.getLast().get("id") : "");
        return result;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
