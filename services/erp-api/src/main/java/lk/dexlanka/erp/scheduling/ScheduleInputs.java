package lk.dexlanka.erp.scheduling;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.routing.CalendarService;
import org.springframework.stereotype.Component;

/**
 * Loads the scheduler's {@link ScheduleEngine.Input} for one tenant at one instant: work centers with their calendar
 * working time ({@link CalendarService#workingIntervals}, the single source of working time), blocked time
 * (maintenance windows and the {@code machine_downtime} view), the open manufacturing orders with their open
 * operations, remaining minutes, allowed work centers and duration estimates, and the active pins. Reads only; every
 * list is loaded in a fixed order so the same data always gives the same input.
 */
@Component
class ScheduleInputs {
    /** Orders the scheduler considers (DRAFT only when the run includes drafts). */
    static final List<String> OPEN_STATUSES =
            List.of("APPROVED", "MATERIALS_RESERVED", "RELEASED", "IN_PROGRESS", "ON_HOLD", "PARTIALLY_COMPLETED");

    static final int DEFAULT_HORIZON_DAYS = 28;
    static final int DEFAULT_BREAKDOWN_HOURS = 8;

    /** Run parameters (validated by the service). */
    record Params(int horizonDays, boolean useAlternates, int breakdownHours, boolean includeDrafts) {
        static Params defaults() {
            return new Params(DEFAULT_HORIZON_DAYS, true, DEFAULT_BREAKDOWN_HOURS, false);
        }
    }

    private final Store s;
    private final CalendarService calendars;

    ScheduleInputs(Store s, CalendarService calendars) {
        this.s = s;
        this.calendars = calendars;
    }

