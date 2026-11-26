package lk.dexlanka.erp.scheduling;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;
import static lk.dexlanka.erp.scheduling.ScheduleInputs.at;
import static lk.dexlanka.erp.scheduling.ScheduleInputs.ceil;
import static lk.dexlanka.erp.scheduling.ScheduleInputs.floor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.routing.CalendarService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Schedule read model (V13). ADMIN and PLANNER read every run; other roles read only the published run (its Gantt,
 * late orders, capacity load), the "next operations" of a work center and an order's projected completion.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ScheduleQuery {
    /** The longest Gantt or capacity window, in days. */
    static final int MAX_WINDOW_DAYS = 42;

    private static final String HEADER = """
            SELECT r.*,cu.name AS created_by_name,pu.name AS published_by_name,b.number AS base_number
            FROM schedule_runs r
            LEFT JOIN users cu ON cu.id=r.created_by
            LEFT JOIN users pu ON pu.id=r.published_by
            LEFT JOIN schedule_runs b ON b.tenant_id=r.tenant_id AND b.id=r.base_run_id
            """;

    private final Store s;
    private final CalendarService calendars;
    private final ObjectMapper json;
    private final ScheduleChanges changes;

    ScheduleQuery(Store s, CalendarService calendars, ObjectMapper json, ScheduleChanges changes) {
        this.s = s;
        this.calendars = calendars;
        this.json = json;
        this.changes = changes;
    }

    private static boolean planner(Actor a) {
        return a.role().equals("ADMIN") || a.role().equals("PLANNER");
    }

    /** A run the actor may read: planners read every run, other roles only the published one. */
    private Map<String, Object> run(Actor a, String id) {
        var run = s.one(HEADER + " WHERE r.tenant_id=? AND r.id=?", a.tenant(), id);
        if (!planner(a) && !"PUBLISHED".equals(run.get("status")))
            throw new AccessDeniedException("Your role can read only the published schedule");
        return run;
    }

    private Map<String, Object> published(String tenant) {
        var rows = s.list(HEADER + " WHERE r.tenant_id=? AND r.status='PUBLISHED'", tenant);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    // ------------------------------------------------------------------ overview and runs

    /**
     * {@code GET /api/schedule}: the published run, the newest draft (planners), active pins, the defaults and (V16)
     * {@code stale}: changes to orders, operations, maintenance windows, downtime and calendars since the published run
     * was created ({@code {changes, last_change_at}}, see {@link ScheduleChanges}; null without a published run).
     */
    public Map<String, Object> overview(Actor a) {
        var out = new LinkedHashMap<String, Object>();
        var published = published(a.tenant());
        out.put("published", published);
        out.put("stale", published == null ? null : changes.since(a.tenant(), published));
        if (planner(a)) {
            var drafts = s.list(
                    HEADER + " WHERE r.tenant_id=? AND r.status='DRAFT' ORDER BY r.created_at DESC,r.id DESC LIMIT 1",
                    a.tenant());
            out.put("draft", drafts.isEmpty() ? null : drafts.getFirst());
            out.put(
                    "pins",
                    s.db().queryForObject(
                                    "SELECT COUNT(*) FROM schedule_pins WHERE tenant_id=? AND removed_at IS NULL",
                                    Long.class,
                                    a.tenant()));
        }
        out.put(
                "defaults",
                Map.of(
                        "horizonDays",
                        ScheduleInputs.DEFAULT_HORIZON_DAYS,
                        "breakdownHours",
                        ScheduleInputs.DEFAULT_BREAKDOWN_HOURS,
                        "useAlternates",
                        true,
                        "includeDrafts",
                        false));
        out.put("now", at(ScheduleService.now()).toString());
        return out;
    }

    /** {@code GET /api/schedule/runs}: runs newest first, optionally by status; keyset-paged (ADMIN/PLANNER). */
    public Map<String, Object> runs(Actor a, String status, int limit, String before) {
        a.allow("PLANNER");
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        String state = blank(status) ? null : status;
        String cursor = blank(before) ? null : before;
        var rows = s.list(HEADER + """
                 WHERE r.tenant_id=? AND (?::text IS NULL OR r.status=?)
                  AND (?::text IS NULL OR (r.created_at,r.id) <
                       (SELECT c.created_at,c.id FROM schedule_runs c WHERE c.tenant_id=r.tenant_id AND c.id=?))
                ORDER BY r.created_at DESC,r.id DESC
                LIMIT ?""", a.tenant(), state, state, cursor, cursor, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        return out;
    }

    /**
     * {@code GET /api/schedule/runs/{id}}: the run header, every order it considered (sequence, status, projected
     * start/end, lateness, warnings, the current order status and estimate) and the pins it met.
     */
    public Map<String, Object> detail(Actor a, String id) {
        var run = run(a, id);
        var out = new LinkedHashMap<String, Object>();
        out.put("run", run);
        out.put("orders", s.list("""
                        SELECT ro.*,o.number,o.status AS current_status,o.quantity,i.name AS product,i.sku,i.uom,m.code AS machine_code,
                               e.minutes AS current_estimate,p.number AS parent_number,
                               (SELECT COUNT(*) FROM schedule_run_operations x WHERE x.tenant_id=ro.tenant_id AND x.run_id=ro.run_id AND x.order_id=ro.order_id) AS operations
                        FROM schedule_run_orders ro
                        JOIN manufacturing_orders o ON o.tenant_id=ro.tenant_id AND o.id=ro.order_id
                        JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id
                        JOIN machines m ON m.tenant_id=o.tenant_id AND m.id=o.machine_id
                        LEFT JOIN manufacturing_orders p ON p.tenant_id=o.tenant_id AND p.id=ro.parent_order_id
                        LEFT JOIN schedule_estimates e ON e.tenant_id=ro.tenant_id AND e.order_id=ro.order_id
                        WHERE ro.tenant_id=? AND ro.run_id=?
                        ORDER BY ro.sequence""", a.tenant(), id));
        out.put("pins", s.list("""
                        SELECT x.pin_id,x.pin_status,x.pin_note,x.order_id,x.operation_id,x.seq,x.name,x.machine_id,m.code AS machine_code,
                               x.start_at,p.start_at AS pinned_start,pm.code AS pinned_machine_code,o.number AS order_number
                        FROM schedule_run_operations x
                        JOIN schedule_pins p ON p.tenant_id=x.tenant_id AND p.id=x.pin_id
                        JOIN machines m ON m.tenant_id=x.tenant_id AND m.id=x.machine_id
                        JOIN machines pm ON pm.tenant_id=p.tenant_id AND pm.id=p.machine_id
                        JOIN manufacturing_orders o ON o.tenant_id=x.tenant_id AND o.id=x.order_id
                        WHERE x.tenant_id=? AND x.run_id=? AND x.pin_id IS NOT NULL
                        ORDER BY p.start_at,x.id""", a.tenant(), id));
        return out;
    }

    // ------------------------------------------------------------------ Gantt

    /**
     * {@code GET /api/schedule/runs/{id}/gantt?from=&to=}: per work center (by code) its live calendar working
     * intervals and closed dates for the window ({@link CalendarService}), the blocked time the run avoided and the
     * run's operation bars (with their working segments) overlapping the window. Dates are local calendar dates,
     * inclusive; the default window is the run's first seven days.
     */
    public Map<String, Object> gantt(Actor a, String id, String from, String to) {
        var run = run(a, id);
        var window = window(run, from, to, 7);
        var start = window[0];
        var end = window[1];
        var windowStart = start.atStartOfDay(CalendarService.ZONE).toOffsetDateTime();
        var windowEnd = end.plusDays(1).atStartOfDay(CalendarService.ZONE).toOffsetDateTime();
        var machines =
                s.list("SELECT id,code,name,kind,status FROM machines WHERE tenant_id=? ORDER BY code,id", a.tenant());
        var availability = calendars.availability(
                a.tenant(), machines.stream().map(m -> str(m, "id")).toList(), start, end);
        var bars = new HashMap<String, List<Map<String, Object>>>();
        for (var bar : s.list("""
                SELECT x.id,x.order_id,x.operation_id,x.seq,x.name,x.operation_status,x.machine_id,x.planned_machine_id,x.alternate,
                       x.start_at,x.end_at,x.minutes,x.remaining_minutes,x.planned_minutes,x.segments::text AS segments,x.late,x.running,
                       x.pin_id,x.pin_status,x.pin_note,pm.code AS planned_machine_code,
                       o.number AS order_number,o.status AS order_status,o.priority,o.due_date,i.name AS product,i.sku,
                       COALESCE(op.status,o.status) AS current_status,ro.late AS order_late,ro.projected_end
                FROM schedule_run_operations x
                JOIN manufacturing_orders o ON o.tenant_id=x.tenant_id AND o.id=x.order_id
                JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id
                JOIN machines pm ON pm.tenant_id=x.tenant_id AND pm.id=x.planned_machine_id
                JOIN schedule_run_orders ro ON ro.tenant_id=x.tenant_id AND ro.run_id=x.run_id AND ro.order_id=x.order_id
                LEFT JOIN mo_operations op ON op.tenant_id=x.tenant_id AND op.id=x.operation_id
                WHERE x.tenant_id=? AND x.run_id=? AND x.end_at>=? AND x.start_at<?
                ORDER BY x.machine_id,x.start_at,x.id""", a.tenant(), id, windowStart, windowEnd)) {
            var row = new LinkedHashMap<String, Object>(bar);
            row.put("segments", segments(str(bar, "segments")));
            bars.computeIfAbsent(str(bar, "machine_id"), k -> new ArrayList<>()).add(row);
        }
        var blocks = new HashMap<String, List<Map<String, Object>>>();
        for (var b : s.list(
                "SELECT id,machine_id,kind,reference,label,start_at,end_at,assumed_end FROM schedule_run_blocks WHERE tenant_id=? AND run_id=? AND end_at>? AND start_at<? ORDER BY machine_id,start_at,id",
                a.tenant(),
                id,
                windowStart,
                windowEnd))
            blocks.computeIfAbsent(str(b, "machine_id"), k -> new ArrayList<>()).add(b);
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < machines.size(); i++) {
            var m = machines.get(i);
            var avail = availability.get(i);
            var row = new LinkedHashMap<String, Object>(m);
            row.put("calendar_code", avail.calendarCode());
            row.put("calendar_source", avail.source());
            var working = new ArrayList<Map<String, Object>>();
            var closed = new ArrayList<Map<String, Object>>();
            for (var day : avail.days()) {
                if (day.closed()) closed.add(Map.of("date", day.date().toString(), "reason", day.closedReason()));
                for (var interval : day.intervals())
                    working.add(Map.of(
                            "start", interval.start().toString(),
                            "end", interval.end().toString(),
                            "kind", interval.kind(),
                            "label", interval.label() == null ? "" : interval.label()));
            }
            row.put("working", working);
            row.put("closed", closed);
            row.put("blocks", blocks.getOrDefault(str(m, "id"), List.of()));
            row.put("bars", bars.getOrDefault(str(m, "id"), List.of()));
            rows.add(row);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("run", run);
        out.put("from", start.toString());
        out.put("to", end.toString());
        out.put("now", at(ScheduleService.now()).toString());
        out.put("machines", rows);
        return out;
    }

    private List<Map<String, String>> segments(String text) {
        try {
            return json.readValue(text, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code [from, to]} local dates: defaults to the run's first {@code days} days; at most 42 days. */
    private static LocalDate[] window(Map<String, Object> run, String from, String to, int days) {
        LocalDate first;
        LocalDate last;
        try {
            first = blank(from)
                    ? ScheduleInputs.instant(run.get("horizon_start"))
                            .atZone(CalendarService.ZONE)
                            .toLocalDate()
                    : LocalDate.parse(from);
            last = blank(to) ? first.plusDays(days - 1) : LocalDate.parse(to);
        } catch (java.time.DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use valid calendar dates");
        }
        require(!last.isBefore(first), "The end date must not be before the start date");
        require(
                ChronoUnit.DAYS.between(first, last) < MAX_WINDOW_DAYS,
                "Choose a window of at most " + MAX_WINDOW_DAYS + " days");
        return new LocalDate[] {first, last};
    }

    // ------------------------------------------------------------------ late orders and capacity

    /** {@code GET /api/schedule/runs/{id}/late}: the run's late orders, latest first, with projected completion. */
    public Map<String, Object> late(Actor a, String id) {
        var run = run(a, id);
        var items = s.list("""
                SELECT ro.order_id,ro.priority,ro.due_date,ro.due_end,ro.projected_start,ro.projected_end,ro.status,ro.lateness_minutes,
                       ro.warnings,ro.order_status,o.number,o.status AS current_status,o.quantity,i.name AS product,i.sku,i.uom
                FROM schedule_run_orders ro
                JOIN manufacturing_orders o ON o.tenant_id=ro.tenant_id AND o.id=ro.order_id
                JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id
                WHERE ro.tenant_id=? AND ro.run_id=? AND ro.late
                ORDER BY ro.lateness_minutes DESC,ro.sequence""", a.tenant(), id);
        return Map.of("run", run, "items", items);
    }

    /**
     * {@code GET /api/schedule/runs/{id}/capacity?from=&to=}: per work center and local date the calendar minutes
     * (live {@link CalendarService}), the minutes blocked by the run's maintenance/downtime inside working time, the
     * available minutes (calendar − blocked), the run's scheduled minutes, the load in percent and {@code overloaded}
     * (scheduled > available: only when the calendar lost working time after the run). Default: the run's horizon
     * (at most 42 days).
     */
    public Map<String, Object> capacity(Actor a, String id, String from, String to) {
        var run = run(a, id);
        int horizon = ((Number) run.get("horizon_days")).intValue();
        var window = window(run, from, to, Math.min(horizon + 1, MAX_WINDOW_DAYS));
        var machines =
                s.list("SELECT id,code,name,kind,status FROM machines WHERE tenant_id=? ORDER BY code,id", a.tenant());
        var availability = calendars.availability(
                a.tenant(), machines.stream().map(m -> str(m, "id")).toList(), window[0], window[1]);
        var blocks = new HashMap<String, List<ScheduleEngine.Span>>();
        for (var b : s.list(
                "SELECT machine_id,start_at,end_at FROM schedule_run_blocks WHERE tenant_id=? AND run_id=?",
                a.tenant(),
                id))
            blocks.computeIfAbsent(str(b, "machine_id"), k -> new ArrayList<>())
                    .add(new ScheduleEngine.Span(floor(b.get("start_at")), ceil(b.get("end_at"))));
        var scheduled = new HashMap<String, List<ScheduleEngine.Span>>();
        for (var x : s.list(
                "SELECT machine_id,segments::text AS segments FROM schedule_run_operations WHERE tenant_id=? AND run_id=?",
                a.tenant(),
                id))
            for (var seg : segments(str(x, "segments")))
                scheduled
                        .computeIfAbsent(str(x, "machine_id"), k -> new ArrayList<>())
                        .add(new ScheduleEngine.Span(floor(seg.get("start")), floor(seg.get("end"))));
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < machines.size(); i++) {
            var m = machines.get(i);
            String machineId = str(m, "id");
            var days = new ArrayList<Map<String, Object>>();
            long totalAvailable = 0;
            long totalScheduled = 0;
            for (var day : availability.get(i).days()) {
                var working = day.intervals().stream()
                        .map(w -> new ScheduleEngine.Span(
                                Math.floorDiv(w.start().toEpochSecond(), 60),
                                Math.floorDiv(w.end().toEpochSecond(), 60)))
                        .toList();
                long calendar =
                        working.stream().mapToLong(ScheduleEngine.Span::minutes).sum();
                long free = ScheduleEngine.subtract(working, blocks.getOrDefault(machineId, List.of())).stream()
                        .mapToLong(ScheduleEngine.Span::minutes)
                        .sum();
                long dayStart = day.date().atStartOfDay(CalendarService.ZONE).toEpochSecond() / 60;
                long dayEnd = day.date()
                                .plusDays(1)
                                .atStartOfDay(CalendarService.ZONE)
                                .toEpochSecond()
                        / 60;
                long busy = 0;
                for (var seg : scheduled.getOrDefault(machineId, List.of()))
                    busy += Math.max(0, Math.min(seg.end(), dayEnd) - Math.max(seg.start(), dayStart));
                totalAvailable += free;
                totalScheduled += busy;
                var d = new LinkedHashMap<String, Object>();
                d.put("date", day.date().toString());
                d.put("calendar_minutes", calendar);
                d.put("blocked_minutes", calendar - free);
                d.put("available_minutes", free);
                d.put("scheduled_minutes", busy);
                d.put("load_pct", free > 0 ? Math.round(busy * 100.0 / free) : busy > 0 ? 999 : 0);
                d.put("overloaded", busy > free);
                d.put("closed", day.closed());
                d.put("closed_reason", day.closedReason());
                days.add(d);
            }
            var row = new LinkedHashMap<String, Object>(m);
            row.put("days", days);
            row.put("available_minutes", totalAvailable);
            row.put("scheduled_minutes", totalScheduled);
            row.put("load_pct", totalAvailable > 0 ? Math.round(totalScheduled * 100.0 / totalAvailable) : 0);
            rows.add(row);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("run", run);
        out.put("from", window[0].toString());
        out.put("to", window[1].toString());
        out.put("machines", rows);
        return out;
    }

    // ------------------------------------------------------------------ compare

    /**
     * {@code GET /api/schedule/runs/{id}/compare?with=}: this run against {@code with} (default: the run published when
     * this one was created, else the current published run). Orders: completion delta and NEWLY_LATE, NO_LONGER_LATE,
     * LATER, EARLIER, SAME, NEW (not in the base) or DROPPED (finished or out of scope since). Operations: only the
     * changes (MOVED, NEW, DROPPED) with their work center and start in both runs.
     */
    public Map<String, Object> compare(Actor a, String id, String with) {
        a.allow("PLANNER");
        var run = run(a, id);
        String baseId = blank(with) ? (String) run.get("base_run_id") : with;
        if (baseId == null) {
            var current = published(a.tenant());
            if (current != null && !id.equals(current.get("id"))) baseId = str(current, "id");
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("run", run);
        if (baseId == null) {
            out.put("base", null);
            out.put("orders", List.of());
            out.put("operations", List.of());
            out.put("summary", Map.of());
            return out;
        }
        var base = run(a, baseId);
        out.put("base", base);
        var orders = new ArrayList<Map<String, Object>>();
        var counts = new LinkedHashMap<String, Integer>();
        for (var r : s.list("""
                WITH a AS (SELECT * FROM schedule_run_orders WHERE tenant_id=? AND run_id=?),
                     b AS (SELECT * FROM schedule_run_orders WHERE tenant_id=? AND run_id=?)
                SELECT COALESCE(a.order_id,b.order_id) AS order_id,o.number,i.name AS product,o.priority,o.due_date,
                       a.status,b.status AS base_status,a.projected_end,b.projected_end AS base_projected_end,
                       a.late,b.late AS base_late,a.lateness_minutes,b.lateness_minutes AS base_lateness_minutes,
                       COALESCE(a.sequence,b.sequence+100000) AS sort
                FROM a FULL OUTER JOIN b ON b.order_id=a.order_id
                JOIN manufacturing_orders o ON o.tenant_id=? AND o.id=COALESCE(a.order_id,b.order_id)
                JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id
                ORDER BY sort,o.number""", a.tenant(), id, a.tenant(), baseId, a.tenant())) {
            var row = new LinkedHashMap<String, Object>(r);
            String change;
            Long delta = null;
            if (r.get("status") == null) change = "DROPPED";
            else if (r.get("base_status") == null) change = "NEW";
            else {
                boolean late = Boolean.TRUE.equals(r.get("late"));
                boolean baseLate = Boolean.TRUE.equals(r.get("base_late"));
                if (r.get("projected_end") != null && r.get("base_projected_end") != null)
                    delta = floor(r.get("projected_end")) - floor(r.get("base_projected_end"));
                if (late && !baseLate) change = "NEWLY_LATE";
                else if (!late && baseLate) change = "NO_LONGER_LATE";
                else if (delta != null && delta > 0) change = "LATER";
                else if (delta != null && delta < 0) change = "EARLIER";
                else change = "SAME";
            }
            row.put("change", change);
            row.put("delta_minutes", delta);
            row.remove("sort");
            counts.merge(change, 1, Integer::sum);
            orders.add(row);
        }
        var operations = new ArrayList<Map<String, Object>>();
        int moved = 0;
        int added = 0;
        int dropped = 0;
        for (var r : s.list("""
                WITH a AS (SELECT * FROM schedule_run_operations WHERE tenant_id=? AND run_id=?),
                     b AS (SELECT * FROM schedule_run_operations WHERE tenant_id=? AND run_id=?)
                SELECT COALESCE(a.order_id,b.order_id) AS order_id,COALESCE(a.operation_id,b.operation_id) AS operation_id,
                       COALESCE(a.seq,b.seq) AS seq,COALESCE(a.name,b.name) AS name,o.number AS order_number,
                       a.machine_id,ma.code AS machine_code,a.start_at,a.end_at,
                       b.machine_id AS base_machine_id,mb.code AS base_machine_code,b.start_at AS base_start_at,b.end_at AS base_end_at
                FROM a FULL OUTER JOIN b ON b.order_id=a.order_id AND COALESCE(b.operation_id,'')=COALESCE(a.operation_id,'')
                JOIN manufacturing_orders o ON o.tenant_id=? AND o.id=COALESCE(a.order_id,b.order_id)
                LEFT JOIN machines ma ON ma.tenant_id=o.tenant_id AND ma.id=a.machine_id
                LEFT JOIN machines mb ON mb.tenant_id=o.tenant_id AND mb.id=b.machine_id
                ORDER BY COALESCE(a.start_at,b.start_at),o.number,COALESCE(a.seq,b.seq)""", a.tenant(), id, a.tenant(), baseId, a.tenant())) {
            String change;
            Long delta = null;
            if (r.get("machine_id") == null) {
                change = "DROPPED";
                dropped++;
            } else if (r.get("base_machine_id") == null) {
                change = "NEW";
                added++;
            } else {
                delta = floor(r.get("start_at")) - floor(r.get("base_start_at"));
                if (delta == 0 && r.get("machine_id").equals(r.get("base_machine_id"))) continue;
                change = "MOVED";
                moved++;
            }
            var row = new LinkedHashMap<String, Object>(r);
            row.put("change", change);
            row.put("delta_minutes", delta);
            operations.add(row);
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("moved", moved);
        summary.put("new", added);
        summary.put("dropped", dropped);
        summary.put("newly_late", counts.getOrDefault("NEWLY_LATE", 0));
        summary.put("no_longer_late", counts.getOrDefault("NO_LONGER_LATE", 0));
        summary.put("later", counts.getOrDefault("LATER", 0));
        summary.put("earlier", counts.getOrDefault("EARLIER", 0));
        out.put("summary", summary);
        out.put("orders", orders);
        out.put("operations", operations);
        return out;
    }

    // ------------------------------------------------------------------ published schedule for everyone

    /**
     * {@code GET /api/schedule/next?machineId=&limit=}: the published run's operations on a work center that have not
     * ended yet and are still open, in scheduled order (every role).
     */
    public Map<String, Object> next(Actor a, String machineId, int limit) {
        require(limit >= 1 && limit <= 50, "Choose between 1 and 50 operations");
        var machine = s.one(
                "SELECT id,code,name,kind,status FROM machines WHERE tenant_id=? AND id=?", a.tenant(), machineId);
        var run = published(a.tenant());
        var out = new LinkedHashMap<String, Object>();
        out.put("run", run);
        out.put("machine", machine);
        out.put("items", run == null ? List.of() : s.list("""
                                SELECT x.id,x.order_id,x.operation_id,x.seq,x.name,x.start_at,x.end_at,x.minutes,x.late,x.running,x.pin_status,
                                       o.number AS order_number,o.priority,o.due_date,i.name AS product,i.sku,
                                       COALESCE(op.status,o.status) AS current_status
                                FROM schedule_run_operations x
                                JOIN manufacturing_orders o ON o.tenant_id=x.tenant_id AND o.id=x.order_id
                                JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id
                                LEFT JOIN mo_operations op ON op.tenant_id=x.tenant_id AND op.id=x.operation_id
                                WHERE x.tenant_id=? AND x.run_id=? AND x.machine_id=? AND x.end_at>=now()
                                  AND COALESCE(op.status,'OPEN') NOT IN ('DONE','CANCELLED')
                                  AND o.status NOT IN ('QUALITY_REVIEW','COMPLETED','CLOSED','CANCELLED')
                                ORDER BY x.start_at,x.id
                                LIMIT ?""", a.tenant(), run.get("id"), machineId, limit));
        return out;
    }

    /**
     * {@code GET /api/schedule/orders/{id}}: an order's projection in the published run ({@code published}: status,
     * projected start/end, lateness and its operations) and, for planners, in the newest draft ({@code draft}).
     */
    public Map<String, Object> order(Actor a, String orderId) {
        s.one("SELECT id FROM manufacturing_orders WHERE tenant_id=? AND id=?", a.tenant(), orderId);
        var out = new LinkedHashMap<String, Object>();
        out.put("published", projection(a.tenant(), orderId, "PUBLISHED"));
        if (planner(a)) out.put("draft", projection(a.tenant(), orderId, "DRAFT"));
        return out;
    }

    private Map<String, Object> projection(String tenant, String orderId, String status) {
        var rows = s.list("""
                SELECT r.id AS run_id,r.number AS run_number,r.status AS run_status,r.created_at AS run_created_at,r.published_at,
                       ro.status,ro.projected_start,ro.projected_end,ro.late,ro.lateness_minutes,ro.due_date,ro.warnings,ro.sequence
                FROM schedule_run_orders ro JOIN schedule_runs r ON r.tenant_id=ro.tenant_id AND r.id=ro.run_id
                WHERE ro.tenant_id=? AND ro.order_id=? AND r.status=?
                ORDER BY r.created_at DESC,r.id DESC LIMIT 1""", tenant, orderId, status);
        if (rows.isEmpty()) return null;
        var row = new LinkedHashMap<String, Object>(rows.getFirst());
        row.put("operations", s.list("""
                        SELECT x.operation_id,x.seq,x.name,x.machine_id,m.code AS machine_code,x.start_at,x.end_at,x.minutes,x.late,x.alternate,x.pin_status
                        FROM schedule_run_operations x JOIN machines m ON m.tenant_id=x.tenant_id AND m.id=x.machine_id
                        WHERE x.tenant_id=? AND x.run_id=? AND x.order_id=?
                        ORDER BY x.seq,x.start_at""", tenant, row.get("run_id"), orderId));
        return row;
    }

    /** {@code GET /api/schedule/pins}: active pins (ADMIN/PLANNER). */
    public Map<String, Object> pins(Actor a) {
        a.allow("PLANNER");
        return Map.of("items", s.list("""
                        SELECT p.id,p.order_id,p.operation_id,p.machine_id,p.start_at,p.reason,p.created_at,u.name AS created_by_name,
                               o.number AS order_number,op.seq,op.name AS operation,op.status AS operation_status,m.code AS machine_code
                        FROM schedule_pins p
                        JOIN manufacturing_orders o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
                        LEFT JOIN mo_operations op ON op.tenant_id=p.tenant_id AND op.id=p.operation_id
                        JOIN machines m ON m.tenant_id=p.tenant_id AND m.id=p.machine_id
                        LEFT JOIN users u ON u.id=p.created_by
                        WHERE p.tenant_id=? AND p.removed_at IS NULL
                        ORDER BY p.start_at,p.id
                        LIMIT 500""", a.tenant()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
