package lk.dexlanka.erp.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lk.dexlanka.erp.scheduling.ScheduleEngine.Block;
import lk.dexlanka.erp.scheduling.ScheduleEngine.Input;
import lk.dexlanka.erp.scheduling.ScheduleEngine.Machine;
import lk.dexlanka.erp.scheduling.ScheduleEngine.Operation;
import lk.dexlanka.erp.scheduling.ScheduleEngine.Order;
import lk.dexlanka.erp.scheduling.ScheduleEngine.Pin;
import lk.dexlanka.erp.scheduling.ScheduleEngine.Placed;
import lk.dexlanka.erp.scheduling.ScheduleEngine.Span;
import org.junit.jupiter.api.Test;

/**
 * The scheduler's rules on synthetic input (no Spring, no database): minute 0 is the start of day 0; a "day shift"
 * works 06:00–10:00 and 10:30–14:00 (450 minutes) every day.
 */
class ScheduleEngineTest {
    static final long DAY = 1440;
    static final LocalDate D0 = LocalDate.of(2026, 3, 2);

    /** Day shifts 06:00–14:00 with a 30-minute break at 10:00 on days 0..n-1. */
    static List<Span> dayShifts(int days) {
        var out = new ArrayList<Span>();
        for (int d = 0; d < days; d++) {
            out.add(new Span(d * DAY + 360, d * DAY + 600));
            out.add(new Span(d * DAY + 630, d * DAY + 840));
        }
        return out;
    }

    static Machine machine(String id, List<Span> working) {
        return new Machine(id, id.toUpperCase(), working);
    }

    static Operation op(String id, int seq, String status, String machine, long remaining, String... alternates) {
        var candidates = new ArrayList<String>();
        candidates.add(machine);
        candidates.addAll(Arrays.asList(alternates));
        return new Operation(
                id,
                seq,
                "Op " + seq,
                status,
                machine,
                machine,
                candidates,
                BigDecimal.valueOf(remaining),
                BigDecimal.valueOf(remaining),
                BigDecimal.ZERO,
                status.equals("IN_PROGRESS"));
    }

    static Order order(String id, int rank, int dueDay, long created, String parent, Operation... ops) {
        return new Order(
                id,
                "MO-" + id,
                "RELEASED",
                rank,
                rank == 0 ? "URGENT" : rank == 1 ? "HIGH" : "NORMAL",
                D0.plusDays(dueDay),
                (dueDay + 1) * DAY,
                created,
                parent,
                ops.length > 0,
                null,
                List.of(ops),
                List.of());
    }

    static Input input(long now, int days, List<Machine> machines, List<Order> orders) {
        return new Input(now, now + days * DAY, true, machines, List.of(), orders, List.of());
    }