    ScheduleEngine.Input load(String tenant, Params p, long now) {
        long horizonEnd = now + p.horizonDays() * 1440L;
        OffsetDateTime from = at(now);
        OffsetDateTime to = at(horizonEnd);
        var statuses = new ArrayList<>(OPEN_STATUSES);
        if (p.includeDrafts()) statuses.add("DRAFT");
        String in = String.join(",", statuses.stream().map(x -> "'" + x + "'").toList());

        // ------------------------------------------------------------ work centers and working time
        var machines = new ArrayList<ScheduleEngine.Machine>();
        var codes = new HashMap<String, String>();
        for (var m : s.list("SELECT id,code FROM machines WHERE tenant_id=? ORDER BY code,id", tenant)) {
            String id = str(m, "id");
            codes.put(id, str(m, "code"));
            var working = calendars.workingIntervals(tenant, id, from.toInstant(), to.toInstant()).stream()
                    .map(i -> new ScheduleEngine.Span(
                            Math.floorDiv(i.start().toEpochSecond(), 60),
                            Math.floorDiv(i.end().toEpochSecond(), 60)))
                    .toList();
            machines.add(new ScheduleEngine.Machine(id, str(m, "code"), working));
        }

        // ------------------------------------------------------------ blocked time
        var blocks = new ArrayList<ScheduleEngine.Block>();
        for (var w : s.list("""
                SELECT w.number,w.machine_id,w.title,w.scheduled_start,w.scheduled_end
                FROM maintenance_work_orders w
                WHERE w.tenant_id=? AND w.status IN ('APPROVED','SCHEDULED','IN_PROGRESS') AND w.scheduled_start IS NOT NULL
                  AND w.scheduled_end>? AND w.scheduled_start<?
                ORDER BY w.machine_id,w.scheduled_start,w.number""", tenant, from, to)) {
            long start = Math.max(now, floor(w.get("scheduled_start")));
            long end = Math.min(horizonEnd, ceil(w.get("scheduled_end")));
            if (end > start)
                blocks.add(new ScheduleEngine.Block(
                        str(w, "machine_id"), "MAINTENANCE", str(w, "number"), str(w, "title"), start, end, false));
        }
        for (var d : s.list("""
                SELECT d.number,d.machine_id,d.reason,d.started_at,d.ended_at,w.number AS work_order,
                       CASE WHEN w.status IN ('APPROVED','SCHEDULED','IN_PROGRESS') THEN w.scheduled_end END AS window_end
                FROM machine_downtime d
                LEFT JOIN maintenance_work_orders w ON w.tenant_id=d.tenant_id AND w.id=d.work_order_id
                WHERE d.tenant_id=? AND (d.ended_at IS NULL OR d.ended_at>?) AND d.started_at<?
                ORDER BY d.machine_id,d.started_at,d.number""", tenant, from, to)) {
            long start = Math.max(now, floor(d.get("started_at")));
            long end;
            boolean assumed = false;
            if (d.get("ended_at") != null) end = ceil(d.get("ended_at"));
            else if (d.get("window_end") != null && ceil(d.get("window_end")) > now) end = ceil(d.get("window_end"));
            else {
                end = now + p.breakdownHours() * 60L;
                assumed = true;
            }
            end = Math.min(end, horizonEnd);
            String label = reason(str(d, "reason"))
                    + (d.get("work_order") == null ? "" : " · " + str(d, "work_order"))
                    + (d.get("ended_at") == null ? " (open)" : "");
            if (end > start)
                blocks.add(new ScheduleEngine.Block(
                        str(d, "machine_id"), "DOWNTIME", str(d, "number"), label, start, end, assumed));
        }

        // ------------------------------------------------------------ orders, operations, estimates
        var orderRows = s.list("""
                SELECT o.id,o.number,o.status,o.priority,o.due_date::text AS due_date,o.created_at,o.parent_order_id,o.machine_id,
                       EXISTS (SELECT 1 FROM mo_operations x WHERE x.tenant_id=o.tenant_id AND x.order_id=o.id) AS routed,
                       e.minutes AS estimate
                FROM manufacturing_orders o
                LEFT JOIN schedule_estimates e ON e.tenant_id=o.tenant_id AND e.order_id=o.id
                WHERE o.tenant_id=? AND o.status IN (""" + in + """
                )
                ORDER BY o.created_at,o.id""", tenant);
        var alternates = new HashMap<String, List<String>>();
        for (var x : s.list("""
                SELECT op.id AS operation_id,x.machine_id
                FROM mo_operations op
                JOIN manufacturing_orders o ON o.tenant_id=op.tenant_id AND o.id=op.order_id
                JOIN routing_operation_alternates x ON x.tenant_id=op.tenant_id AND x.operation_id=op.routing_operation_id
                WHERE op.tenant_id=? AND op.status NOT IN ('DONE','CANCELLED') AND o.status IN (""" + in + """
                )
                ORDER BY op.id,x.machine_id""", tenant))
            alternates
                    .computeIfAbsent(str(x, "operation_id"), k -> new ArrayList<>())
                    .add(str(x, "machine_id"));
        var operations = new LinkedHashMap<String, List<ScheduleEngine.Operation>>();
        for (var op : s.list("""
                SELECT op.id,op.order_id,op.seq,op.name,op.status,op.machine_id,op.planned_machine_id,op.planned_minutes,
                       ro.machine_id AS routing_machine_id,
                       COALESCE((SELECT SUM(CASE WHEN l.ended_at IS NULL
                                                 THEN GREATEST(0, EXTRACT(EPOCH FROM (?::timestamptz - l.started_at))::numeric / 60)
                                                 ELSE l.minutes END)
                                 FROM mo_operation_time_logs l WHERE l.tenant_id=op.tenant_id AND l.operation_id=op.id),0) AS actual_minutes
                FROM mo_operations op
                JOIN manufacturing_orders o ON o.tenant_id=op.tenant_id AND o.id=op.order_id
                JOIN routing_operations ro ON ro.tenant_id=op.tenant_id AND ro.id=op.routing_operation_id
                WHERE op.tenant_id=? AND o.status IN (""" + in + """
                )
                ORDER BY op.order_id,op.seq""", from, tenant)) {
            String status = str(op, "status");
            BigDecimal planned = dec(op.get("planned_minutes"));
            BigDecimal actual = dec(op.get("actual_minutes"));
            boolean started = status.equals("IN_PROGRESS") || status.equals("PAUSED");
            BigDecimal remaining = started ? planned.subtract(actual).max(BigDecimal.ZERO) : planned;
            String current = str(op, "machine_id");
            var others = new LinkedHashSet<String>();
            others.add(str(op, "planned_machine_id"));
            others.add(str(op, "routing_machine_id"));
            others.addAll(alternates.getOrDefault(str(op, "id"), List.of()));
            others.remove(current);
            var candidates = new ArrayList<String>();
            candidates.add(current);
            others.stream()
                    .sorted(Comparator.comparing((String m) -> codes.getOrDefault(m, ""))
                            .thenComparing(m -> m))
                    .forEach(candidates::add);
            operations
                    .computeIfAbsent(str(op, "order_id"), k -> new ArrayList<>())
                    .add(new ScheduleEngine.Operation(
                            str(op, "id"),
                            ((Number) op.get("seq")).intValue(),
                            str(op, "name"),
                            status,
                            current,
                            str(op, "planned_machine_id"),
                            List.copyOf(candidates),
                            remaining,
                            planned,
                            actual,
                            status.equals("IN_PROGRESS")));
        }
        var notes = new HashMap<String, List<String>>();
        if (!p.includeDrafts())
            for (var c : s.list(
                    "SELECT parent_order_id,number FROM manufacturing_orders WHERE tenant_id=? AND parent_order_id IS NOT NULL AND status='DRAFT' ORDER BY number",
                    tenant))
                notes.computeIfAbsent(str(c, "parent_order_id"), k -> new ArrayList<>())
                        .add("Child order " + str(c, "number") + " is a draft and is not scheduled");
        var orders = new ArrayList<ScheduleEngine.Order>();
        for (var o : orderRows) {
            String id = str(o, "id");
            String status = str(o, "status");
            boolean routed = Boolean.TRUE.equals(o.get("routed"));
            BigDecimal estimate = o.get("estimate") == null ? null : dec(o.get("estimate"));
            List<ScheduleEngine.Operation> ops = operations.getOrDefault(id, List.of());
            if (!routed && estimate != null) {
                boolean running = status.equals("IN_PROGRESS") || status.equals("PARTIALLY_COMPLETED");
                String machine = str(o, "machine_id");
                ops = List.of(new ScheduleEngine.Operation(
                        null,
                        0,
                        "Order run (planner estimate)",
                        running ? "IN_PROGRESS" : "READY",
                        machine,
                        machine,
                        List.of(machine),
                        estimate,
                        estimate,
                        BigDecimal.ZERO,
                        running));
            }
            LocalDate due = LocalDate.parse(str(o, "due_date"));
            orders.add(new ScheduleEngine.Order(
                    id,
                    str(o, "number"),
                    status,
                    rank(str(o, "priority")),
                    str(o, "priority"),
                    due,
                    dueEnd(due),
                    instant(o.get("created_at")).toEpochMilli(),
                    (String) o.get("parent_order_id"),
                    routed,
                    estimate,
                    ops,
                    notes.getOrDefault(id, List.of())));
        }

        // ------------------------------------------------------------ pins
        var pins = s
                .list(
                        "SELECT id,order_id,operation_id,machine_id,start_at FROM schedule_pins WHERE tenant_id=? AND removed_at IS NULL ORDER BY start_at,id",
                        tenant)
                .stream()
                .map(r -> new ScheduleEngine.Pin(
                        str(r, "id"),
                        str(r, "order_id"),
                        (String) r.get("operation_id"),
                        str(r, "machine_id"),
                        floor(r.get("start_at"))))
                .toList();
        return new ScheduleEngine.Input(now, horizonEnd, p.useAlternates(), machines, blocks, orders, pins);
    }

