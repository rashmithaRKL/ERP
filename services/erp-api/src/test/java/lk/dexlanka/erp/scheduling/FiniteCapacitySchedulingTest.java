package lk.dexlanka.erp.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import lk.dexlanka.erp.RoutingTestBase;
import lk.dexlanka.erp.manufacturing.OperationCommands;
import lk.dexlanka.erp.manufacturing.OperationService;
import lk.dexlanka.erp.routing.CalendarCommands;
import lk.dexlanka.erp.routing.CalendarService;
import lk.dexlanka.erp.routing.ShiftCalendarService;
import lk.dexlanka.erp.scheduling.ScheduleEngine.Span;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The scheduler on real data: shift calendars through {@link CalendarService} (breaks, overnight shifts, a holiday,
 * overtime), maintenance windows and open downtime, operations in progress, routing alternates, orders without a
 * routing and determinism. "Now" is chosen by the test ({@link ScheduleService#compute}).
 */
class FiniteCapacitySchedulingTest extends RoutingTestBase {
    @Autowired
    ScheduleService service;

    @Autowired
    ScheduleInputs inputs;

    @Autowired
    ShiftCalendarService calendarAdmin;

    @Autowired
    OperationService operations;

    static final ScheduleInputs.Params NO_ALTERNATES = new ScheduleInputs.Params(28, false, 8, false);

    /** A Monday at least two weeks ahead, so "now" never depends on today's shifts. */
    static LocalDate monday() {
        return LocalDate.now(CalendarService.ZONE).plusDays(14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    static long at(LocalDate date, String time) {
        return date.atTime(LocalTime.parse(time)).atZone(CalendarService.ZONE).toEpochSecond() / 60;
    }

    String pattern(String code, String start, String end, String breakStart, List<Integer> days) {
        return str(
                calendarAdmin.createPattern(
                        planner,
                        new CalendarCommands.ShiftPattern(code, code + " shift", start, end, breakStart, 30, days),
                        id()),
                "id");
    }

    String calendar(String code, List<String> patterns, boolean plantDefault) {
        return str(
                calendarAdmin.createCalendar(
                        planner, new CalendarCommands.Calendar(code, code + " calendar", patterns, plantDefault), id()),
                "id");
    }

    void exception(String calendar, LocalDate date, String kind, String start, String end) {
        calendarAdmin.addException(
                planner,
                calendar,
                new CalendarCommands.DateException(date.toString(), kind, start, end, "Test " + kind),
                id());
    }

    String operation(String order, int seq) {
        return str(row("SELECT id FROM mo_operations WHERE tenant_id=? AND order_id=? AND seq=?", t, order, seq), "id");
    }

    static ScheduleEngine.Placed placed(ScheduleEngine.Result r, String operationId) {
        return r.operations().stream()
                .filter(p -> operationId.equals(p.operation().id()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void shiftBreaksHolidaysOvertimeAndOvernightShiftsShapeThePlacement() {
        LocalDate mon = monday();
        LocalDate tue = mon.plusDays(1);
        LocalDate wed = mon.plusDays(2);
        var weekdays = List.of(1, 2, 3, 4, 5);
        String plant = calendar("PLANT", List.of(pattern("DAY", "06:00", "14:00", "10:00", weekdays)), true);
        String nights = calendar("NIGHTS", List.of(pattern("NIGHT", "22:00", "06:00", "02:00", weekdays)), false);
        calendarAdmin.assign(planner, new CalendarCommands.Assignment(bm2, nights), id());
        exception(plant, tue, "CLOSED", null, null);
        // 1,000 bottles: 600 minutes of blow moulding (BM-1, alternate BM-2), then 10 minutes of packing.
        approvedRouting(
                bottle,
                "R1",
                op(10, "Blow", bm1, List.of(bm2), "0", "0.6", "1", null, null, false),
                op(20, "Pack", pk1, List.of(), "0", "0.01", "1", null, null, false));
        String day = releasedOrder("1000", bm1, "HIGH");
        String night = releasedOrder("1000", bm2, "NORMAL");
        long now = at(mon, "05:00");

        var r = service.compute(t, NO_ALTERNATES, now);
        // Day shift: 06:00–10:00 and 10:30–14:00 on Monday (450), Tuesday is a holiday, 150 minutes on Wednesday.
        var blow = placed(r, operation(day, 10));
        assertThat(blow.segments())
                .containsExactly(
                        new Span(at(mon, "06:00"), at(mon, "10:00")),
                        new Span(at(mon, "10:30"), at(mon, "14:00")),
                        new Span(at(wed, "06:00"), at(wed, "08:30")));
        var pack = placed(r, operation(day, 20));
        assertThat(pack.start()).isEqualTo(at(wed, "08:30"));
        assertThat(pack.end()).isEqualTo(at(wed, "08:40"));
        // Night calendar on BM-2: 22:00–02:00 and 02:30–06:00 across midnight, then Tuesday night (no holiday there).
        var overnight = placed(r, operation(night, 10));
        assertThat(overnight.machineId()).isEqualTo(bm2);
        assertThat(overnight.segments())
                .containsExactly(
                        new Span(at(mon, "22:00"), at(tue, "02:00")),
                        new Span(at(tue, "02:30"), at(tue, "06:00")),
                        new Span(at(tue, "22:00"), at(wed, "00:30")));

        // Overtime on the holiday adds working time: the blow moulding finishes on Tuesday morning instead.
        exception(plant, tue, "OVERTIME", "08:00", "12:00");
        var withOvertime = service.compute(t, NO_ALTERNATES, now);
        assertThat(placed(withOvertime, operation(day, 10)).segments().getLast())
                .isEqualTo(new Span(at(tue, "08:00"), at(tue, "10:30")));
        assertThat(placed(withOvertime, operation(day, 20)).start()).isEqualTo(at(tue, "10:30"));
    }

    @Test
    void maintenanceWindowsAndOpenDowntimeBlockTheirWorkCenter() {
        approvedRouting(bottle, "R1", op(10, "Blow", bm1, List.of(), "0", "0.6", "1", null, null, false));
        String order = releasedOrder("1000", bm1, "NORMAL");
        long now = ScheduleService.now();
        String window = id();
        update(
                "INSERT INTO maintenance_work_orders(id,tenant_id,number,type,machine_id,title,status,scheduled_start,scheduled_end,requested_by) VALUES(?,?,?,'CORRECTIVE',?,'Service','SCHEDULED',?,?,?)",
                window,
                t,
                "MWO-" + window.substring(0, 6),
                bm1,
                ScheduleInputs.at(now + 60),
                ScheduleInputs.at(now + 180),
                planner.id());
        String breakdown = id();
        update(
                "INSERT INTO downtime_events(id,tenant_id,number,machine_id,reason,planned,started_at,status,reported_by) VALUES(?,?,?,?,'BREAKDOWN',false,?,'OPEN',?)",
                breakdown,
                t,
                "DT-" + breakdown.substring(0, 6),
                pk1,
                ScheduleInputs.at(now - 30),
                operator.id());

        var r = service.compute(t, ScheduleInputs.Params.defaults(), now);
        // No calendar = around the clock; the maintenance window splits the operation.
        assertThat(placed(r, operation(order, 10)).segments())
                .containsExactly(new Span(now, now + 60), new Span(now + 180, now + 720));
        // The open breakdown blocks PK-1 from now for the assumed 8 hours (2 hours when the run says so).
        var input = inputs.load(t, ScheduleInputs.Params.defaults(), now);
        var down = input.blocks().stream()
                .filter(b -> b.kind().equals("DOWNTIME"))
                .findFirst()
                .orElseThrow();
        assertThat(down.machineId()).isEqualTo(pk1);
        assertThat(down.start()).isEqualTo(now);
        assertThat(down.end()).isEqualTo(now + 480);
        assertThat(down.assumedEnd()).isTrue();
        var shortBreakdown = inputs.load(t, new ScheduleInputs.Params(28, true, 2, false), now).blocks().stream()
                .filter(b -> b.kind().equals("DOWNTIME"))
                .findFirst()
                .orElseThrow();
        assertThat(shortBreakdown.end()).isEqualTo(now + 120);
        // Linked to a work order whose window ends later: blocked until the window ends.
        String repair = id();
        update(
                "INSERT INTO maintenance_work_orders(id,tenant_id,number,type,machine_id,title,status,scheduled_start,scheduled_end,requested_by) VALUES(?,?,?,'CORRECTIVE',?,'Repair','SCHEDULED',?,?,?)",
                repair,
                t,
                "MWO-" + repair.substring(0, 6),
                pk1,
                ScheduleInputs.at(now - 30),
                ScheduleInputs.at(now + 600),
                planner.id());
        update("UPDATE downtime_events SET work_order_id=? WHERE tenant_id=? AND id=?", repair, t, breakdown);
        var linked = inputs.load(t, ScheduleInputs.Params.defaults(), now).blocks().stream()
                .filter(b -> b.kind().equals("DOWNTIME"))
                .findFirst()
                .orElseThrow();
        assertThat(linked.end()).isEqualTo(now + 600);
        assertThat(linked.assumedEnd()).isFalse();
        // A closed downtime in the past does not block.
        update(
                "UPDATE downtime_events SET status='CLOSED',ended_at=?,closed_by=?,closed_at=now() WHERE tenant_id=? AND id=?",
                ScheduleInputs.at(now - 10),
                operator.id(),
                t,
                breakdown);
        assertThat(inputs.load(t, ScheduleInputs.Params.defaults(), now).blocks())
                .noneMatch(b -> b.kind().equals("DOWNTIME"));
    }

    @Test
    void operationsInProgressContinueForPlannedMinusActualMinutes() {
        approvedRouting(bottle, "R1", op(10, "Blow", bm1, List.of(), "0", "0.6", "1", null, null, false));
        String running = releasedOrder("1000", bm1, "NORMAL");
        String urgent = releasedOrder("100", bm1, "URGENT");
        String blow = operation(running, 10);
        operations.start(operator, blow, new OperationCommands.Start(null, "RUN", null), id());
        update(
                "UPDATE mo_operation_time_logs SET started_at=now()-interval '120 minutes' WHERE tenant_id=? AND operation_id=? AND ended_at IS NULL",
                t,
                blow);
        long now = ScheduleService.now();
        var r = service.compute(t, ScheduleInputs.Params.defaults(), now);
        var p = placed(r, blow);
        // 600 planned − 120 actual: about 480 minutes left from now, ahead of the urgent order.
        assertThat(p.operation().running()).isTrue();
        assertThat(p.start()).isEqualTo(now);
        assertThat(p.minutes()).isBetween(479L, 481L);
        assertThat(placed(r, operation(urgent, 10)).start()).isEqualTo(p.end());
        // Past its planned minutes the remaining time is zero, never negative.
        update(
                "UPDATE mo_operation_time_logs SET started_at=now()-interval '900 minutes' WHERE tenant_id=? AND operation_id=? AND ended_at IS NULL",
                t,
                blow);
        var over = placed(service.compute(t, ScheduleInputs.Params.defaults(), ScheduleService.now()), blow);
        assertThat(over.minutes()).isZero();
        assertThat(over.operation().remaining()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void alternatesFinishEarliestAndOrdersWithoutRoutingNeedAnEstimate() {
        approvedRouting(bottle, "R1", op(10, "Blow", bm1, List.of(bm2), "0", "0.6", "1", null, null, false));
        String first = releasedOrder("1000", bm1, "URGENT");
        String second = releasedOrder("1000", bm1, "NORMAL");
        long now = ScheduleService.now();
        var r = service.compute(t, ScheduleInputs.Params.defaults(), now);
        assertThat(placed(r, operation(first, 10)).machineId()).isEqualTo(bm1);
        var alt = placed(r, operation(second, 10));
        assertThat(alt.machineId()).isEqualTo(bm2);
        assertThat(alt.alternate()).isTrue();
        assertThat(alt.start()).isEqualTo(now);
        var off = service.compute(t, NO_ALTERNATES, now);
        assertThat(placed(off, operation(second, 10)).machineId()).isEqualTo(bm1);
        assertThat(placed(off, operation(second, 10)).start()).isEqualTo(now + 600);

        // A bottle order created before the routing existed has no operations: listed until a planner estimates it.
        update("DELETE FROM routing_defaults WHERE tenant_id=? AND product_id=?", t, bottle);
        String legacy = releasedOrder("100", pk1, "NORMAL");
        var listed = service.compute(t, ScheduleInputs.Params.defaults(), now).order(legacy);
        assertThat(listed.status()).isEqualTo(ScheduleEngine.NO_ROUTING);
        service.estimate(planner, new ScheduleCommands.Estimate(legacy, new BigDecimal("90"), "Two pallets"), id());
        var estimated = service.compute(t, ScheduleInputs.Params.defaults(), now);
        assertThat(estimated.order(legacy).status()).isEqualTo(ScheduleEngine.SCHEDULED);
        var run = estimated.placed(legacy).getFirst();
        assertThat(run.operation().id()).isNull();
        assertThat(run.machineId()).isEqualTo(pk1);
        assertThat(run.end() - run.start()).isEqualTo(90);
    }

    @Test
    void theSameDataAndNowGiveTheSameSchedule() {
        approvedRouting(
                bottle,
                "R1",
                op(10, "Blow", bm1, List.of(bm2), "15", "0.05", "1", null, null, false),
                op(20, "Pack", pk1, List.of(), "5", "0.01", "1", null, null, false));
        for (String priority : List.of("NORMAL", "HIGH", "URGENT", "NORMAL", "HIGH"))
            releasedOrder("1000", bm1, priority);
        long now = ScheduleService.now();
        var a = service.compute(t, ScheduleInputs.Params.defaults(), now);
        var b = service.compute(t, ScheduleInputs.Params.defaults(), now);
        assertThat(describe(b)).isEqualTo(describe(a)).hasSize(10);
    }

    private static List<String> describe(ScheduleEngine.Result r) {
        return r.operations().stream()
                .map(p -> p.operation().id() + "@" + p.machineId() + ":" + p.start() + "-" + p.end())
                .toList();
    }
}
