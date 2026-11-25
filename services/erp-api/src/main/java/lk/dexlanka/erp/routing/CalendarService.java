package lk.dexlanka.erp.routing;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Working time of work centers (V10): the single source of truth for "when can work center X work". V13 finite
 * scheduling and V15 OEE (planned production time) call {@link #availableMinutes} and {@link #workingIntervals};
 * nothing else should interpret shift calendars.
 *
 * <p>Rules (all local times are Asia/Colombo, {@link #ZONE}):
 *
 * <ol>
 *   <li><b>Calendar of a work center</b>: its assigned calendar ({@code work_center_calendars}), else the tenant's plant
 *       default calendar, else none: a work center without any calendar is available around the clock ({@link
 *       Source#NONE}, one interval per day 00:00–24:00), so tenants that never configured shifts keep infinite
 *       capacity.
 *   <li><b>Shifts</b>: each pattern of the calendar produces one shift on every ISO weekday in its {@code weekdays}
 *       mask, from {@code start_time} to {@code end_time}; when {@code end_time <= start_time} the shift ends on the
 *       next day (overnight). A shift belongs to the date it starts on.
 *   <li><b>Breaks</b>: {@code break_minutes} are removed from the shift starting at {@code break_start} (the first
 *       occurrence of that time at or after the shift start), or centred in the shift when {@code break_start} is
 *       NULL. Breaks are validated to lie inside the shift.
 *   <li><b>Exceptions</b>: a {@code CLOSED} date (holiday) cancels every shift that <i>starts</i> on that date (the
 *       overnight shift of the previous day still runs into it); an {@code OVERTIME} exception adds a working interval
 *       starting on its date (ending the next day when {@code end_time <= start_time}), also on a closed date.
 *   <li><b>Union</b>: overlapping and touching intervals are merged, so overtime overlapping a shift, or two shifts
 *       back to back, count once.
 *   <li><b>Days</b>: the available minutes of a date are the merged intervals clipped to that calendar day
 *       (00:00–24:00 local); an overnight shift contributes to both dates.
 * </ol>
 *
 * Calendar changes apply to past and future dates alike (no history is kept); a consumer that needs frozen planned
 * time must store its own snapshot.
 */
@Service
@Transactional(readOnly = true)
public class CalendarService {
    /** The business timezone; local shift times are interpreted here. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    /** The longest date range one availability call may cover. */
    public static final int MAX_DAYS = 366;

    /** Where a work center's working time comes from. */
    public enum Source {
        ASSIGNED,
        PLANT_DEFAULT,
        NONE
    }

    /**
     * One working interval {@code [start, end)}. {@code kind} is {@code SHIFT}, {@code OVERTIME}, {@code ALWAYS} (no
     * calendar) or {@code MIXED} (merged across kinds); {@code label} names the shift pattern(s) or overtime reason.
     */
    public record Interval(OffsetDateTime start, OffsetDateTime end, String kind, String label) {
        public Duration duration() {
            return Duration.between(start, end);
        }

        /** Whole minutes (intervals clipped to arbitrary instants may have a partial last minute). */
        public long minutes() {
            return duration().toMinutes();
        }
    }

    /** One calendar date: available minutes, whether a holiday closed it, and its merged intervals within the day. */
    public record Day(LocalDate date, long minutes, boolean closed, String closedReason, List<Interval> intervals) {}

    /** Availability of one work center over a date range. */
    public record Availability(
            String machineId,
            String calendarId,
            String calendarCode,
            String calendarName,
            Source source,
            long minutes,
            List<Day> days) {}

    private final Store s;

    CalendarService(Store s) {
        this.s = s;
    }

    // ------------------------------------------------------------------ public API

    /** Available minutes of work center {@code machineId} on calendar date {@code date} (00:00–24:00 local). */
    public long availableMinutes(String tenant, String machineId, LocalDate date) {
        return availability(tenant, machineId, date, date).minutes();
    }

    /**
     * Merged working intervals of work center {@code machineId} that overlap {@code [from, to)}, clipped to it, in
     * time order.
     */
    public List<Interval> workingIntervals(String tenant, String machineId, Instant from, Instant to) {
        require(from.isBefore(to), "The end of the range must be after its start");
        LocalDate first = from.atZone(ZONE).toLocalDate();
        LocalDate last = to.minusNanos(1).atZone(ZONE).toLocalDate();
        require(ChronoUnit.DAYS.between(first, last) < MAX_DAYS, "Choose a range of at most " + MAX_DAYS + " days");
        var calendar = resolve(tenant, machineId);
        var plan = load(tenant, calendar, first.minusDays(1), last);
        return clip(plan.intervals(first.minusDays(1), last), from, to);
    }

    /** Merged working intervals of the calendar dates {@code from}..{@code to} (inclusive), clipped to those days. */
    public List<Interval> workingIntervals(String tenant, String machineId, LocalDate from, LocalDate to) {
        return workingIntervals(
                tenant,
                machineId,
                startOf(from).toInstant(),
                startOf(to.plusDays(1)).toInstant());
    }

    /** Availability per calendar date {@code from}..{@code to} (inclusive) of one work center. */
    public Availability availability(String tenant, String machineId, LocalDate from, LocalDate to) {
        return availability(tenant, List.of(machineId), from, to).getFirst();
    }

    /** Availability of several work centers over the same dates; calendars shared by work centers are loaded once. */
    public List<Availability> availability(String tenant, List<String> machineIds, LocalDate from, LocalDate to) {
        require(!to.isBefore(from), "The end date must not be before the start date");
        require(ChronoUnit.DAYS.between(from, to) < MAX_DAYS, "Choose a range of at most " + MAX_DAYS + " days");
        var plans = new HashMap<String, Plan>();
        var out = new ArrayList<Availability>();
        for (String machineId : machineIds) {
            var calendar = resolve(tenant, machineId);
            var plan = plans.computeIfAbsent(
                    calendar.id() == null ? "" : calendar.id(), k -> load(tenant, calendar, from.minusDays(1), to));
            var merged = plan.intervals(from.minusDays(1), to);
            var days = new ArrayList<Day>();
            long total = 0;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                var within = clip(merged, startOf(d), startOf(d.plusDays(1)));
                long minutes = within.stream().mapToLong(Interval::minutes).sum();
                total += minutes;
                String closed = plan.closed.get(d);
                days.add(new Day(d, minutes, closed != null, closed, within));
            }
            out.add(new Availability(
                    machineId, calendar.id(), calendar.code(), calendar.name(), calendar.source(), total, days));
        }
        return out;
    }

    // ------------------------------------------------------------------ calendar resolution and loading

    /** The calendar that governs a work center (404 when the work center is not the tenant's). */
    record Calendar(String id, String code, String name, Source source) {}

    Calendar resolve(String tenant, String machineId) {
        s.one("SELECT id FROM machines WHERE tenant_id=? AND id=?", tenant, machineId);
        var assigned = s.list(
                "SELECT c.id,c.code,c.name FROM work_center_calendars w JOIN shift_calendars c ON c.tenant_id=w.tenant_id AND c.id=w.calendar_id WHERE w.tenant_id=? AND w.machine_id=?",
                tenant,
                machineId);
        if (!assigned.isEmpty()) return calendar(assigned.getFirst(), Source.ASSIGNED);
        var plant = s.list("SELECT id,code,name FROM shift_calendars WHERE tenant_id=? AND plant_default", tenant);
        if (!plant.isEmpty()) return calendar(plant.getFirst(), Source.PLANT_DEFAULT);
        return new Calendar(null, null, null, Source.NONE);
    }

    private static Calendar calendar(Map<String, Object> row, Source source) {
        return new Calendar(str(row, "id"), str(row, "code"), str(row, "name"), source);
    }

    /** A shift pattern: local start/end, optional break start, break minutes, ISO weekday mask (bit 0 = Monday). */
    record Pattern(String code, LocalTime start, LocalTime end, LocalTime breakStart, int breakMinutes, int weekdays) {
        boolean on(LocalDate date) {
            return (weekdays >> (date.getDayOfWeek().getValue() - 1) & 1) == 1;
        }

        /** Shift length in minutes; equal start and end times mean a 24-hour shift. */
        long length() {
            long minutes = Duration.between(start, end).toMinutes();
            return minutes <= 0 ? minutes + 1440 : minutes;
        }

        /** Minutes from the shift start to the break start. */
        long breakOffset() {
            if (breakStart == null) return (length() - breakMinutes) / 2;
            long offset = Duration.between(start, breakStart).toMinutes();
            return offset < 0 ? offset + 1440 : offset;
        }
    }

    record Overtime(LocalDate date, LocalTime start, LocalTime end, String reason) {}

    /** Everything needed to generate intervals for a date window. */
    static final class Plan {
        final boolean always;
        final List<Pattern> patterns;
        final Map<LocalDate, String> closed;
        final List<Overtime> overtime;

        Plan(boolean always, List<Pattern> patterns, Map<LocalDate, String> closed, List<Overtime> overtime) {
            this.always = always;
            this.patterns = patterns;
            this.closed = closed;
            this.overtime = overtime;
        }

        /** Merged intervals of every shift and overtime that starts on a date {@code first}..{@code last}. */
        List<Interval> intervals(LocalDate first, LocalDate last) {
            var raw = new ArrayList<Interval>();
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                if (always) {
                    raw.add(new Interval(startOf(d), startOf(d.plusDays(1)), "ALWAYS", "No shift calendar"));
                    continue;
                }
                if (!closed.containsKey(d)) for (var p : patterns) if (p.on(d)) raw.addAll(shift(d, p));
            }
            for (var o : overtime)
                if (!o.date().isBefore(first) && !o.date().isAfter(last)) {
                    var start = at(o.date(), o.start());
                    var end =
                            at(o.end().isAfter(o.start()) ? o.date() : o.date().plusDays(1), o.end());
                    raw.add(new Interval(start, end, "OVERTIME", o.reason()));
                }
            return merge(raw);
        }

        private static List<Interval> shift(LocalDate d, Pattern p) {
            var start = at(d, p.start());
            var end = start.plusMinutes(p.length());
            if (p.breakMinutes() <= 0) return List.of(new Interval(start, end, "SHIFT", p.code()));
            var breakStart = start.plusMinutes(p.breakOffset());
            var breakEnd = breakStart.plusMinutes(p.breakMinutes());
            var out = new ArrayList<Interval>();
            if (breakStart.isAfter(start)) out.add(new Interval(start, min(breakStart, end), "SHIFT", p.code()));
            if (breakEnd.isBefore(end)) out.add(new Interval(max(breakEnd, start), end, "SHIFT", p.code()));
            return out;
        }
    }

    Plan load(String tenant, Calendar calendar, LocalDate first, LocalDate last) {
        if (calendar.id() == null) return new Plan(true, List.of(), Map.of(), List.of());
        var patterns = s.list("""
                        SELECT p.code,p.start_time,p.end_time,p.break_start,p.break_minutes,p.weekdays
                        FROM shift_calendar_patterns cp
                        JOIN shift_patterns p ON p.tenant_id=cp.tenant_id AND p.id=cp.pattern_id
                        WHERE cp.tenant_id=? AND cp.calendar_id=?
                        ORDER BY p.start_time,p.code""", tenant, calendar.id()).stream()
                .map(r -> new Pattern(
                        str(r, "code"),
                        time(r.get("start_time")),
                        time(r.get("end_time")),
                        r.get("break_start") == null ? null : time(r.get("break_start")),
                        ((Number) r.get("break_minutes")).intValue(),
                        ((Number) r.get("weekdays")).intValue()))
                .toList();
        var closed = new HashMap<LocalDate, String>();
        var overtime = new ArrayList<Overtime>();
        for (var e : s.list(
                "SELECT exception_date,kind,start_time,end_time,reason FROM shift_calendar_exceptions WHERE tenant_id=? AND calendar_id=? AND exception_date BETWEEN ?::date AND ?::date ORDER BY exception_date,start_time",
                tenant,
                calendar.id(),
                first.toString(),
                last.toString())) {
            LocalDate date = LocalDate.parse(str(e, "exception_date"));
            if ("CLOSED".equals(e.get("kind"))) closed.put(date, str(e, "reason"));
            else overtime.add(new Overtime(date, time(e.get("start_time")), time(e.get("end_time")), str(e, "reason")));
        }
        return new Plan(false, patterns, closed, overtime);
    }

    // ------------------------------------------------------------------ interval helpers

    /** Sorts and unions overlapping or touching intervals; merged labels are joined, mixed kinds become MIXED. */
    static List<Interval> merge(List<Interval> intervals) {
        var sorted = intervals.stream()
                .filter(i -> i.end().isAfter(i.start()))
                .sorted(Comparator.comparing(Interval::start).thenComparing(Interval::end))
                .toList();
        var out = new ArrayList<Interval>();
        Interval current = null;
        var labels = new LinkedHashSet<String>();
        for (var i : sorted) {
            if (current != null && !i.start().isAfter(current.end())) {
                labels.add(i.label());
                current = new Interval(
                        current.start(),
                        max(current.end(), i.end()),
                        current.kind().equals(i.kind()) ? current.kind() : "MIXED",
                        String.join(" + ", labels));
            } else {
                if (current != null) out.add(current);
                labels.clear();
                labels.add(i.label());
                current = i;
            }
        }
        if (current != null) out.add(current);
        return out;
    }

    static List<Interval> clip(List<Interval> intervals, Instant from, Instant to) {
        return clip(
                intervals, from.atZone(ZONE).toOffsetDateTime(), to.atZone(ZONE).toOffsetDateTime());
    }

    static List<Interval> clip(List<Interval> intervals, OffsetDateTime from, OffsetDateTime to) {
        var out = new ArrayList<Interval>();
        for (var i : intervals) {
            var start = max(i.start(), from);
            var end = min(i.end(), to);
            if (end.isAfter(start)) out.add(new Interval(start, end, i.kind(), i.label()));
        }
        return out;
    }

    static OffsetDateTime startOf(LocalDate date) {
        return date.atStartOfDay(ZONE).toOffsetDateTime();
    }

    private static OffsetDateTime at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(ZONE).toOffsetDateTime();
    }

    private static OffsetDateTime min(OffsetDateTime a, OffsetDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private static OffsetDateTime max(OffsetDateTime a, OffsetDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    static LocalTime time(Object value) {
        if (value instanceof java.sql.Time t) return t.toLocalTime();
        if (value instanceof LocalTime t) return t;
        return LocalTime.parse(value.toString());
    }
}
