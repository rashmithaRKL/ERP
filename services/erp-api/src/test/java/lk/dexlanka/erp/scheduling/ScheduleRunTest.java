package lk.dexlanka.erp.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.RoutingTestBase;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.OperationCommands;
import lk.dexlanka.erp.manufacturing.OperationService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.routing.CalendarCommands;
import lk.dexlanka.erp.routing.CalendarService;
import lk.dexlanka.erp.routing.ShiftCalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * Schedule runs end to end through the HTTP API: draft → publish → superseded (immutable), late orders, capacity
 * load, comparison, pins (validated, honoured, removed), duration estimates, the {@code schedule} state key, role
 * permissions, idempotent retries and tenant isolation. The fixture tenant has no shift calendar (around the clock)
 * unless a test adds one.
 */
class ScheduleRunTest extends RoutingTestBase {
    @Autowired
    OperationService operations;

    @Autowired
    ShiftCalendarService calendarAdmin;

    static final Map<String, Object> DEFAULTS = Map.of();

    /** 1,000 bottles: 600 minutes on BM-1 (alternate BM-2), then 10 minutes on PK-1. */
    void routing() {
        approvedRouting(
                bottle,
                "R1",
                op(10, "Blow", bm1, List.of(bm2), "0", "0.6", "1", null, null, false),
                op(20, "Pack", pk1, List.of(), "0", "0.01", "1", null, null, false));
    }

    String operation(String order, int seq) {
        return str(row("SELECT id FROM mo_operations WHERE tenant_id=? AND order_id=? AND seq=?", t, order, seq), "id");
    }

    String run(Actor actor) throws Exception {
        return str(json(command(actor, "/api/schedule/runs", DEFAULTS).andExpect(status().isOk())), "id");
    }

    /** A run that keeps every operation on its own work center (no alternates). */
    String fixedRun() throws Exception {
        return str(
                json(command(planner, "/api/schedule/runs", Map.of("useAlternates", false))
                        .andExpect(status().isOk())),
                "id");
    }

    String runStatus(String run) {
        return str(row("SELECT status FROM schedule_runs WHERE tenant_id=? AND id=?", t, run), "status");
    }

    Map<String, Object> scheduled(String run, String operationId) {
        return row(
                "SELECT * FROM schedule_run_operations WHERE tenant_id=? AND run_id=? AND operation_id=?",
                t,
                run,
                operationId);
    }

    /** {@code now + minutes} as a local Asia/Colombo date-time text (the UI's pin format). */
    static String local(long minutesFromNow) {
        return ScheduleEngine.local(ScheduleService.now() + minutesFromNow).replace(' ', 'T');
    }

