package lk.dexlanka.erp.routing;

import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shift calendar maintenance (V10, PLANNER and ADMIN): shift patterns, calendars made of patterns, dated exceptions
 * (holidays, overtime) and the calendar of each work center. The working-time rules themselves live in {@link
 * CalendarService}.
 */
@Service
public class ShiftCalendarService {
    private final Store s;

    ShiftCalendarService(Store s) {
        this.s = s;
    }

    @Transactional
    public Map<String, Object> createPattern(Actor a, CalendarCommands.ShiftPattern c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "shift-pattern", c, () -> {
            String code = c.code().strip().toUpperCase(Locale.ROOT);
            require(
                    s.list("SELECT 1 FROM shift_patterns WHERE tenant_id=? AND code=?", a.tenant(), code)
                            .isEmpty(),
                    "Shift pattern " + code + " already exists");
            var p = pattern(code, c.startTime(), c.endTime(), c.breakStart(), c.breakMinutes(), c.weekdays());
            String id = id();
            s.db().update(
                            "INSERT INTO shift_patterns(id,tenant_id,code,name,start_time,end_time,break_start,break_minutes,weekdays,created_by) VALUES(?,?,?,?,?::time,?::time,?::time,?,?,?)",
                            id,
                            a.tenant(),
                            code,
                            c.name().strip(),
                            p.start().toString(),
                            p.end().toString(),
                            p.breakStart() == null ? null : p.breakStart().toString(),
                            p.breakMinutes(),
                            p.weekdays(),
                            a.id());
            s.audit(a, "SHIFT_PATTERN_CREATED", code, describe(c.name().strip(), p));
            return Map.of("id", id, "code", code);
        });
    }

    @Transactional
    public Map<String, Object> updatePattern(Actor a, String id, CalendarCommands.ShiftPatternUpdate c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "edit-shift-pattern:" + id, c, () -> {
            var row = s.one("SELECT * FROM shift_patterns WHERE tenant_id=? AND id=?", a.tenant(), id);
            var p = pattern(
                    str(row, "code"), c.startTime(), c.endTime(), c.breakStart(), c.breakMinutes(), c.weekdays());
            s.db().update(
                            "UPDATE shift_patterns SET name=?,start_time=?::time,end_time=?::time,break_start=?::time,break_minutes=?,weekdays=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            c.name().strip(),
                            p.start().toString(),
                            p.end().toString(),
                            p.breakStart() == null ? null : p.breakStart().toString(),
                            p.breakMinutes(),
                            p.weekdays(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "SHIFT_PATTERN_UPDATED",
                    str(row, "code"),
                    describe(c.name().strip(), p));
            return Map.of("id", id, "code", str(row, "code"));
        });
    }

    @Transactional
    public Map<String, Object> createCalendar(Actor a, CalendarCommands.Calendar c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "shift-calendar", c, () -> {
            String code = c.code().strip().toUpperCase(Locale.ROOT);
            require(
                    s.list("SELECT 1 FROM shift_calendars WHERE tenant_id=? AND code=?", a.tenant(), code)
                            .isEmpty(),
                    "Shift calendar " + code + " already exists");
            var patterns = patterns(a, c.patternIds());
            boolean plantDefault = Boolean.TRUE.equals(c.plantDefault());
            if (plantDefault)
                s.db().update(
                                "UPDATE shift_calendars SET plant_default=false,updated_at=now() WHERE tenant_id=? AND plant_default",
                                a.tenant());
            String id = id();
            s.db().update(
                            "INSERT INTO shift_calendars(id,tenant_id,code,name,plant_default,created_by) VALUES(?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            code,
                            c.name().strip(),
                            plantDefault,
                            a.id());
            writePatterns(a, id, patterns);
            s.audit(
                    a,
                    "CALENDAR_CREATED",
                    code,
                    c.name().strip() + "; shifts " + (patterns.isEmpty() ? "none" : String.join(", ", codes(a, id)))
                            + (plantDefault ? "; plant default" : ""));
            return Map.of("id", id, "code", code);
        });
    }

    @Transactional
    public Map<String, Object> updateCalendar(Actor a, String id, CalendarCommands.CalendarUpdate c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "edit-shift-calendar:" + id, c, () -> {
            var row = s.one("SELECT * FROM shift_calendars WHERE tenant_id=? AND id=?", a.tenant(), id);
            var patterns = patterns(a, c.patternIds());
            boolean plantDefault = Boolean.TRUE.equals(c.plantDefault());
            if (plantDefault)
                s.db().update(
                                "UPDATE shift_calendars SET plant_default=false,updated_at=now() WHERE tenant_id=? AND plant_default AND id<>?",
                                a.tenant(),
                                id);
            s.db().update(
                            "UPDATE shift_calendars SET name=?,plant_default=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            c.name().strip(),
                            plantDefault,
                            a.tenant(),
                            id);
            s.db().update("DELETE FROM shift_calendar_patterns WHERE tenant_id=? AND calendar_id=?", a.tenant(), id);
            writePatterns(a, id, patterns);
            s.audit(
                    a,
                    "CALENDAR_UPDATED",
                    str(row, "code"),
                    c.name().strip() + "; shifts " + (patterns.isEmpty() ? "none" : String.join(", ", codes(a, id)))
                            + (plantDefault ? "; plant default" : ""));
            return Map.of("id", id, "code", str(row, "code"));
        });
    }

    @Transactional
    public Map<String, Object> addException(Actor a, String calendarId, CalendarCommands.DateException c, String key) {
        a.allow("PLANNER");
        s.validDate(c.date());
        return s.once(a, key, "calendar-exception:" + calendarId, c, () -> {
            var calendar = s.one("SELECT * FROM shift_calendars WHERE tenant_id=? AND id=?", a.tenant(), calendarId);
            boolean closed = "CLOSED".equals(c.kind());
            String start = blank(c.startTime()) ? null : c.startTime();
            String end = blank(c.endTime()) ? null : c.endTime();
            if (closed) {
                require(start == null && end == null, "A closed date has no times; add overtime separately");
                require(
                        s.list(
                                        "SELECT 1 FROM shift_calendar_exceptions WHERE tenant_id=? AND calendar_id=? AND exception_date=?::date AND kind='CLOSED'",
                                        a.tenant(),
                                        calendarId,
                                        c.date())
                                .isEmpty(),
                        c.date() + " is already closed in this calendar");
            } else {
                require(start != null && end != null, "Overtime needs a start and an end time");
                require(!start.equals(end), "Overtime needs an end time different from its start");
            }
            String id = id();
            s.db().update(
                            "INSERT INTO shift_calendar_exceptions(id,tenant_id,calendar_id,exception_date,kind,start_time,end_time,reason,created_by) VALUES(?,?,?,?::date,?,?::time,?::time,?,?)",
                            id,
                            a.tenant(),
                            calendarId,
                            c.date(),
                            c.kind(),
                            start,
                            end,
                            c.reason().strip(),
                            a.id());
            s.audit(
                    a,
                    "CALENDAR_EXCEPTION_ADDED",
                    str(calendar, "code"),
                    (closed ? "Closed " + c.date() : "Overtime " + c.date() + " " + start + "–" + end) + ": "
                            + c.reason().strip());
            return Map.of("id", id, "calendarId", calendarId);
        });
    }

    @Transactional
    public Map<String, Object> removeException(Actor a, String calendarId, String exceptionId, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "remove-calendar-exception:" + exceptionId, Map.of(), () -> {
            var calendar = s.one("SELECT * FROM shift_calendars WHERE tenant_id=? AND id=?", a.tenant(), calendarId);
            var e = s.one(
                    "SELECT * FROM shift_calendar_exceptions WHERE tenant_id=? AND calendar_id=? AND id=?",
                    a.tenant(),
                    calendarId,
                    exceptionId);
            s.db().update("DELETE FROM shift_calendar_exceptions WHERE tenant_id=? AND id=?", a.tenant(), exceptionId);
            s.audit(
                    a,
                    "CALENDAR_EXCEPTION_REMOVED",
                    str(calendar, "code"),
                    ("CLOSED".equals(e.get("kind")) ? "Closed " : "Overtime ") + str(e, "exception_date") + ": "
                            + str(e, "reason"));
            return Map.of("id", exceptionId, "removed", true);
        });
    }

    /** Assigns a calendar to a work center, or (empty calendar) returns it to the plant default calendar. */
    @Transactional
    public Map<String, Object> assign(Actor a, CalendarCommands.Assignment c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "work-center-calendar:" + c.machineId(), c, () -> {
            var machine = s.one("SELECT code FROM machines WHERE tenant_id=? AND id=?", a.tenant(), c.machineId());
            if (blank(c.calendarId())) {
                s.db().update(
                                "DELETE FROM work_center_calendars WHERE tenant_id=? AND machine_id=?",
                                a.tenant(),
                                c.machineId());
                s.audit(a, "WORK_CENTER_CALENDAR_ASSIGNED", str(machine, "code"), "Plant default calendar");
                return Map.of("machineId", c.machineId(), "calendarId", "");
            }
            var calendar =
                    s.one("SELECT code FROM shift_calendars WHERE tenant_id=? AND id=?", a.tenant(), c.calendarId());
            s.db().update(
                            "INSERT INTO work_center_calendars(tenant_id,machine_id,calendar_id,updated_by) VALUES(?,?,?,?) ON CONFLICT (tenant_id,machine_id) DO UPDATE SET calendar_id=EXCLUDED.calendar_id,updated_by=EXCLUDED.updated_by,updated_at=now()",
                            a.tenant(),
                            c.machineId(),
                            c.calendarId(),
                            a.id());
            s.audit(a, "WORK_CENTER_CALENDAR_ASSIGNED", str(machine, "code"), "Calendar " + str(calendar, "code"));
            return Map.of("machineId", c.machineId(), "calendarId", c.calendarId());
        });
    }

    // ------------------------------------------------------------------ rules

    /**
     * Parses and checks a pattern: the break must lie inside the shift and leave working time. A break of 0 minutes has
     * no start.
     */
    static CalendarService.Pattern pattern(
            String code, String start, String end, String breakStart, int breakMinutes, List<Integer> weekdays) {
        int mask = 0;
        for (int day : weekdays) mask |= 1 << (day - 1);
        LocalTime from = LocalTime.parse(start);
        LocalTime to = LocalTime.parse(end);
        LocalTime pause = breakMinutes == 0 || blank(breakStart) ? null : LocalTime.parse(breakStart);
        var p = new CalendarService.Pattern(code, from, to, pause, breakMinutes, mask);
        require(breakMinutes < p.length(), "The break must be shorter than the shift");
        require(
                p.breakOffset() + breakMinutes <= p.length(),
                "The break must start and end within the shift (" + start + "–" + end + ")");
        return p;
    }

    private static String describe(String name, CalendarService.Pattern p) {
        var days = new StringBuilder();
        String[] names = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int i = 0; i < 7; i++)
            if ((p.weekdays() >> i & 1) == 1)
                days.append(days.isEmpty() ? "" : " ").append(names[i]);
        return name + " " + p.start() + "–" + p.end()
                + (p.breakMinutes() > 0 ? ", " + p.breakMinutes() + " min break" : "") + "; " + days;
    }

    private List<String> patterns(Actor a, List<String> ids) {
        var unique = new LinkedHashSet<String>(ids == null ? List.of() : ids);
        for (String id : unique) s.one("SELECT id FROM shift_patterns WHERE tenant_id=? AND id=?", a.tenant(), id);
        return List.copyOf(unique);
    }

    private void writePatterns(Actor a, String calendar, List<String> patterns) {
        for (String p : patterns)
            s.db().update(
                            "INSERT INTO shift_calendar_patterns(tenant_id,calendar_id,pattern_id) VALUES(?,?,?)",
                            a.tenant(),
                            calendar,
                            p);
    }

    private List<String> codes(Actor a, String calendar) {
        return s
                .list(
                        "SELECT p.code FROM shift_calendar_patterns cp JOIN shift_patterns p ON p.tenant_id=cp.tenant_id AND p.id=cp.pattern_id WHERE cp.tenant_id=? AND cp.calendar_id=? ORDER BY p.start_time,p.code",
                        a.tenant(),
                        calendar)
                .stream()
                .map(r -> str(r, "code"))
                .toList();
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static LocalDate date(String value) {
        return LocalDate.parse(value);
    }
}
