package lk.dexlanka.erp.performance;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.routing.CalendarService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the execution records of work centers for a range of Asia/Colombo business days and produces one figures
 * object per work center and day: the frozen snapshot of a closed day, otherwise the live calculation ({@link
 * OeeCalculator}). Reads only: {@code routing.CalendarService} for working time, the {@code machine_downtime} view,
 * operation time logs and reports, and production actuals of orders without a routing.
 */
@Service
@Transactional(readOnly = true)
public class OeeEngine {
    public static final ZoneId ZONE = CalendarService.ZONE;

    /** The longest range one read may cover. */
    static final int MAX_DAYS = 92;

    private final Store s;
    private final CalendarService calendars;

    OeeEngine(Store s, CalendarService calendars) {
        this.s = s;
        this.calendars = calendars;
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    static Instant dayStart(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    /** Work centers of the tenant ordered by code, or only {@code machineId} (404 when it is not the tenant's). */
    List<Map<String, Object>> machines(String tenant, String machineId) {
        if (machineId != null && !machineId.isBlank())
            return List.of(s.one(
                    "SELECT id,code,name,kind,status FROM machines WHERE tenant_id=? AND id=?",
                    tenant,
                    machineId.strip()));
        return s.list("SELECT id,code,name,kind,status FROM machines WHERE tenant_id=? ORDER BY code,id", tenant);
    }

    static void checkRange(LocalDate from, LocalDate to) {
        require(!to.isBefore(from), "The end date must not be before the start date");
        require(ChronoUnit.DAYS.between(from, to) < MAX_DAYS, "Choose a range of at most " + MAX_DAYS + " days");
    }

    /**
     * Figures per work center and day ({@code from}..{@code to}, inclusive): the current snapshot of closed days
     * ({@code closed: true}) and the live calculation of every other day ({@code closed: false}).
     */
    Map<String, Map<LocalDate, Map<String, Object>>> days(
            String tenant, List<String> machineIds, LocalDate from, LocalDate to) {
        checkRange(from, to);
        var out = snapshots(tenant, machineIds, from, to);
        boolean complete = true;
        for (String m : machineIds)
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
                if (!out.get(m).containsKey(d)) complete = false;
        if (complete) return out;
        var live = live(tenant, machineIds, from, to, Instant.now());
        for (String m : machineIds)
            for (var e : live.get(m).entrySet()) out.get(m).putIfAbsent(e.getKey(), e.getValue());
        return out;
    }

    /** The current snapshots of closed days, figures plus {@code closed}, {@code version}, closing user and time. */
    Map<String, Map<LocalDate, Map<String, Object>>> snapshots(
            String tenant, List<String> machineIds, LocalDate from, LocalDate to) {
        var out = new LinkedHashMap<String, Map<LocalDate, Map<String, Object>>>();
        for (String m : machineIds) out.put(m, new LinkedHashMap<>());
        if (machineIds.isEmpty()) return out;
        for (var row : s.list(
                """
                SELECT o.machine_id,o.day,o.version,o.figures::text AS figures,o.closed_at,o.reason,u.name AS closed_by
                FROM oee_day_snapshots o JOIN users u ON u.id=o.closed_by
                WHERE o.tenant_id=? AND o.status='CURRENT' AND o.machine_id=ANY(string_to_array(?,','))
                  AND o.day BETWEEN ?::date AND ?::date""",
                tenant,
                String.join(",", machineIds),
                from.toString(),
                to.toString())) {
            var figures = new LinkedHashMap<String, Object>(s.decode(str(row, "figures")));
            figures.put("closed", true);
            figures.put("version", row.get("version"));
            figures.put("closedAt", instant(row.get("closed_at")).toString());
            figures.put("closedBy", row.get("closed_by"));
            figures.put("recalculationReason", row.get("reason"));
            out.get(str(row, "machine_id")).put(LocalDate.parse(str(row, "day")), figures);
        }
        return out;
    }

    /** Live figures of every work center and day, computed now from the current records and calendars. */
    Map<String, Map<LocalDate, Map<String, Object>>> live(
            String tenant, List<String> machineIds, LocalDate from, LocalDate to, Instant now) {
        checkRange(from, to);
        var out = new LinkedHashMap<String, Map<LocalDate, Map<String, Object>>>();
        for (String m : machineIds) out.put(m, new LinkedHashMap<>());
        if (machineIds.isEmpty()) return out;
        Instant rangeStart = dayStart(from);
        Instant rangeEnd = dayStart(to.plusDays(1));
        if (rangeEnd.isAfter(now)) rangeEnd = now.isAfter(rangeStart) ? now : rangeStart;
        String ids = String.join(",", machineIds);

        var availability = new HashMap<String, CalendarService.Availability>();
        for (var a : calendars.availability(tenant, machineIds, from, to)) availability.put(a.machineId(), a);

        var stops = group(s.list(
                """
                SELECT machine_id,number,reason,planned,started_at,ended_at FROM machine_downtime
                WHERE tenant_id=? AND machine_id=ANY(string_to_array(?,','))
                  AND started_at<? AND COALESCE(ended_at,'infinity'::timestamptz)>?""",
                tenant,
                ids,
                utc(rangeEnd),
                utc(rangeStart)));
        var logs = group(s.list(
                """
                SELECT l.machine_id,l.operation_id,l.order_id,mo.number AS order_number,o.seq,o.name,l.activity,
                       l.started_at,l.ended_at,o.run_minutes_per_unit
                FROM mo_operation_time_logs l
                JOIN mo_operations o ON o.tenant_id=l.tenant_id AND o.id=l.operation_id
                JOIN manufacturing_orders mo ON mo.tenant_id=l.tenant_id AND mo.id=l.order_id
                WHERE l.tenant_id=? AND l.machine_id=ANY(string_to_array(?,','))
                  AND l.started_at<? AND COALESCE(l.ended_at,'infinity'::timestamptz)>?
                ORDER BY l.started_at,l.id""",
                tenant,
                ids,
                utc(rangeEnd),
                utc(rangeStart)));
        var reports = group(s.list(
                """
                SELECT r.machine_id,r.operation_id,r.order_id,mo.number AS order_number,o.seq,o.name,r.good_qty,
                       r.scrap_qty,o.run_minutes_per_unit,r.created_at
                FROM mo_operation_reports r
                JOIN mo_operations o ON o.tenant_id=r.tenant_id AND o.id=r.operation_id
                JOIN manufacturing_orders mo ON mo.tenant_id=r.tenant_id AND mo.id=r.order_id
                WHERE r.tenant_id=? AND r.machine_id=ANY(string_to_array(?,','))
                  AND r.created_at>=? AND r.created_at<?
                ORDER BY r.created_at,r.id""",
                tenant,
                ids,
                utc(rangeStart),
                utc(rangeEnd)));
        var untimed = group(s.list(
                """
                SELECT mo.machine_id,p.good_qty,p.scrap_qty,p.created_at
                FROM production_actuals p
                JOIN manufacturing_orders mo ON mo.tenant_id=p.tenant_id AND mo.id=p.order_id
                WHERE p.tenant_id=? AND mo.routing_id IS NULL AND mo.machine_id=ANY(string_to_array(?,','))
                  AND p.created_at>=? AND p.created_at<?""",
                tenant,
                ids,
                utc(rangeStart),
                utc(rangeEnd)));

        for (String machine : machineIds) {
            var cal = availability.get(machine);
            var calendar = new OeeCalculator.Calendar(cal.source().name(), cal.calendarCode(), cal.calendarName());
            for (var day : cal.days()) {
                LocalDate d = day.date();
                Instant ws = dayStart(d);
                Instant dayEnd = dayStart(d.plusDays(1));
                Instant we = dayEnd.isAfter(now) ? (now.isAfter(ws) ? now : ws) : dayEnd;
                boolean partial = dayEnd.isAfter(now);
                var working = new ArrayList<OeeCalculator.Working>();
                for (var i : day.intervals())
                    working.add(new OeeCalculator.Working(i.start().toInstant(), i.end().toInstant(), i.kind(), i.label()));
                var dayStops = new ArrayList<OeeCalculator.Stop>();
                for (var r : stops.getOrDefault(machine, List.of())) {
                    Instant start = instant(r.get("started_at"));
                    Instant end = r.get("ended_at") == null ? null : instant(r.get("ended_at"));
                    if (start.isBefore(we) && (end == null || end.isAfter(ws)))
                        dayStops.add(new OeeCalculator.Stop(
                                str(r, "number"), str(r, "reason"), Boolean.TRUE.equals(r.get("planned")), start, end));
                }
                var dayLogs = new ArrayList<OeeCalculator.Log>();
                for (var r : logs.getOrDefault(machine, List.of())) {
                    Instant start = instant(r.get("started_at"));
                    Instant end = r.get("ended_at") == null ? null : instant(r.get("ended_at"));
                    if (start.isBefore(we) && (end == null || end.isAfter(ws)))
                        dayLogs.add(new OeeCalculator.Log(
                                str(r, "operation_id"),
                                str(r, "order_id"),
                                str(r, "order_number"),
                                ((Number) r.get("seq")).intValue(),
                                str(r, "name"),
                                str(r, "activity"),
                                start,
                                end,
                                dec(r.get("run_minutes_per_unit"))));
                }
                var dayReports = new ArrayList<OeeCalculator.Report>();
                for (var r : reports.getOrDefault(machine, List.of())) {
                    Instant at = instant(r.get("created_at"));
                    if (!at.isBefore(ws) && at.isBefore(we))
                        dayReports.add(new OeeCalculator.Report(
                                str(r, "operation_id"),
                                str(r, "order_id"),
                                str(r, "order_number"),
                                ((Number) r.get("seq")).intValue(),
                                str(r, "name"),
                                dec(r.get("good_qty")),
                                dec(r.get("scrap_qty")),
                                dec(r.get("run_minutes_per_unit"))));
                }
                BigDecimal untimedGood = BigDecimal.ZERO, untimedScrap = BigDecimal.ZERO;
                for (var r : untimed.getOrDefault(machine, List.of())) {
                    Instant at = instant(r.get("created_at"));
                    if (!at.isBefore(ws) && at.isBefore(we)) {
                        untimedGood = untimedGood.add(dec(r.get("good_qty")));
                        untimedScrap = untimedScrap.add(dec(r.get("scrap_qty")));
                    }
                }
                var figures = new LinkedHashMap<String, Object>(OeeCalculator.compute(new OeeCalculator.Input(
                        machine,
                        d,
                        ws,
                        we,
                        partial,
                        calendar,
                        working,
                        dayStops,
                        dayLogs,
                        dayReports,
                        untimedGood,
                        untimedScrap)));
                figures.put("closed", false);
                out.get(machine).put(d, figures);
            }
        }
        return out;
    }

    private static Map<String, List<Map<String, Object>>> group(List<Map<String, Object>> rows) {
        var out = new HashMap<String, List<Map<String, Object>>>();
        for (var r : rows)
            out.computeIfAbsent(str(r, "machine_id"), k -> new ArrayList<>()).add(r);
        return out;
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime o) return o.toInstant();
        return OffsetDateTime.parse(value.toString()).toInstant();
    }
}