    @Test
    void draftsArePublishedOneAtATimeAndFrozen() throws Exception {
        routing();
        releasedOrder("1000", bm1, "NORMAL");
        releasedOrder("1000", bm1, "HIGH");
        String first = run(planner);
        assertThat(runStatus(first)).isEqualTo("DRAFT");
        assertThat(count("SELECT COUNT(*) FROM schedule_run_operations WHERE tenant_id=? AND run_id=?", t, first))
                .isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM schedule_run_orders WHERE tenant_id=? AND run_id=?", t, first))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='SCHEDULE_RUN_CREATED'", t))
                .isEqualTo(1);
        command(planner, "/api/schedule/runs/" + first + "/publish", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        String second = run(admin);
        assertThat(str(row("SELECT base_run_id FROM schedule_runs WHERE id=?", second), "base_run_id"))
                .isEqualTo(first);
        command(planner, "/api/schedule/runs/" + second + "/publish", null).andExpect(status().isOk());
        assertThat(runStatus(first)).isEqualTo("SUPERSEDED");
        assertThat(str(row("SELECT superseded_by_run_id FROM schedule_runs WHERE id=?", first), "superseded_by_run_id"))
                .isEqualTo(second);
        assertThat(count("SELECT COUNT(*) FROM schedule_runs WHERE tenant_id=? AND status='PUBLISHED'", t))
                .isEqualTo(1);

        // Only the newest draft can be published; publishing it discards older drafts.
        String older = run(planner);
        String newer = run(planner);
        command(planner, "/api/schedule/runs/" + older + "/publish", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("newer schedule run")));
        command(planner, "/api/schedule/runs/" + newer + "/publish", null).andExpect(status().isOk());
        assertThat(runStatus(older)).isEqualTo("DISCARDED");
        assertThat(runStatus(second)).isEqualTo("SUPERSEDED");
        command(planner, "/api/schedule/runs/" + newer + "/publish", null).andExpect(status().isConflict());
        command(planner, "/api/schedule/runs/" + newer + "/discard", null).andExpect(status().isConflict());
        String draft = run(planner);
        command(planner, "/api/schedule/runs/" + draft + "/discard", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCARDED"));
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='SCHEDULE_DISCARDED'", t))
                .isEqualTo(1);

        // Frozen results: superseded runs, run rows and published results never change; nothing is deleted.
        assertThatThrownBy(() -> update("UPDATE schedule_runs SET status='PUBLISHED' WHERE id=?", first))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> update("UPDATE schedule_runs SET late_orders=99 WHERE id=?", newer))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("frozen result");
        assertThatThrownBy(() -> update("UPDATE schedule_runs SET status='DRAFT' WHERE id=?", newer))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("superseded");
        assertThatThrownBy(() -> update("UPDATE schedule_run_operations SET minutes=1 WHERE run_id=?", newer))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> update("DELETE FROM schedule_run_orders WHERE run_id=?", first))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> update("DELETE FROM schedule_runs WHERE id=?", draft))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void lateOrdersCapacityLoadAndComparison() throws Exception {
        routing();
        // 5,000 bottles due today: 3,000 minutes of blow moulding cannot finish today.
        String late = str(
                mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                bm1,
                                null,
                                bd("5000"),
                                "NORMAL",
                                LocalDate.now(CalendarService.ZONE).toString()),
                        id()),
                "id");
        mo.transition(otherPlanner, late, "approve", id());
        String onTime = releasedOrder("1000", bm1, "NORMAL");
        String base = fixedRun();
        command(planner, "/api/schedule/runs/" + base + "/publish", null).andExpect(status().isOk());

        var lateList =
                json(query(planner, "/api/schedule/runs/" + base + "/late").andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) lateList.get("items");
        assertThat(items).extracting(r -> r.get("order_id")).containsExactly(late);
        assertThat(((Number) items.getFirst().get("lateness_minutes")).longValue())
                .isPositive();
        assertThat(items.getFirst().get("projected_end")).isNotNull();
        assertThat(row("SELECT late FROM schedule_run_orders WHERE run_id=? AND order_id=?", base, onTime)
                        .get("late"))
                .isEqualTo(false);
        assertThat(count(
                        "SELECT COUNT(*) FROM schedule_run_operations WHERE run_id=? AND order_id=? AND late",
                        base,
                        late))
                .isEqualTo(2);

        // Capacity: around the clock = 1,440 available minutes a day; the scheduled minutes add up per work center.
        var capacity =
                json(query(planner, "/api/schedule/runs/" + base + "/capacity").andExpect(status().isOk()));
        long scheduledTotal = 0;
        for (var machine : rowsOf(capacity, "machines")) {
            long machineMinutes = 0;
            for (var day : rowsOf(machine, "days")) {
                assertThat(((Number) day.get("available_minutes")).longValue()).isEqualTo(1440);
                assertThat(day.get("overloaded")).isEqualTo(false);
                machineMinutes += ((Number) day.get("scheduled_minutes")).longValue();
            }
            assertThat(((Number) machine.get("scheduled_minutes")).longValue()).isEqualTo(machineMinutes);
            scheduledTotal += machineMinutes;
        }
        assertThat(scheduledTotal)
                .isEqualTo(count("SELECT COALESCE(SUM(minutes),0) FROM schedule_run_operations WHERE run_id=?", base));
        assertThat(scheduledTotal).isEqualTo(3000 + 50 + 600 + 10);
        // A calendar added after the run removes working time: the published load now overflows some days.
        String day = str(
                calendarAdmin.createPattern(
                        planner,
                        new CalendarCommands.ShiftPattern(
                                "D", "Day", "06:00", "14:00", "10:00", 30, List.of(1, 2, 3, 4, 5)),
                        id()),
                "id");
        calendarAdmin.createCalendar(planner, new CalendarCommands.Calendar("P", "Plant", List.of(day), true), id());
        var tighter =
                json(query(planner, "/api/schedule/runs/" + base + "/capacity").andExpect(status().isOk()));
        assertThat(rowsOf(tighter, "machines").stream()
                        .flatMap(m -> rowsOf(m, "days").stream())
                        .anyMatch(d -> Boolean.TRUE.equals(d.get("overloaded"))))
                .isTrue();

        // Compare a new draft with the published run: the urgent order is new and pushes the others later.
        update("DELETE FROM shift_calendar_patterns WHERE tenant_id=?", t);
        update("DELETE FROM shift_calendars WHERE tenant_id=?", t);
        String urgent = releasedOrder("1000", bm1, "URGENT");
        String next = fixedRun();
        var diff =
                json(query(planner, "/api/schedule/runs/" + next + "/compare").andExpect(status().isOk()));
        assertThat(str((Map<String, Object>) diff.get("base"), "id")).isEqualTo(base);
        var changes = rowsOf(diff, "orders");
        assertThat(changes).anySatisfy(r -> {
            assertThat(r.get("order_id")).isEqualTo(urgent);
            assertThat(r.get("change")).isEqualTo("NEW");
        });
        assertThat(changes).anySatisfy(r -> {
            assertThat(r.get("order_id")).isEqualTo(late);
            assertThat(r.get("change")).isEqualTo("LATER");
            assertThat(((Number) r.get("delta_minutes")).longValue()).isPositive();
        });
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) diff.get("summary");
        assertThat(((Number) summary.get("moved")).intValue()).isPositive();
        assertThat(((Number) summary.get("new")).intValue()).isEqualTo(2); // both operations of the urgent order

        // Gantt: one row per work center with its bars in the window.
        var gantt = json(query(planner, "/api/schedule/runs/" + next + "/gantt").andExpect(status().isOk()));
        assertThat(rowsOf(gantt, "machines")).extracting(m -> m.get("id")).contains(bm1, bm2, pk1);
        assertThat(rowsOf(gantt, "machines").stream()
                        .filter(m -> m.get("id").equals(bm1))
                        .flatMap(m -> rowsOf(m, "bars").stream())
                        .map(b -> b.get("order_id")))
                .contains(urgent);
        query(planner, "/api/schedule/runs/" + next + "/gantt?from=2026-01-01&to=2026-12-31")
                .andExpect(status().isConflict());
    }

    @Test
    void pinsAreValidatedHonouredReplacedAndRemoved() throws Exception {
        routing();
        String a = releasedOrder("1000", bm1, "NORMAL");
        String b = releasedOrder("1000", bm1, "NORMAL");
        String pack = operation(a, 20);
        // Pinned 12 hours ahead (op 10 ends after 600 minutes) with a re-run: honoured at the pinned start.
        var pinned = json(command(
                        planner,
                        "/api/schedule/pins",
                        new ScheduleCommands.Pin(null, pack, pk1, local(720), "Customer collection slot", true))
                .andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var rerun = (Map<String, Object>) pinned.get("run");
        String run = str(rerun, "id");
        var op = scheduled(run, pack);
        assertThat(op.get("pin_status")).isEqualTo("HONOURED");
        assertThat(ScheduleInputs.floor(op.get("start_at"))).isEqualTo(ScheduleInputs.floor(str(pinned, "start")));
        assertThat(count("SELECT COUNT(*) FROM schedule_pins WHERE tenant_id=? AND removed_at IS NULL", t))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='SCHEDULE_PINNED'", t))
                .isEqualTo(1);

        // Before operation 10 can finish, on a work center the operation cannot use, in the past: refused.
        command(planner, "/api/schedule/pins", new ScheduleCommands.Pin(null, pack, pk1, local(30), "", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cannot be honoured")));
        command(planner, "/api/schedule/pins", new ScheduleCommands.Pin(null, pack, bm2, local(720), "", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cannot run on")));
        command(planner, "/api/schedule/pins", new ScheduleCommands.Pin(null, pack, pk1, local(-120), "", false))
                .andExpect(status().isConflict());
        command(planner, "/api/schedule/pins", new ScheduleCommands.Pin(null, pack, pk1, "tomorrow", "", false))
                .andExpect(status().isBadRequest());
        // Overlapping another pinned operation: refused.
        command(
                        planner,
                        "/api/schedule/pins",
                        new ScheduleCommands.Pin(null, operation(b, 20), pk1, local(725), "", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("overlaps")));
        // A started operation cannot be pinned.
        String blow = operation(a, 10);
        operations.start(operator, blow, new OperationCommands.Start(null, "RUN", null), id());
        command(planner, "/api/schedule/pins", new ScheduleCommands.Pin(null, blow, bm1, local(60), "", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not started")));

        // Pinning again replaces the active pin (the old one is kept as removed).
        var again = json(command(
                        planner,
                        "/api/schedule/pins",
                        new ScheduleCommands.Pin(null, pack, pk1, local(900), "Later slot", false))
                .andExpect(status().isOk()));
        assertThat(count("SELECT COUNT(*) FROM schedule_pins WHERE tenant_id=?", t))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM schedule_pins WHERE tenant_id=? AND removed_at IS NULL", t))
                .isEqualTo(1);
        var pins = json(query(planner, "/api/schedule/pins").andExpect(status().isOk()));
        assertThat(rowsOf(pins, "items")).hasSize(1);
        assertThatThrownBy(() -> update("UPDATE schedule_pins SET start_at=now() WHERE id=?", str(again, "id")))
                .isInstanceOf(DataAccessException.class);

        // Removing it (with a re-run) frees the operation.
        var removed = json(command(planner, "/api/schedule/pins/" + str(again, "id") + "/remove", Map.of("rerun", true))
                .andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        String after = str((Map<String, Object>) removed.get("run"), "id");
        assertThat(scheduled(after, pack).get("pin_status")).isNull();
        command(planner, "/api/schedule/pins/" + str(again, "id") + "/remove", Map.of("rerun", false))
                .andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='SCHEDULE_UNPINNED'", t))
                .isEqualTo(1);
    }

    @Test
    void estimatesApplyOnlyToOrdersWithoutRouting() throws Exception {
        String legacy = releasedOrder("100", pk1, "NORMAL"); // no routing yet
        routing();
        String routed = releasedOrder("1000", bm1, "NORMAL");
        String first = run(planner);
        assertThat(row("SELECT status FROM schedule_run_orders WHERE run_id=? AND order_id=?", first, legacy)
                        .get("status"))
                .isEqualTo("NO_ROUTING");
        command(planner, "/api/schedule/estimates", new ScheduleCommands.Estimate(routed, bd("60"), ""))
                .andExpect(status().isConflict());
        command(planner, "/api/schedule/estimates", new ScheduleCommands.Estimate(legacy, bd("0"), ""))
                .andExpect(status().isBadRequest());
        command(planner, "/api/schedule/estimates", new ScheduleCommands.Estimate(legacy, bd("240"), "Half a shift"))
                .andExpect(status().isOk());
        String second = run(planner);
        var row = row("SELECT * FROM schedule_run_orders WHERE run_id=? AND order_id=?", second, legacy);
        assertThat(row.get("status")).isEqualTo("SCHEDULED");
        assertThat(dec(row.get("estimate_minutes"))).isEqualByComparingTo("240");
        assertThat(count(
                        "SELECT COUNT(*) FROM schedule_run_operations WHERE run_id=? AND order_id=? AND operation_id IS NULL AND minutes=240",
                        second,
                        legacy))
                .isEqualTo(1);
        // Its estimated run can be pinned like an operation.
        command(planner, "/api/schedule/pins", new ScheduleCommands.Pin(legacy, null, pk1, local(1440), "", false))
                .andExpect(status().isOk());
        command(planner, "/api/schedule/estimates/" + legacy + "/remove", null).andExpect(status().isOk());
        assertThat(count(
                        "SELECT COUNT(*) FROM schedule_pins WHERE tenant_id=? AND order_id=? AND removed_at IS NULL",
                        t,
                        legacy))
                .isZero();
        command(planner, "/api/schedule/estimates/" + legacy + "/remove", null).andExpect(status().isConflict());
        assertThat(count(
                        "SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action IN ('SCHEDULE_ESTIMATE_SET','SCHEDULE_ESTIMATE_REMOVED')",
                        t))
                .isEqualTo(2);
    }

    @Test
    void rolesRetriesTenantsAndThePublishedScheduleForEveryone() throws Exception {
        routing();
        String order = releasedOrder("1000", bm1, "NORMAL");
        // Writes: planners and administrators only.
        command(viewer, "/api/schedule/runs", DEFAULTS).andExpect(status().isForbidden());
        command(operator, "/api/schedule/runs", DEFAULTS).andExpect(status().isForbidden());
        command(
                        operator,
                        "/api/schedule/pins",
                        new ScheduleCommands.Pin(null, operation(order, 20), pk1, local(720), "", false))
                .andExpect(status().isForbidden());
        command(quality, "/api/schedule/estimates", new ScheduleCommands.Estimate(order, bd("60"), ""))
                .andExpect(status().isForbidden());

        // Idempotent retry: same key and body → the same run; same key, other body → 409.
        String key = id();
        String first = str(
                json(command(planner, "/api/schedule/runs", Map.of("horizonDays", 14), key)
                        .andExpect(status().isOk())),
                "id");
        String again = str(
                json(command(planner, "/api/schedule/runs", Map.of("horizonDays", 14), key)
                        .andExpect(status().isOk())),
                "id");
        assertThat(again).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM schedule_runs WHERE tenant_id=?", t))
                .isEqualTo(1);
        assertThat(row("SELECT horizon_days FROM schedule_runs WHERE id=?", first)
                        .get("horizon_days"))
                .isEqualTo(14);
        command(planner, "/api/schedule/runs", Map.of("horizonDays", 21), key).andExpect(status().isConflict());
        command(planner, "/api/schedule/runs", Map.of("horizonDays", 400)).andExpect(status().isBadRequest());

        // Before publication other roles cannot read the draft.
        query(operator, "/api/schedule/runs").andExpect(status().isForbidden());
        query(operator, "/api/schedule/runs/" + first + "/gantt").andExpect(status().isForbidden());
        query(viewer, "/api/schedule/runs/" + first + "/compare").andExpect(status().isForbidden());
        query(planner, "/api/schedule/runs?limit=10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(first));
        command(planner, "/api/schedule/runs/" + first + "/publish", null).andExpect(status().isOk());
        query(operator, "/api/schedule/runs/" + first + "/gantt").andExpect(status().isOk());
        query(viewer, "/api/schedule/runs/" + first + "/late").andExpect(status().isOk());
        query(viewer, "/api/schedule/runs/" + first + "/capacity").andExpect(status().isOk());
        query(operator, "/api/schedule/next?machineId=" + bm1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].order_id").value(order))
                .andExpect(jsonPath("$.items[0].seq").value(10));
        query(viewer, "/api/schedule/orders/" + order)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published.run_id").value(first))
                .andExpect(jsonPath("$.published.operations.length()").value(2))
                .andExpect(jsonPath("$.draft").doesNotExist());
        query(viewer, "/api/schedule")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published.id").value(first))
                .andExpect(jsonPath("$.draft").doesNotExist());
        query(viewer, "/api/state")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule.published.id").value(first))
                .andExpect(jsonPath("$.schedule.late_orders").value(0));

        // Another tenant sees none of it.
        var other = createTenant("Other scheduler");
        var stranger = createUser(other.id(), "PLANNER");
        query(stranger, "/api/schedule/runs/" + first).andExpect(status().isNotFound());
        query(stranger, "/api/schedule/runs/" + first + "/gantt").andExpect(status().isNotFound());
        query(stranger, "/api/schedule/orders/" + order).andExpect(status().isNotFound());
        query(stranger, "/api/schedule/next?machineId=" + bm1).andExpect(status().isNotFound());
        command(stranger, "/api/schedule/runs/" + first + "/publish", null).andExpect(status().isNotFound());
        command(
                        stranger,
                        "/api/schedule/pins",
                        new ScheduleCommands.Pin(null, operation(order, 20), pk1, local(720), "", false))
                .andExpect(status().isNotFound());
        command(stranger, "/api/schedule/estimates", new ScheduleCommands.Estimate(order, new BigDecimal("60"), ""))
                .andExpect(status().isNotFound());
        query(stranger, "/api/schedule")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").doesNotExist());
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rowsOf(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }
}