    // ------------------------------------------------------------------ helpers

    static int rank(String priority) {
        return switch (priority) {
            case "URGENT" -> 0;
            case "HIGH" -> 1;
            default -> 2;
        };
    }

    /** The end of a due date (midnight after it, Asia/Colombo) in epoch minutes. */
    static long dueEnd(LocalDate due) {
        return due.plusDays(1).atStartOfDay(CalendarService.ZONE).toEpochSecond() / 60;
    }

    static String reason(String code) {
        return switch (code) {
            case "BREAKDOWN" -> "Breakdown";
            case "PLANNED_MAINTENANCE" -> "Planned maintenance";
            case "CHANGEOVER" -> "Changeover";
            case "MATERIAL_SHORTAGE" -> "Material shortage";
            case "NO_OPERATOR" -> "No operator";
            case "QUALITY_HOLD" -> "Quality hold";
            case "UTILITIES" -> "Utilities";
            default -> "Downtime";
        };
    }

    static Instant instant(Object value) {
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime t) return t.toInstant();
        if (value instanceof Instant t) return t;
        return OffsetDateTime.parse(value.toString()).toInstant();
    }

    static long floor(Object timestamp) {
        return Math.floorDiv(instant(timestamp).getEpochSecond(), 60);
    }

    static long ceil(Object timestamp) {
        var i = instant(timestamp);
        long m = Math.floorDiv(i.getEpochSecond(), 60);
        return i.getEpochSecond() % 60 == 0 && i.getNano() == 0 ? m : m + 1;
    }

    /** Epoch minute as a UTC timestamp (JDBC parameter and JSON value). */
    static OffsetDateTime at(long minute) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(minute * 60), ZoneOffset.UTC);
    }
}
