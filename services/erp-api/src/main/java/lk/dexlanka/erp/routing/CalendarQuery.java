package lk.dexlanka.erp.routing;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shift calendar read model (V10): calendars, patterns and work-center assignments for the Routings & shifts screen, one
 * calendar with its exceptions, and available minutes per work center and day ({@link CalendarService}).
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CalendarQuery {
    /** Longest range of {@code GET /api/calendars/available-minutes}. */
    static final int MAX_DAYS = 62;

    private final Store s;
    private final CalendarService calendars;

    CalendarQuery(Store s, CalendarService calendars) {
        this.s = s;
        this.calendars = calendars;
    }

    /** {@code {calendars, patterns, workCenters}} of the tenant. */
    public Map<String, Object> overview(Actor a) {
        var out = new LinkedHashMap<String, Object>();
        out.put("calendars", s.list("""
                        SELECT c.*,
                               COALESCE((SELECT string_agg(p.id, ',' ORDER BY p.start_time,p.code) FROM shift_calendar_patterns cp
                                         JOIN shift_patterns p ON p.tenant_id=cp.tenant_id AND p.id=cp.pattern_id
                                         WHERE cp.tenant_id=c.tenant_id AND cp.calendar_id=c.id),'') AS pattern_ids,
                               COALESCE((SELECT string_agg(p.code, ', ' ORDER BY p.start_time,p.code) FROM shift_calendar_patterns cp
                                         JOIN shift_patterns p ON p.tenant_id=cp.tenant_id AND p.id=cp.pattern_id
                                         WHERE cp.tenant_id=c.tenant_id AND cp.calendar_id=c.id),'') AS pattern_codes,
                               (SELECT COUNT(*) FROM work_center_calendars w WHERE w.tenant_id=c.tenant_id AND w.calendar_id=c.id) AS assigned,
                               (SELECT COUNT(*) FROM shift_calendar_exceptions e WHERE e.tenant_id=c.tenant_id AND e.calendar_id=c.id
                                  AND e.exception_date >= (now() AT TIME ZONE 'Asia/Colombo')::date) AS upcoming_exceptions
                        FROM shift_calendars c
                        WHERE c.tenant_id=?
                        ORDER BY c.plant_default DESC,c.code""", a.tenant()));
        out.put("patterns", patterns(a));
        out.put("workCenters", s.list("""
                        SELECT m.id,m.code,m.name,m.kind,m.status,w.calendar_id,c.code AS calendar_code,c.name AS calendar_name,
                               CASE WHEN w.calendar_id IS NOT NULL THEN 'ASSIGNED'
                                    WHEN d.id IS NOT NULL THEN 'PLANT_DEFAULT' ELSE 'NONE' END AS source,
                               COALESCE(c.id,d.id) AS effective_calendar_id,
                               COALESCE(c.code,d.code) AS effective_calendar_code
                        FROM machines m
                        LEFT JOIN work_center_calendars w ON w.tenant_id=m.tenant_id AND w.machine_id=m.id
                        LEFT JOIN shift_calendars c ON c.tenant_id=w.tenant_id AND c.id=w.calendar_id
                        LEFT JOIN shift_calendars d ON d.tenant_id=m.tenant_id AND d.plant_default
                        WHERE m.tenant_id=?
                        ORDER BY m.code""", a.tenant()));
        return out;
    }

    /** Shift patterns with their weekdays as ISO numbers ({@code weekday_list}). */
    List<Map<String, Object>> patterns(Actor a) {
        var out = new ArrayList<Map<String, Object>>();
        for (var p : s.list("""
                SELECT p.*,
                       (SELECT COUNT(*) FROM shift_calendar_patterns cp WHERE cp.tenant_id=p.tenant_id AND cp.pattern_id=p.id) AS calendars
                FROM shift_patterns p WHERE p.tenant_id=? ORDER BY p.start_time,p.code""", a.tenant())) {
            var row = new LinkedHashMap<String, Object>(p);
            int mask = ((Number) p.get("weekdays")).intValue();
            var days = new ArrayList<Integer>();
            for (int i = 0; i < 7; i++) if ((mask >> i & 1) == 1) days.add(i + 1);
            row.put("weekday_list", days);
            var pattern = new CalendarService.Pattern(
                    str(p, "code"),
                    CalendarService.time(p.get("start_time")),
                    CalendarService.time(p.get("end_time")),
                    p.get("break_start") == null ? null : CalendarService.time(p.get("break_start")),
                    ((Number) p.get("break_minutes")).intValue(),
                    mask);
            row.put("paid_minutes", pattern.length() - pattern.breakMinutes());
            row.put("overnight", !pattern.end().isAfter(pattern.start()));
            out.add(row);
        }
        return out;
    }

    /** One calendar: {@code {calendar, patterns, exceptions, workCenters}}; exceptions dated {@code from}..{@code to}. */
    public Map<String, Object> calendar(Actor a, String id, String from, String to) {
        var calendar = s.one("SELECT * FROM shift_calendars WHERE tenant_id=? AND id=?", a.tenant(), id);
        LocalDate today = LocalDate.now(CalendarService.ZONE);
        LocalDate first = from == null || from.isBlank() ? today.minusDays(30) : date(from);
        LocalDate last = to == null || to.isBlank() ? today.plusDays(365) : date(to);
        require(!last.isBefore(first), "The end date must not be before the start date");
        var out = new LinkedHashMap<String, Object>();
        out.put("calendar", calendar);
        out.put(
                "patterns",
                s.list(
                        "SELECT p.* FROM shift_calendar_patterns cp JOIN shift_patterns p ON p.tenant_id=cp.tenant_id AND p.id=cp.pattern_id WHERE cp.tenant_id=? AND cp.calendar_id=? ORDER BY p.start_time,p.code",
                        a.tenant(),
                        id));
        out.put(
                "exceptions",
                s.list(
                        "SELECT e.*,(SELECT name FROM users u WHERE u.id=e.created_by) AS created_by_name FROM shift_calendar_exceptions e WHERE e.tenant_id=? AND e.calendar_id=? AND e.exception_date BETWEEN ?::date AND ?::date ORDER BY e.exception_date,e.start_time NULLS FIRST LIMIT 500",
                        a.tenant(),
                        id,
                        first.toString(),
                        last.toString()));
        out.put(
                "workCenters",
                s.list(
                        "SELECT m.id,m.code,m.name FROM work_center_calendars w JOIN machines m ON m.tenant_id=w.tenant_id AND m.id=w.machine_id WHERE w.tenant_id=? AND w.calendar_id=? ORDER BY m.code",
                        a.tenant(),
                        id));
        return out;
    }

    /**
     * Available minutes per day of one work center ({@code machineId}) or of every work center, from {@code from} to
     * {@code to} inclusive (at most {@value #MAX_DAYS} days): {@code {from, to, workCenters: [{machine, calendar,
     * source, minutes, days: [{date, minutes, closed, closedReason, intervals}]}]}}.
     */
    public Map<String, Object> availability(Actor a, String machineId, String from, String to) {
        LocalDate first = date(from);
        LocalDate last = date(to);
        require(!last.isBefore(first), "The end date must not be before the start date");
        require(
                java.time.temporal.ChronoUnit.DAYS.between(first, last) < MAX_DAYS,
                "Choose a range of at most " + MAX_DAYS + " days");
        var machines = machineId == null || machineId.isBlank()
                ? s.list("SELECT id,code,name,kind,status FROM machines WHERE tenant_id=? ORDER BY code", a.tenant())
                : List.of(s.one(
                        "SELECT id,code,name,kind,status FROM machines WHERE tenant_id=? AND id=?",
                        a.tenant(),
                        machineId));
        var results = calendars.availability(
                a.tenant(), machines.stream().map(m -> str(m, "id")).toList(), first, last);
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < machines.size(); i++) {
            var m = machines.get(i);
            var r = results.get(i);
            var row = new LinkedHashMap<String, Object>();
            row.put("machine_id", m.get("id"));
            row.put("code", m.get("code"));
            row.put("name", m.get("name"));
            row.put("kind", m.get("kind"));
            row.put("status", m.get("status"));
            row.put("calendar_id", r.calendarId());
            row.put("calendar_code", r.calendarCode());
            row.put("calendar_name", r.calendarName());
            row.put("source", r.source());
            row.put("minutes", r.minutes());
            row.put("days", r.days());
            rows.add(row);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("from", first.toString());
        out.put("to", last.toString());
        out.put("workCenters", rows);
        return out;
    }

    static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use calendar dates (yyyy-MM-dd)");
        }
    }
}
