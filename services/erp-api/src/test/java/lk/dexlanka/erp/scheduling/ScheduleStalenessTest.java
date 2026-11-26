package lk.dexlanka.erp.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.RoutingTestBase;
import lk.dexlanka.erp.maintenance.DowntimeService;
import lk.dexlanka.erp.maintenance.MaintenanceCommands;
import lk.dexlanka.erp.manufacturing.OperationCommands;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.routing.CalendarCommands;
import lk.dexlanka.erp.routing.ShiftCalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V16 stale-schedule hint ({@link ScheduleChanges}): the {@code schedule} state key and {@code GET /api/schedule} count
 * the changes a new run would see since the published run was created: order and operation events of open orders or
 * orders in the run, maintenance windows and downtime that reach into the schedule, calendars and pins; not orders that
 * were created and finished afterwards, not downtime that ended before the run, not other tenants. Publishing a new run
 * resets it.
 */
class ScheduleStalenessTest extends RoutingTestBase {
    @Autowired
    DowntimeService downtime;

    @Autowired
    ShiftCalendarService calendarAdmin;

    @SuppressWarnings("unchecked")
    private Map<String, Object> scheduleKey(Actor actor) throws Exception {
        return (Map<String, Object>) json(query(actor, "/api/state?days=7").andExpect(status().isOk()))
                .get("schedule");
    }

    private long changes(Actor actor) throws Exception {
        long fromState = ((Number) scheduleKey(actor).get("changes_since_publish")).longValue();
        @SuppressWarnings("unchecked")
        var stale = (Map<String, Object>)
                json(query(actor, "/api/schedule").andExpect(status().isOk())).get("stale");
        assertThat(((Number) stale.get("changes")).longValue()).isEqualTo(fromState);
        return fromState;
    }

    private String publishRun() throws Exception {
        String run = str(json(command(planner, "/api/schedule/runs", Map.of()).andExpect(status().isOk())), "id");
        command(planner, "/api/schedule/runs/" + run + "/publish", null).andExpect(status().isOk());
        return run;
    }

    private String operation(String order, int seq) {
        return str(row("SELECT id FROM mo_operations WHERE tenant_id=? AND order_id=? AND seq=?", t, order, seq), "id");
    }

    @Test
    void countsRelevantChangesSinceThePublishedRunWasCreated() throws Exception {
        approvedRouting(bottle, "R1", op(10, "Blow", bm1), op(20, "Pack", pk1));
        String planned = releasedOrder("100", bm1, "NORMAL");
        // Before any publication there is nothing to be out of date.
        assertThat(scheduleKey(viewer).get("changes_since_publish")).isEqualTo(0);
        assertThat(json(query(viewer, "/api/schedule").andExpect(status().isOk()))
                        .get("stale"))
                .isNull();

        publishRun();
        assertThat(changes(viewer)).isZero();
        assertThat(scheduleKey(viewer).get("last_change_at")).isNull();

        // The planned order starts its first operation: MO_START + OP_STARTED.
        command(
                        operator,
                        "/api/operations/" + operation(planned, 10) + "/start",
                        new OperationCommands.Start(null, null, null))
                .andExpect(status().isOk());
        assertThat(changes(viewer)).isEqualTo(2);
        assertThat(scheduleKey(viewer).get("last_change_at")).isNotNull();

        // A new open order is work the published schedule does not know about.
        String added = order("50", bm2, "HIGH");
        assertThat(changes(planner)).isEqualTo(3);
        // An order created and cancelled after the run never affected it.
        String dropped = order("50", bm2, "NORMAL");
        mo.transition(planner, dropped, "cancel", id());
        assertThat(changes(planner)).isEqualTo(3);

        // Downtime that ended before the run's start is history; open downtime blocks capacity ahead.
        var yesterday = OffsetDateTime.now(ZoneOffset.ofHoursMinutes(5, 30)).minusDays(1);
        downtime.report(
                operator,
                new MaintenanceCommands.ReportDowntime(
                        pk1,
                        "CHANGEOVER",
                        null,
                        yesterday.withHour(9).withMinute(0).toString(),
                        yesterday.withHour(9).withMinute(30).toString(),
                        null,
                        ""),
                id());
        assertThat(changes(planner)).isEqualTo(3);
        downtime.report(
                operator, new MaintenanceCommands.ReportDowntime(bm2, "BREAKDOWN", null, null, null, null, ""), id());
        assertThat(changes(planner)).isEqualTo(4);

        // Working time changes capacity: a new plant calendar and a holiday.
        String day = str(
                calendarAdmin.createPattern(
                        planner,
                        new CalendarCommands.ShiftPattern(
                                "DAY", "Day", "06:00", "18:00", null, 0, List.of(1, 2, 3, 4, 5, 6, 7)),
                        id()),
                "id");
        String calendar = str(
                calendarAdmin.createCalendar(
                        planner, new CalendarCommands.Calendar("PLANT", "Plant", List.of(day), true), id()),
                "id");
        calendarAdmin.addException(
                planner,
                calendar,
                new CalendarCommands.DateException(
                        LocalDate.now().plusDays(3).toString(), "CLOSED", null, null, "Holiday"),
                id());
        assertThat(changes(planner)).isEqualTo(6); // CALENDAR_CREATED + CALENDAR_EXCEPTION_ADDED

        // Another tenant's activity never counts.
        var other = createTenant();
        var otherPlanner = createUser(other.id(), "PLANNER");
        query(otherPlanner, "/api/state?days=7").andExpect(status().isOk());
        assertThat(changes(planner)).isEqualTo(6);

        // A new published run starts from a clean slate.
        publishRun();
        assertThat(changes(viewer)).isZero();
        assertThat(added).isNotBlank();
    }
}
