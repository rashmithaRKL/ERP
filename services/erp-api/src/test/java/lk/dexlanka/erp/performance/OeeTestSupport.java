package lk.dexlanka.erp.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.RoutingTestBase;
import lk.dexlanka.erp.routing.CalendarCommands;
import lk.dexlanka.erp.routing.CalendarService;
import lk.dexlanka.erp.routing.ShiftCalendarService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Fixture helpers for the V15 OEE tests: shift calendars through the V10 commands, and execution history (time logs,
 * quantity reports, downtime events, production actuals) inserted with explicit past timestamps, exactly as the
 * operation and downtime commands would have written them.
 */
abstract class OeeTestSupport extends RoutingTestBase {
    /** Monday 2 March 2026 (Asia/Colombo, UTC+05:30, no daylight saving). */
    static final LocalDate MON = LocalDate.of(2026, 3, 2);

    static final LocalDate TUE = MON.plusDays(1), WED = MON.plusDays(2), FRI = MON.plusDays(4);

    @Autowired
    ShiftCalendarService calendarAdmin;

    @Autowired
    OeeEngine engine;

    static OffsetDateTime at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(CalendarService.ZONE).toOffsetDateTime();
    }

    String pattern(String code, String start, String end, String breakStart, int breakMinutes, List<Integer> days) {
        return str(
                calendarAdmin.createPattern(
                        planner,
                        new CalendarCommands.ShiftPattern(
                                code, code + " shift", start, end, breakStart, breakMinutes, days),
                        id()),
                "id");
    }

    String calendar(String code, List<String> patterns, boolean plantDefault) {
        return str(
                calendarAdmin.createCalendar(
                        planner, new CalendarCommands.Calendar(code, code + " calendar", patterns, plantDefault), id()),
                "id");
    }

    void assign(String machine, String calendar) {
        calendarAdmin.assign(planner, new CalendarCommands.Assignment(machine, calendar), id());
    }

    void holiday(String calendar, LocalDate day) {
        calendarAdmin.addException(
                planner,
                calendar,
                new CalendarCommands.DateException(day.toString(), "CLOSED", null, null, "Holiday"),
                id());
    }

    String operation(String order, int seq) {
        return str(row("SELECT id FROM mo_operations WHERE tenant_id=? AND order_id=? AND seq=?", t, order, seq), "id");
    }

    /** A closed SETUP or RUN time log of {@code operation} on {@code machine}. */
    void log(
            String machine, String order, String operation, String activity, OffsetDateTime start, OffsetDateTime end) {
        update(
                "INSERT INTO mo_operation_time_logs(id,tenant_id,operation_id,order_id,machine_id,user_id,activity,crew_size,started_at,ended_at,end_reason) VALUES(?,?,?,?,?,?,?,1,?,?,?)",
                id(),
                t,
                operation,
                order,
                machine,
                operator.id(),
                activity,
                start,
                end,
                end == null ? null : "PAUSED");
    }

    void report(String machine, String order, String operation, String good, String scrap, OffsetDateTime at) {
        update(
                "INSERT INTO mo_operation_reports(id,tenant_id,operation_id,order_id,machine_id,good_qty,scrap_qty,notes,reported_by,created_at) VALUES(?,?,?,?,?,?,?,'',?,?)",
                id(),
                t,
                operation,
                order,
                machine,
                bd(good),
                bd(scrap),
                operator.id(),
                at);
    }

    void downtime(String machine, String reason, boolean planned, OffsetDateTime start, OffsetDateTime end) {
        update(
                "INSERT INTO downtime_events(id,tenant_id,number,machine_id,reason,planned,started_at,ended_at,status,reported_by) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id(),
                t,
                "DT-" + id().substring(0, 8),
                machine,
                reason,
                planned,
                start,
                end,
                end == null ? "OPEN" : "CLOSED",
                operator.id());
    }

    void actual(String order, String good, String scrap, OffsetDateTime at) {
        update(
                "INSERT INTO production_actuals(id,tenant_id,order_id,good_qty,scrap_qty,labour_cost,machine_cost,notes,created_at) VALUES(?,?,?,?,?,0,0,'',?)",
                id(),
                t,
                order,
                bd(good),
                bd(scrap),
                at);
    }

    Map<String, Object> live(String machine, LocalDate day) {
        return engine.live(t, List.of(machine), day, day, java.time.Instant.now())
                .get(machine)
                .get(day);
    }

    static void near(Object actual, double expected) {
        assertThat(actual).as("expected %s", expected).isNotNull();
        assertThat(Double.parseDouble(actual.toString())).isCloseTo(expected, within(0.00001));
    }

    static void nearMinutes(Object actual, double expected) {
        assertThat(actual).as("expected %s minutes", expected).isNotNull();
        assertThat(Double.parseDouble(actual.toString())).isCloseTo(expected, within(0.001));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    static List<String> codes(Map<String, Object> figures) {
        return list(figures.get("warnings")).stream().map(w -> str(w, "code")).toList();
    }
}
