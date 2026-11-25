package lk.dexlanka.erp.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Preventive plans: due/overdue lists, idempotent generation per plan and due date, completion rescheduling from the
 * completion date, skipping by cancellation, run-hour meter readings and the {@code maintenance} state key.
 */
class PreventiveMaintenanceTest extends MaintenanceTestSupport {
    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    private static LocalDate today() {
        return LocalDate.now(COLOMBO);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    @Test
    void generationIsIdempotentPerPlanAndDueDate() throws Exception {
        var overdue = plans.create(planner, plan(m1, 30, today().minusDays(2).toString()), id());
        var soon = plans.create(planner, plan(m2, 14, today().plusDays(3).toString()), id());
        plans.create(planner, plan(m2, 90, today().plusDays(20).toString()), id());
        assertThat(str(overdue, "number")).startsWith("PM-");

        // Due and overdue lists, plus the attention counts in /api/state.
        assertThat(list(query.plans(viewer, null, "overdue", null, null, 25, null), "items"))
                .extracting(r -> r.get("id"))
                .containsExactly(overdue.get("id"));
        var due = query.plans(viewer, null, "due", 7, null, 25, null);
        assertThat(list(due, "items")).extracting(r -> r.get("id")).containsExactly(overdue.get("id"), soon.get("id"));
        assertThat(list(due, "items").getFirst().get("due_state")).isEqualTo("OVERDUE");
        var state = json(query(viewer, "/api/state?days=14").andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var maintenance = (Map<String, Object>) state.get("maintenance");
        assertThat(((Number) maintenance.get("overdue_plans")).intValue()).isEqualTo(1);
        assertThat((List<?>) maintenance.get("overdue")).hasSize(1);

        // Horizon 0: only the overdue plan; the order is approved and scheduled by the generating planner.
        String key = id();
        var first = plans.generate(planner, new MaintenanceCommands.Generate(0), key);
        assertThat(list(first, "created")).hasSize(1);
        String woId = str(list(first, "created").getFirst(), "id");
        var wo = workOrderRow(woId);
        assertThat(wo.get("type")).isEqualTo("PREVENTIVE");
        assertThat(wo.get("status")).isEqualTo("SCHEDULED");
        assertThat(wo.get("approved_by")).isEqualTo(planner.id());
        assertThat(wo.get("plan_id")).isEqualTo(overdue.get("id"));
        assertThat(wo.get("due_date").toString()).isEqualTo(today().minusDays(2).toString());
        // An overdue occurrence is scheduled from the next full hour, for the plan's estimated hours.
        var startAt = MaintenanceRules.instant(wo.get("scheduled_start"));
        assertThat(startAt).isAfter(java.time.Instant.now());
        assertThat(java.time.Duration.between(startAt, MaintenanceRules.instant(wo.get("scheduled_end")))
                        .toHours())
                .isEqualTo(2);
        var checklist = query.workOrder(viewer, woId).get("checklist");
        assertThat((List<?>) checklist).hasSize(2);

        // Retrying the same request replays it; a new request creates nothing for the same occurrence.
        var replay = plans.generate(planner, new MaintenanceCommands.Generate(0), key);
        assertThat(list(replay, "created")).hasSize(1);
        var again = plans.generate(planner, new MaintenanceCommands.Generate(0), id());
        assertThat(list(again, "created")).isEmpty();
        assertThat(list(again, "skipped")).hasSize(1);
        assertThat(count(
                        "SELECT COUNT(*) FROM maintenance_work_orders WHERE tenant_id=? AND plan_id=?",
                        t,
                        overdue.get("id")))
                .isEqualTo(1);

        // A wider horizon picks up the next plan, scheduled on its due date at 08:00 Asia/Colombo.
        var wider = plans.generate(planner, new MaintenanceCommands.Generate(7), id());
        assertThat(list(wider, "created")).extracting(r -> r.get("plan")).containsExactly(soon.get("number"));
        var next = row("SELECT * FROM maintenance_work_orders WHERE tenant_id=? AND plan_id=?", t, soon.get("id"));
        assertThat(OffsetDateTime.ofInstant(MaintenanceRules.instant(next.get("scheduled_start")), COLOMBO)
                        .toLocalDateTime())
                .isEqualTo(today().plusDays(3).atTime(8, 0));
        // The database refuses a second order for the same occurrence.
        assertThatThrownBy(() -> update(
                        "INSERT INTO maintenance_work_orders(id,tenant_id,number,type,machine_id,plan_id,due_date,title,status,requested_by) SELECT ?,tenant_id,'MWO-DUP',type,machine_id,plan_id,due_date,title,'APPROVED',requested_by FROM maintenance_work_orders WHERE id=?",
                        id(),
                        woId))
                .hasMessageContaining("maintenance_work_orders_plan_due");
        assertThat(actions("PM")).contains("PM_ORDERS_GENERATED");
    }

    @Test
    void completionReschedulesFromTheCompletionDate() {
        var p = plans.create(planner, plan(m1, 30, today().minusDays(2).toString()), id());
        String woId = str(
                list(plans.generate(planner, new MaintenanceCommands.Generate(0), id()), "created")
                        .getFirst(),
                "id");
        orders.start(technician, woId, id());
        assertThat(machineStatus(m1)).isEqualTo("MAINTENANCE");
        var covering = row("SELECT * FROM downtime_events WHERE tenant_id=? AND work_order_id=?", t, woId);
        assertThat(covering.get("reason")).isEqualTo("PLANNED_MAINTENANCE");

        assertThatThrownBy(() -> orders.complete(
                        technician, woId, new MaintenanceCommands.Complete("All fine", null, null, null), id()))
                .hasMessageContaining("Record a result for every checklist item (2 items still open)");
        orders.progress(
                technician,
                woId,
                new MaintenanceCommands.Progress(
                        List.of(new MaintenanceCommands.ChecklistResult(0, "PASS", "Level OK")), "Oil topped up", null),
                id());
        assertThatThrownBy(() -> orders.progress(
                        technician,
                        woId,
                        new MaintenanceCommands.Progress(
                                List.of(new MaintenanceCommands.ChecklistResult(5, "PASS", null)), null, null),
                        id()))
                .hasMessageContaining("Checklist item 6 does not exist");
        orders.complete(
                technician,
                woId,
                new MaintenanceCommands.Complete(
                        "Guides dry",
                        "Greased",
                        List.of(new MaintenanceCommands.ChecklistResult(1, "FAIL", "Grease nipple blocked")),
                        null),
                id());
        var plan = row("SELECT * FROM maintenance_plans WHERE id=?", p.get("id"));
        assertThat(plan.get("next_due_date").toString())
                .isEqualTo(today().plusDays(30).toString());
        assertThat(plan.get("last_completed_at")).isNotNull();
        assertThat(machineStatus(m1)).isEqualTo("IDLE");
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) query.workOrder(viewer, woId).get("checklist");
        assertThat(items).extracting(i -> i.get("result")).containsExactly("PASS", "FAIL");
        assertThat(items.getFirst().get("note")).isEqualTo("Level OK");
        assertThat(list(query.plans(viewer, null, "overdue", null, null, 25, null), "items"))
                .isEmpty();
        assertThat(list(plans.generate(planner, new MaintenanceCommands.Generate(0), id()), "created"))
                .isEmpty();
    }

    @Test
    void cancellingAPreventiveOrderSkipsTheOccurrence() {
        var p = plans.create(planner, plan(m2, 14, today().minusDays(1).toString()), id());
        String woId = str(
                list(plans.generate(planner, new MaintenanceCommands.Generate(0), id()), "created")
                        .getFirst(),
                "id");
        orders.cancel(planner, woId, new MaintenanceCommands.Cancel("Line shut for the holiday"), id());
        assertThat(row("SELECT next_due_date FROM maintenance_plans WHERE id=?", p.get("id"))
                        .get("next_due_date")
                        .toString())
                .isEqualTo(today().minusDays(1).plusDays(14).toString());
        assertThat(list(plans.generate(planner, new MaintenanceCommands.Generate(0), id()), "created"))
                .isEmpty();
    }

    @Test
    void runHourMeterPullsTheDueDateForward() {
        plans.meter(operator, m1, new MaintenanceCommands.Meter(new BigDecimal("100"), null), id());
        var p = plans.create(
                planner,
                new MaintenanceCommands.Plan(
                        m1,
                        "500-hour service",
                        null,
                        List.of("Change filter"),
                        30,
                        new BigDecimal("50"),
                        today().plusDays(20).toString(),
                        null,
                        "HIGH"),
                id());
        assertThat(dec(row("SELECT meter_at_last_service FROM maintenance_plans WHERE id=?", p.get("id"))
                        .get("meter_at_last_service")))
                .isEqualByComparingTo("100");
        var below = plans.meter(technician, m1, new MaintenanceCommands.Meter(new BigDecimal("140"), null), id());
        assertThat((List<?>) below.get("plansDue")).isEmpty();
        var reached = plans.meter(planner, m1, new MaintenanceCommands.Meter(new BigDecimal("160"), "Shift end"), id());
        assertThat((List<Object>) reached.get("plansDue")).containsExactly(p.get("number"));
        assertThat(row("SELECT next_due_date FROM maintenance_plans WHERE id=?", p.get("id"))
                        .get("next_due_date")
                        .toString())
                .isEqualTo(today().toString());
        assertThatThrownBy(() ->
                        plans.meter(operator, m1, new MaintenanceCommands.Meter(new BigDecimal("150"), null), id()))
                .hasMessageContaining("cannot go backwards");
        assertThatThrownBy(() ->
                        plans.meter(warehouse, m1, new MaintenanceCommands.Meter(new BigDecimal("170"), null), id()))
                .isInstanceOf(AccessDeniedException.class);

        // Completing with a reading restarts the run-hour count from it.
        String woId = str(
                list(plans.generate(planner, new MaintenanceCommands.Generate(0), id()), "created")
                        .getFirst(),
                "id");
        orders.start(technician, woId, id());
        orders.complete(
                technician,
                woId,
                new MaintenanceCommands.Complete(
                        "Filter changed",
                        null,
                        List.of(new MaintenanceCommands.ChecklistResult(0, "PASS", null)),
                        new BigDecimal("165")),
                id());
        var plan = row("SELECT * FROM maintenance_plans WHERE id=?", p.get("id"));
        assertThat(dec(plan.get("meter_at_last_service"))).isEqualByComparingTo("165");
        assertThat(plan.get("next_due_date").toString())
                .isEqualTo(today().plusDays(30).toString());
    }

    @Test
    void plansBelongToPlanners() throws Exception {
        command(operator, "/api/maintenance/plans", plan(m1, 30, today().toString()))
                .andExpect(status().isForbidden());
        command(technician, "/api/maintenance/plans/generate", new MaintenanceCommands.Generate(0))
                .andExpect(status().isForbidden());
        command(planner, "/api/maintenance/plans", plan(m1, 0, today().toString()))
                .andExpect(status().isBadRequest());
        command(planner, "/api/maintenance/plans", plan(m1, 30, "next week")).andExpect(status().isBadRequest());
        var created = json(command(
                        planner,
                        "/api/maintenance/plans",
                        plan(m1, 30, today().plusDays(10).toString()))
                .andExpect(status().isOk()));
        var update = new MaintenanceCommands.PlanUpdate(
                "Monthly service (revised)",
                "",
                List.of("Check oil"),
                28,
                null,
                today().plusDays(5).toString(),
                null,
                "URGENT",
                false);
        command(operator, "/api/maintenance/plans/" + created.get("id"), update).andExpect(status().isForbidden());
        var updated = json(command(planner, "/api/maintenance/plans/" + created.get("id"), update)
                .andExpect(status().isOk()));
        assertThat(updated.get("active")).isEqualTo(false);
        assertThat(updated.get("interval_days")).isEqualTo(28);
        // Inactive plans are not generated and only listed on request.
        assertThat(list(query.plans(viewer, null, null, null, null, 25, null), "items"))
                .isEmpty();
        assertThat(list(query.plans(viewer, null, null, null, "all", 25, null), "items"))
                .hasSize(1);
        var other = createTenant();
        command(createUser(other.id(), "PLANNER"), "/api/maintenance/plans", plan(m1, 30, today().toString()))
                .andExpect(status().isNotFound());
    }
}
