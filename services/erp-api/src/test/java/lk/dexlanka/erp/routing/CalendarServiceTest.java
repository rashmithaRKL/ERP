package lk.dexlanka.erp.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.RoutingTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V10 shift calendar maths ({@link CalendarService}): breaks, overnight shifts crossing midnight, holidays, overtime,
 * plant default vs assigned calendars and the around-the-clock fallback, plus the maintenance commands.
 */
class CalendarServiceTest extends RoutingTestBase {
    /** Monday 2 March 2026 (asserted below). */
    static final LocalDate MON = LocalDate.of(2026, 3, 2);

    static final LocalDate TUE = MON.plusDays(1), WED = MON.plusDays(2), FRI = MON.plusDays(4);
    static final LocalDate SAT = MON.plusDays(5), SUN = MON.plusDays(6), NEXT_MON = MON.plusDays(7);
    static final List<Integer> WEEKDAYS = List.of(1, 2, 3, 4, 5);

    @Autowired
    CalendarService calendars;

    @Autowired
    ShiftCalendarService admin;

    String day, night, centred, twoShift;

    @BeforeEach
    void shifts() {
        assertThat(MON.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        // Day 06:00–14:00 with a 30-minute break at 10:00 (450 min); night 22:00–06:00 with a break at 02:00 (450 min).
        day = pattern("DAY", "06:00", "14:00", "10:00", 30, WEEKDAYS);
        night = pattern("NIGHT", "22:00", "06:00", "02:00", 30, WEEKDAYS);
        centred = pattern("MID", "06:00", "14:00", null, 30, List.of(6));
        twoShift = calendar("TWO", List.of(day, night), true);
    }

    String pattern(String code, String start, String end, String breakStart, int breakMinutes, List<Integer> days) {
        return str(
                admin.createPattern(
                        planner,
                        new CalendarCommands.ShiftPattern(
                                code, code + " shift", start, end, breakStart, breakMinutes, days),
                        id()),
                "id");
    }

    String calendar(String code, List<String> patterns, boolean plantDefault) {
        return str(
                admin.createCalendar(
                        planner, new CalendarCommands.Calendar(code, code + " calendar", patterns, plantDefault), id()),
                "id");
    }

    void exception(String calendar, LocalDate date, String kind, String start, String end) {
        admin.addException(
                planner, calendar, new CalendarCommands.DateException(date.toString(), kind, start, end, "Test"), id());
    }

    long minutes(String machine, LocalDate date) {
        return calendars.availableMinutes(t, machine, date);
    }

    @Test
    void breaksAndOvernightShiftsSplitAcrossCalendarDays() {
        // Monday: day shift 450 + the first two hours of Monday's night shift.
        assertThat(minutes(bm1, MON)).isEqualTo(450 + 120);
        // Tuesday: Monday night after midnight (00:00–02:00, 02:30–06:00 = 330) + day 450 + Tuesday night 120.
        assertThat(minutes(bm1, TUE)).isEqualTo(330 + 450 + 120);
        // Saturday: only Friday night's tail; Sunday: nothing; next Monday: no Sunday night shift.
        assertThat(minutes(bm1, SAT)).isEqualTo(330);
        assertThat(minutes(bm1, SUN)).isZero();
        assertThat(minutes(bm1, NEXT_MON)).isEqualTo(570);

        var intervals = calendars.workingIntervals(t, bm1, MON, MON);
        assertThat(intervals)
                .extracting(i -> i.start().toLocalTime(), i -> i.end().toLocalTime(), CalendarService.Interval::label)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(6, 0), LocalTime.of(10, 0), "DAY"),
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(10, 30), LocalTime.of(14, 0), "DAY"),
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(22, 0), LocalTime.of(0, 0), "NIGHT"));
        assertThat(intervals.getFirst().start().getOffset().getTotalSeconds()).isEqualTo(5 * 3600 + 1800);

        // Monday night as one range: 22:00 Monday to 06:00 Tuesday, minus the 02:00 break.
        var nightOnly = calendars.workingIntervals(
                t,
                bm1,
                MON.atTime(20, 0).atZone(CalendarService.ZONE).toInstant(),
                TUE.atTime(9, 0).atZone(CalendarService.ZONE).toInstant());
        // 22:00–02:00, then 02:30–06:00 merged with Tuesday's day shift (they touch at 06:00), clipped at 09:00.
        assertThat(nightOnly).hasSize(2);
        assertThat(nightOnly.stream().mapToLong(CalendarService.Interval::minutes).sum())
                .isEqualTo(240 + 210 + 180);
        assertThat(nightOnly.get(1).start().toLocalTime()).isEqualTo(LocalTime.of(2, 30));
        assertThat(nightOnly.get(1).end().toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(nightOnly.get(1).label()).isEqualTo("NIGHT + DAY");
    }

    @Test
    void aBreakWithoutStartIsCentred() {
        String cal = calendar("SAT", List.of(centred), false);
        admin.assign(planner, new CalendarCommands.Assignment(bm2, cal), id());
        assertThat(minutes(bm2, SAT)).isEqualTo(450);
        assertThat(calendars.workingIntervals(t, bm2, SAT, SAT))
                .extracting(i -> i.start().toLocalTime(), i -> i.end().toLocalTime())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(6, 0), LocalTime.of(9, 45)),
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(10, 15), LocalTime.of(14, 0)));
    }

    @Test
    void holidaysCancelShiftsStartingThatDayAndOvertimeAddsTime() {
        exception(twoShift, TUE, "CLOSED", null, null);
        // Tuesday keeps Monday night's tail; Wednesday loses Tuesday night's tail.
        assertThat(minutes(bm1, TUE)).isEqualTo(330);
        assertThat(minutes(bm1, WED)).isEqualTo(450 + 120);
        assertThat(calendars.availability(t, bm1, TUE, TUE).days().getFirst().closed()).isTrue();

        // Saturday overtime 08:00–12:00 adds 240; overtime overlapping Monday's day shift (13:00–15:00) adds only 60.
        exception(twoShift, SAT, "OVERTIME", "08:00", "12:00");
        exception(twoShift, MON, "OVERTIME", "13:00", "15:00");
        assertThat(minutes(bm1, SAT)).isEqualTo(330 + 240);
        assertThat(minutes(bm1, MON)).isEqualTo(570 + 60);
        // Overtime crossing midnight (Sunday 22:00 – next Monday 02:00) counts on both days.
        exception(twoShift, SUN, "OVERTIME", "22:00", "02:00");
        assertThat(minutes(bm1, SUN)).isEqualTo(120);
        assertThat(minutes(bm1, NEXT_MON)).isEqualTo(570 + 120);
        // Overtime on a closed date still counts.
        exception(twoShift, TUE, "OVERTIME", "08:00", "10:00");
        assertThat(minutes(bm1, TUE)).isEqualTo(330 + 120);
    }

    @Test
    void assignedCalendarsOverrideThePlantDefaultAndNoCalendarMeansAroundTheClock() throws Exception {
        String saturdays = calendar("SATONLY", List.of(centred), false);
        assertThat(calendars.availability(t, pk1, MON, MON).source()).isEqualTo(CalendarService.Source.PLANT_DEFAULT);
        admin.assign(planner, new CalendarCommands.Assignment(pk1, saturdays), id());
        var assigned = calendars.availability(t, pk1, MON, SUN);
        assertThat(assigned.source()).isEqualTo(CalendarService.Source.ASSIGNED);
        assertThat(assigned.calendarCode()).isEqualTo("SATONLY");
        assertThat(assigned.minutes()).isEqualTo(450);
        admin.assign(planner, new CalendarCommands.Assignment(pk1, ""), id());
        assertThat(calendars.availability(t, pk1, MON, MON).source()).isEqualTo(CalendarService.Source.PLANT_DEFAULT);

        // Moving the plant default to another calendar.
        admin.createCalendar(planner, new CalendarCommands.Calendar("NEW", "New default", List.of(day), true), id());
        assertThat(count("SELECT COUNT(*) FROM shift_calendars WHERE tenant_id=? AND plant_default", t))
                .isEqualTo(1);
        assertThat(minutes(pk1, MON)).isEqualTo(450);

        // A tenant without any calendar: available around the clock.
        var other = createTenant();
        String machine = id();
        update(
                "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,'X','X','X','IDLE',1)",
                machine,
                other.id(),
                other.plant());
        var none = calendars.availability(other.id(), machine, MON, TUE);
        assertThat(none.source()).isEqualTo(CalendarService.Source.NONE);
        assertThat(none.minutes()).isEqualTo(2 * 1440);
        assertThat(calendars.workingIntervals(other.id(), machine, MON, TUE)).hasSize(1);
    }

    @Test
    void availableMinutesEndpoint() throws Exception {
        var one = json(query(
                        operator, "/api/calendars/available-minutes?machineId=" + bm1 + "&from=" + MON + "&to=" + SUN)
                .andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) one.get("workCenters");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("source", "PLANT_DEFAULT").containsEntry("calendar_code", "TWO");
        // Mon 570, Tue-Fri 900 (three full days and Friday's 900), Sat 330, Sun 0.
        assertThat(((Number) rows.getFirst().get("minutes")).longValue()).isEqualTo(570 + 900 * 4 + 330);
        @SuppressWarnings("unchecked")
        var days = (List<Map<String, Object>>) rows.getFirst().get("days");
        assertThat(days).hasSize(7);
        assertThat(days.getFirst()).containsEntry("date", MON.toString());
        OffsetDateTime first = OffsetDateTime.parse(
                str(((List<Map<String, Object>>) days.getFirst().get("intervals")).getFirst(), "start"));
        assertThat(first.toLocalTime()).isEqualTo(LocalTime.of(6, 0));

        var all = json(query(operator, "/api/calendars/available-minutes?from=" + MON + "&to=" + MON));
        assertThat(all.get("workCenters")).asList().hasSize(3);
        query(operator, "/api/calendars/available-minutes?from=" + MON + "&to=" + MON.plusDays(70))
                .andExpect(status().isConflict());
        query(operator, "/api/calendars/available-minutes?from=x&to=" + MON).andExpect(status().isBadRequest());
        var other = createUser(createTenant().id(), "PLANNER");
        query(other, "/api/calendars/available-minutes?machineId=" + bm1 + "&from=" + MON + "&to=" + MON)
                .andExpect(status().isNotFound());
        assertThat(FRI.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
    }

    @Test
    void maintenanceRulesPermissionsAndIsolation() throws Exception {
        var pattern = new CalendarCommands.ShiftPattern("EVE", "Evening", "14:00", "22:00", "21:50", 30, WEEKDAYS);
        command(planner, "/api/calendars/patterns", pattern).andExpect(status().isConflict()); // break past the end
        command(
                        planner,
                        "/api/calendars/patterns",
                        new CalendarCommands.ShiftPattern("EVE", "Evening", "14:00", "22:00", "18:00", 30, WEEKDAYS))
                .andExpect(status().isOk());
        command(
                        planner,
                        "/api/calendars/patterns",
                        new CalendarCommands.ShiftPattern("eve", "Again", "14:00", "22:00", null, 0, WEEKDAYS))
                .andExpect(status().isConflict()); // codes are unique (upper-cased)
        command(
                        planner,
                        "/api/calendars/patterns",
                        new CalendarCommands.ShiftPattern("BAD", "Bad", "25:00", "22:00", null, 0, WEEKDAYS))
                .andExpect(status().isBadRequest());
        command(operator, "/api/calendars/patterns", pattern).andExpect(status().isForbidden());

        exception(twoShift, MON, "CLOSED", null, null);
        command(
                        planner,
                        "/api/calendars/" + twoShift + "/exceptions",
                        new CalendarCommands.DateException(MON.toString(), "CLOSED", null, null, "Again"))
                .andExpect(status().isConflict());
        command(
                        planner,
                        "/api/calendars/" + twoShift + "/exceptions",
                        new CalendarCommands.DateException(MON.toString(), "OVERTIME", "08:00", null, "No end"))
                .andExpect(status().isConflict());
        String exceptionId = str(
                row("SELECT id FROM shift_calendar_exceptions WHERE tenant_id=? AND calendar_id=?", t, twoShift), "id");
        command(planner, "/api/calendars/" + twoShift + "/exceptions/" + exceptionId + "/remove", null)
                .andExpect(status().isOk());
        assertThat(minutes(bm1, MON)).isEqualTo(570);

        var overview = json(query(viewer, "/api/calendars").andExpect(status().isOk()));
        assertThat(overview.get("calendars")).asList().hasSize(1);
        assertThat(overview.get("patterns")).asList().hasSize(4);
        assertThat(overview.get("workCenters")).asList().hasSize(3);
        json(query(viewer, "/api/calendars/" + twoShift).andExpect(status().isOk()));

        var outsider = createUser(createTenant().id(), "ADMIN");
        query(outsider, "/api/calendars/" + twoShift).andExpect(status().isNotFound());
        command(outsider, "/api/calendars/assignments", new CalendarCommands.Assignment(bm1, ""))
                .andExpect(status().isNotFound());
        command(outsider, "/api/calendars/" + twoShift + "/exceptions", new CalendarCommands.DateException(
                        MON.toString(), "CLOSED", null, null, "x"))
                .andExpect(status().isNotFound());
        assertThat(rows("SELECT action FROM audit_events WHERE tenant_id=? AND reference='TWO' ORDER BY created_at", t))
                .extracting(e -> e.get("action"))
                .containsExactly("CALENDAR_CREATED", "CALENDAR_EXCEPTION_ADDED", "CALENDAR_EXCEPTION_REMOVED");
    }
}