    static Placed placed(ScheduleEngine.Result r, String opId) {
        return r.operations().stream()
                .filter(p -> opId.equals(p.operation().id()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void operationsSplitAcrossBreaksAndDaysAndStartAtTheNextWorkingMinute() {
        var m = machine("m1", dayShifts(5));
        // 600 minutes from minute 0: 06:00–10:00 (240) + 10:30–14:00 (210) on day 0, then 150 minutes on day 1.
        var r = ScheduleEngine.schedule(
                input(0, 5, List.of(m), List.of(order("a", 2, 4, 1, null, op("a10", 10, "READY", "m1", 600)))));
        var p = placed(r, "a10");
        assertThat(p.start()).isEqualTo(360);
        assertThat(p.end()).isEqualTo(DAY + 360 + 150);
        assertThat(p.segments())
                .containsExactly(new Span(360, 600), new Span(630, 840), new Span(DAY + 360, DAY + 510));
        assertThat(p.minutes()).isEqualTo(600);
        // Fractional remaining minutes are scheduled as whole minutes (rounded up).
        assertThat(ScheduleEngine.minutes(new BigDecimal("18.01"))).isEqualTo(19);
        assertThat(ScheduleEngine.minutes(new BigDecimal("-3"))).isZero();
    }

    @Test
    void oneOperationAtATimeInPriorityThenDueDateThenCreationOrder() {
        var m = machine("m1", List.of(new Span(0, 10 * DAY)));
        var normalEarly = order("n1", 2, 3, 1, null, op("n1-10", 10, "READY", "m1", 100));
        var normalLaterDue = order("n2", 2, 5, 0, null, op("n2-10", 10, "READY", "m1", 100));
        var urgent = order("u", 0, 9, 9, null, op("u-10", 10, "READY", "m1", 100));
        var high = order("h", 1, 9, 8, null, op("h-10", 10, "READY", "m1", 100));
        var sameDueOlder = order("n0", 2, 3, 0, null, op("n0-10", 10, "READY", "m1", 100));
        var r = ScheduleEngine.schedule(
                input(0, 10, List.of(m), List.of(normalEarly, normalLaterDue, urgent, high, sameDueOlder)));
        assertThat(r.orders().stream().map(o -> o.order().id())).containsExactly("u", "h", "n0", "n1", "n2");
        assertThat(placed(r, "u-10").start()).isZero();
        assertThat(placed(r, "h-10").start()).isEqualTo(100);
        assertThat(placed(r, "n0-10").start()).isEqualTo(200);
        assertThat(placed(r, "n1-10").start()).isEqualTo(300);
        assertThat(placed(r, "n2-10").start()).isEqualTo(400);
    }

    @Test
    void operationsOfAnOrderRunInSequenceAndAParentWaitsForItsChildren() {
        var m1 = machine("m1", List.of(new Span(0, 10 * DAY)));
        var m2 = machine("m2", List.of(new Span(0, 10 * DAY)));
        // The parent is urgent, its child normal: the child inherits the urgency and is scheduled first.
        var parent = order("p", 0, 5, 1, null, op("p10", 10, "READY", "m2", 30), op("p20", 20, "PENDING", "m1", 10));
        var child = order("c", 2, 4, 2, "p", op("c10", 10, "READY", "m1", 120), op("c20", 20, "PENDING", "m2", 60));
        var r = ScheduleEngine.schedule(input(0, 10, List.of(m1, m2), List.of(parent, child)));
        assertThat(r.orders().stream().map(o -> o.order().id())).containsExactly("c", "p");
        assertThat(placed(r, "c10").start()).isZero();
        assertThat(placed(r, "c20").start()).isEqualTo(120); // waits for c10 although m2 is free
        assertThat(placed(r, "p10").start()).isEqualTo(180); // waits for the child order to finish
        assertThat(placed(r, "p20").start()).isEqualTo(210);
        assertThat(r.order("p").end()).isEqualTo(220);
    }

    @Test
    void runningOperationsContinueFirstForTheirRemainingMinutes() {
        var m = machine("m1", List.of(new Span(0, 10 * DAY)));
        var running = new Operation(
                "r10",
                10,
                "Running",
                "IN_PROGRESS",
                "m1",
                "m1",
                List.of("m1", "m2"),
                new BigDecimal("30.5"),
                new BigDecimal("100"),
                new BigDecimal("69.5"),
                true);
        var overrun = new Operation(
                "o10",
                10,
                "Overrun",
                "PAUSED",
                "m1",
                "m1",
                List.of("m1"),
                BigDecimal.ZERO,
                new BigDecimal("50"),
                new BigDecimal("80"),
                false);
        var normal = order("n", 2, 9, 0, null, running);
        var urgent = order("u", 0, 1, 1, null, op("u10", 10, "READY", "m1", 60));
        var paused = order("z", 2, 9, 5, null, overrun);
        var r = ScheduleEngine.schedule(input(100, 10, List.of(m), List.of(normal, urgent, paused)));
        // The running (normal) operation keeps its work center from "now" before the urgent order.
        assertThat(placed(r, "r10").start()).isEqualTo(100);
        assertThat(placed(r, "r10").minutes()).isEqualTo(31);
        assertThat(placed(r, "u10").start()).isEqualTo(131);
        // A paused operation past its planned minutes is placed with zero remaining minutes (never negative).
        assertThat(placed(r, "o10").minutes()).isZero();
    }

    @Test
    void anAlternateWorkCenterIsChosenWhenItFinishesEarliest() {
        var m1 = machine("m1", List.of(new Span(0, 10 * DAY)));
        var m2 = machine("m2", List.of(new Span(0, 10 * DAY)));
        var busy = order("b", 0, 9, 0, null, op("b10", 10, "READY", "m1", 300));
        var flexible = order("f", 2, 9, 1, null, op("f10", 10, "READY", "m1", 60, "m2"));
        var r = ScheduleEngine.schedule(input(0, 10, List.of(m1, m2), List.of(busy, flexible)));
        assertThat(placed(r, "f10").machineId()).isEqualTo("m2");
        assertThat(placed(r, "f10").alternate()).isTrue();
        assertThat(placed(r, "f10").start()).isZero();
        // Ties keep the current work center; with alternates off the operation waits for its own work center.
        var tie = ScheduleEngine.schedule(input(0, 10, List.of(m1, m2), List.of(flexible)));
        assertThat(placed(tie, "f10").machineId()).isEqualTo("m1");
        var off = ScheduleEngine.schedule(
                new Input(0, 10 * DAY, false, List.of(m1, m2), List.of(), List.of(busy, flexible), List.of()));
        assertThat(placed(off, "f10").machineId()).isEqualTo("m1");
        assertThat(placed(off, "f10").start()).isEqualTo(300);
    }

    @Test
    void blockedTimeIsSkippedAndALaterSmallOperationMayFillAnEarlierGap() {
        var m = machine("m1", dayShifts(3));
        var block = new Block("m1", "MAINTENANCE", "MWO-1", "Service", 400, 500, false);
        var first = order("a", 0, 2, 0, null, op("a10", 10, "READY", "m1", 200));
        var r = ScheduleEngine.schedule(
                new Input(0, 3 * DAY, true, List.of(m), List.of(block), List.of(first), List.of()));
        // 06:40–08:20 is blocked: 40 minutes before it, 100 after it to 10:00, the rest after the break.
        assertThat(placed(r, "a10").segments())
                .containsExactly(new Span(360, 400), new Span(500, 600), new Span(630, 690));
        // The urgent order's second step leaves m1 idle until minute 100: a later 80-minute operation fits that gap
        // entirely and is placed in it; a 30-minute one no longer fits the remaining 20 minutes and goes after.
        var m1 = machine("m1", List.of(new Span(0, 3 * DAY)));
        var m2 = machine("m2", List.of(new Span(0, 3 * DAY)));
        var urgent = order("u", 0, 2, 0, null, op("u10", 10, "READY", "m2", 100), op("u20", 20, "PENDING", "m1", 50));
        var fits = order("f", 2, 2, 1, null, op("f10", 10, "READY", "m1", 80));
        var tooLong = order("t", 2, 2, 2, null, op("t10", 10, "READY", "m1", 30));
        var g = ScheduleEngine.schedule(input(0, 3, List.of(m1, m2), List.of(urgent, fits, tooLong)));
        assertThat(placed(g, "u20").start()).isEqualTo(100);
        assertThat(placed(g, "f10").start()).isZero();
        assertThat(placed(g, "t10").start()).isEqualTo(150);
    }

    @Test
    void ordersWithoutRoutingAreListedOrScheduledFromTheirEstimate() {
        var m = machine("m1", List.of(new Span(0, 10 * DAY)));
        var none = new Order("x", "MO-x", "APPROVED", 2, "NORMAL", D0, DAY, 0, null, false, null, List.of(), List.of());
        var estimated = new Order(
                "e",
                "MO-e",
                "APPROVED",
                2,
                "NORMAL",
                D0,
                DAY,
                1,
                null,
                false,
                new BigDecimal("90"),
                List.of(new Operation(
                        null,
                        0,
                        "Order run",
                        "READY",
                        "m1",
                        "m1",
                        List.of("m1"),
                        new BigDecimal("90"),
                        new BigDecimal("90"),
                        BigDecimal.ZERO,
                        false)),
                List.of());
        var r = ScheduleEngine.schedule(input(0, 10, List.of(m), List.of(none, estimated)));
        assertThat(r.order("x").status()).isEqualTo(ScheduleEngine.NO_ROUTING);
        assertThat(r.order("x").end()).isNull();
        assertThat(r.order("e").status()).isEqualTo(ScheduleEngine.SCHEDULED);
        assertThat(r.order("e").end()).isEqualTo(90);
        assertThat(r.placed("e")).hasSize(1);
    }

    @Test
    void workBeyondTheHorizonIsNotPlacedAndLatenessIsDetected() {
        var m = machine("m1", dayShifts(2));
        var tooBig = order("t", 2, 0, 0, null, op("t10", 10, "READY", "m1", 1000), op("t20", 20, "PENDING", "m1", 10));
        var late = order("l", 2, 0, 1, null, op("l10", 10, "READY", "m1", 50));
        var onTime = order("o", 2, 5, 2, null, op("o10", 10, "READY", "m1", 50));
        var r = ScheduleEngine.schedule(input(0, 2, List.of(m), List.of(tooBig, late, onTime)));
        assertThat(r.order("t").status()).isEqualTo(ScheduleEngine.BEYOND_HORIZON);
        assertThat(r.placed("t")).isEmpty();
        assertThat(r.order("t").late()).isTrue();
        assertThat(r.order("t").latenessMinutes()).isEqualTo(DAY); // at least horizon end − due end
        // Day 0 has 450 minutes: "l" runs 06:00–06:50 on day 0 and is on time; "o" too.
        assertThat(r.order("l").late()).isFalse();
        var busy = order("b", 0, 9, 0, null, op("b10", 10, "READY", "m1", 440));
        var r2 = ScheduleEngine.schedule(input(0, 2, List.of(m), List.of(busy, late)));
        // "l" starts in the last ten minutes of day 0 and is split into day 1, past its due date.
        assertThat(placed(r2, "l10").start()).isEqualTo(830);
        assertThat(placed(r2, "l10").segments()).containsExactly(new Span(830, 840), new Span(DAY + 360, DAY + 400));
        assertThat(placed(r2, "l10").late()).isTrue();
        assertThat(r2.order("l").late()).isTrue();
        assertThat(r2.order("l").latenessMinutes()).isEqualTo(400);
    }

    @Test
    void pinsAreHonouredWhenFeasibleAndReleasedOnConflict() {
        var m1 = machine("m1", List.of(new Span(0, 10 * DAY)));
        var m2 = machine("m2", List.of(new Span(0, 10 * DAY)));
        var order =
                order("a", 2, 9, 0, null, op("a10", 10, "READY", "m1", 100), op("a20", 20, "PENDING", "m2", 50, "m1"));
        var other = order("u", 0, 9, 1, null, op("u10", 10, "READY", "m2", 400));
        // a20 pinned to m1 at 500: honoured; the urgent order keeps m2 and the pinned span stays free on m1.
        var pin = new Pin("p1", "a", "a20", "m1", 500);
        var r = ScheduleEngine.schedule(
                new Input(0, 10 * DAY, true, List.of(m1, m2), List.of(), List.of(order, other), List.of(pin)));
        assertThat(placed(r, "a20").machineId()).isEqualTo("m1");
        assertThat(placed(r, "a20").start()).isEqualTo(500);
        assertThat(placed(r, "a20").pinStatus()).isEqualTo(ScheduleEngine.HONOURED);
        assertThat(r.pins().get("p1").status()).isEqualTo(ScheduleEngine.HONOURED);
        // Pinned before a10 can end (a10 now runs 100–200 around the pinned span): released, placed by the rules.
        var early = new Pin("p2", "a", "a20", "m1", 50);
        var c = ScheduleEngine.schedule(
                new Input(0, 10 * DAY, true, List.of(m1, m2), List.of(), List.of(order, other), List.of(early)));
        assertThat(c.pins().get("p2").status()).isEqualTo(ScheduleEngine.CONFLICT);
        assertThat(c.pins().get("p2").note()).contains("after the pinned start");
        assertThat(placed(c, "a20").pinStatus()).isEqualTo(ScheduleEngine.CONFLICT);
        assertThat(placed(c, "a20").start()).isEqualTo(200);
        assertThat(placed(c, "a20").machineId()).isEqualTo("m1");
        // A pin on non-working time, in the past, or on a work center the operation cannot use is not honoured.
        var shifts = machine("m1", dayShifts(3));
        var offShift = ScheduleEngine.schedule(new Input(
                0,
                3 * DAY,
                true,
                List.of(shifts, m2),
                List.of(),
                List.of(order),
                List.of(new Pin("p3", "a", "a20", "m1", 610))));
        assertThat(offShift.pins().get("p3").status()).isEqualTo(ScheduleEngine.CONFLICT);
        assertThat(offShift.pins().get("p3").note()).contains("not working time");
        var past = ScheduleEngine.schedule(new Input(
                600,
                10 * DAY,
                true,
                List.of(m1, m2),
                List.of(),
                List.of(order),
                List.of(new Pin("p4", "a", "a20", "m2", 100))));
        assertThat(past.pins().get("p4").note()).contains("passed");
        var stranger = order("s", 2, 9, 0, null, op("s10", 10, "READY", "m2", 10));
        var wrong = ScheduleEngine.schedule(new Input(
                0,
                10 * DAY,
                true,
                List.of(m1, m2),
                List.of(),
                List.of(stranger),
                List.of(new Pin("p5", "s", "s10", "m1", 100))));
        assertThat(wrong.pins().get("p5").status()).isEqualTo(ScheduleEngine.CONFLICT);
        assertThat(placed(wrong, "s10").machineId()).isEqualTo("m2");
    }

    @Test
    void theSameInputAlwaysGivesTheSameSchedule() {
        var machines = List.of(machine("m1", dayShifts(6)), machine("m2", dayShifts(6)), machine("m3", dayShifts(6)));
        var orders = new ArrayList<Order>();
        for (int i = 0; i < 12; i++)
            orders.add(order(
                    "o" + i,
                    i % 3,
                    i % 4,
                    i % 5,
                    null,
                    op("o" + i + "-10", 10, "READY", "m" + (1 + i % 3), 37 + i * 11, "m" + (1 + (i + 1) % 3)),
                    op("o" + i + "-20", 20, "PENDING", "m" + (1 + (i + 2) % 3), 23 + i * 7)));
        var first = describe(ScheduleEngine.schedule(input(17, 6, machines, orders)));
        Collections.reverse(orders);
        var second = describe(ScheduleEngine.schedule(input(17, 6, machines, orders)));
        assertThat(second).isEqualTo(first);
        assertThat(first).isNotEmpty();
    }

    private static List<String> describe(ScheduleEngine.Result r) {
        return r.operations().stream()
                .map(p -> p.operation().id() + "@" + p.machineId() + ":" + p.start() + "-" + p.end() + p.segments())
                .sorted()
                .toList();
    }
}
