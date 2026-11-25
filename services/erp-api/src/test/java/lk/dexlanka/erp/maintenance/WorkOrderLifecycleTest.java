package lk.dexlanka.erp.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Corrective work orders: request with a breakdown, independent approval, start (machine to MAINTENANCE, refused while
 * production runs), labour with its journal, completion (machine to IDLE, downtime closed), cancellation, the planners'
 * machine status correction, permissions, tenants and idempotency.
 */
class WorkOrderLifecycleTest extends MaintenanceTestSupport {

    @Test
    void correctiveOrderTakesTheMachineThroughMaintenance() throws Exception {
        var requested = json(command(operator, "/api/maintenance/work-orders", corrective(m2, true))
                .andExpect(status().isOk()));
        String id = str(requested, "id");
        assertThat(requested.get("status")).isEqualTo("REQUESTED");
        assertThat(requested.get("type")).isEqualTo("CORRECTIVE");
        assertThat(requested.get("priority")).isEqualTo("HIGH");
        String number = str(workOrderRow(id), "number");
        assertThat(number).startsWith("MWO-");
        var breakdown = row("SELECT * FROM downtime_events WHERE tenant_id=? AND work_order_id=?", t, id);
        assertThat(breakdown.get("reason")).isEqualTo("BREAKDOWN");
        assertThat(breakdown.get("status")).isEqualTo("OPEN");
        assertThat(breakdown.get("planned")).isEqualTo(false);

        // Approval: planners only, never the requester.
        command(
                        operator,
                        "/api/maintenance/work-orders/" + id + "/approve",
                        new MaintenanceCommands.Approve(null, null, null))
                .andExpect(status().isForbidden());
        command(
                        technician,
                        "/api/maintenance/work-orders/" + id + "/approve",
                        new MaintenanceCommands.Approve(null, null, null))
                .andExpect(status().isForbidden());
        command(operator, "/api/maintenance/work-orders/" + id + "/start", null).andExpect(status().isForbidden());
        assertThatThrownBy(() -> orders.start(technician, id, id())).hasMessageContaining("Approve the work order");
        var approved = orders.approve(planner, id, new MaintenanceCommands.Approve(null, null, technician.id()), id());
        assertThat(approved.get("status")).isEqualTo("APPROVED");
        assertThat(approved.get("assignee_id")).isEqualTo(technician.id());

        // Start: the machine goes to MAINTENANCE and the reported breakdown covers the work.
        command(planner, "/api/maintenance/work-orders/" + id + "/start", null).andExpect(status().isForbidden());
        var started = json(command(technician, "/api/maintenance/work-orders/" + id + "/start", null)
                .andExpect(status().isOk()));
        assertThat(started.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(machineStatus(m2)).isEqualTo("MAINTENANCE");
        assertThat(count("SELECT COUNT(*) FROM downtime_events WHERE tenant_id=? AND machine_id=?", t, m2))
                .isEqualTo(1);
        var change = row("SELECT * FROM machine_status_changes WHERE tenant_id=? AND machine_id=?", t, m2);
        assertThat(change.get("from_status")).isEqualTo("RUNNING");
        assertThat(change.get("to_status")).isEqualTo("MAINTENANCE");
        assertThat(change.get("source")).isEqualTo("MAINTENANCE_ORDER");
        assertThat(change.get("reference")).isEqualTo(number);
        // While the order runs, its downtime is closed by completion only.
        assertThatThrownBy(() -> downtime.close(
                        operator, str(breakdown, "id"), new MaintenanceCommands.CloseDowntime(null, null), id()))
                .hasMessageContaining("completing it closes this downtime");

        // Labour at the default rate (Rs. 1,500 per hour), reversible, never below zero.
        orders.labour(technician, id, new MaintenanceCommands.Labour(new BigDecimal("2"), null, "Diagnosis"), id());
        orders.labour(technician, id, new MaintenanceCommands.Labour(new BigDecimal("-0.5"), null, "Typo"), id());
        assertThatThrownBy(() -> orders.labour(
                        technician, id, new MaintenanceCommands.Labour(new BigDecimal("-5"), null, null), id()))
                .hasMessageContaining("labour hours are recorded");
        var wo = workOrderRow(id);
        assertThat(dec(wo.get("labour_hours"))).isEqualByComparingTo("1.5");
        assertThat(dec(wo.get("labour_cost"))).isEqualByComparingTo("2250");
        assertThat(journal(number, "MAINTENANCE_EXPENSE")).isEqualByComparingTo("2250");
        assertThat(journal(number, "MAINTENANCE_LABOUR_CLEARING")).isEqualByComparingTo("-2250");
        assertThat(unbalancedEntries()).isZero();
        assertThatThrownBy(() -> orders.labour(
                        technician, id, new MaintenanceCommands.Labour(BigDecimal.ONE, planner.id(), null), id()))
                .hasMessageContaining("not a maintenance technician");

        // Completion: findings required (400), the machine is idle again and the breakdown is closed.
        command(
                        technician,
                        "/api/maintenance/work-orders/" + id + "/complete",
                        new MaintenanceCommands.Complete("", null, null, null))
                .andExpect(status().isBadRequest());
        var done = orders.complete(
                technician,
                id,
                new MaintenanceCommands.Complete("Seal worn", "Replaced the clamp seal", List.of(), null),
                id());
        assertThat(done.get("status")).isEqualTo("COMPLETED");
        assertThat(machineStatus(m2)).isEqualTo("IDLE");
        var closed = row("SELECT * FROM downtime_events WHERE id=?", breakdown.get("id"));
        assertThat(closed.get("status")).isEqualTo("CLOSED");
        assertThat(closed.get("ended_at")).isNotNull();
        assertThat(rows(
                        "SELECT to_status FROM machine_status_changes WHERE tenant_id=? AND machine_id=? ORDER BY changed_at",
                        t,
                        m2))
                .extracting(r -> r.get("to_status"))
                .containsExactly("MAINTENANCE", "IDLE");
        assertThat(str(workOrderRow(id), "findings")).isEqualTo("Seal worn");
        assertThatThrownBy(() -> orders.complete(
                        technician, id, new MaintenanceCommands.Complete("Again", null, null, null), id()))
                .hasMessageContaining("Only work in progress");
        assertThatThrownBy(() -> update("UPDATE maintenance_work_orders SET findings='x' WHERE id=?", id))
                .hasMessageContaining("immutable");
        assertThat(actions(number))
                .containsSubsequence(
                        "MWO_REQUESTED", "MWO_APPROVED", "MWO_STARTED", "MWO_LABOUR_LOGGED", "MWO_COMPLETED");

        var detail = query.workOrder(viewer, id);
        assertThat((List<?>) detail.get("labour")).hasSize(2);
        assertThat((List<?>) detail.get("downtime")).hasSize(1);
        assertThat((List<?>) detail.get("journal")).hasSize(4);
    }

    @Test
    void selfApprovalIsRefusedEvenForAdministrators() {
        String byAdmin = str(orders.request(admin, corrective(m1, false), id()), "id");
        assertThatThrownBy(
                        () -> orders.approve(admin, byAdmin, new MaintenanceCommands.Approve(null, null, null), id()))
                .hasMessageContaining("A different authorized person must approve");
        assertThat(orders.approve(planner, byAdmin, new MaintenanceCommands.Approve(null, null, null), id())
                        .get("status"))
                .isEqualTo("APPROVED");

        String byPlanner = str(orders.request(planner, corrective(m2, false), id()), "id");
        assertThatThrownBy(() ->
                        orders.approve(planner, byPlanner, new MaintenanceCommands.Approve(null, null, null), id()))
                .hasMessageContaining("A different authorized person must approve");
        // Approval with a window schedules the order directly.
        String start =
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0).toString();
        String end = OffsetDateTime.now(ZoneOffset.UTC)
                .plusDays(1)
                .plusHours(3)
                .withNano(0)
                .toString();
        var scheduled = orders.approve(planner2, byPlanner, new MaintenanceCommands.Approve(start, end, null), id());
        assertThat(scheduled.get("status")).isEqualTo("SCHEDULED");
        assertThatThrownBy(() ->
                        orders.approve(planner2, byPlanner, new MaintenanceCommands.Approve(null, null, null), id()))
                .hasMessageContaining("Only requested work orders");
        assertThatThrownBy(() ->
                        orders.schedule(planner, byPlanner, new MaintenanceCommands.Schedule(end, start, null), id()))
                .hasMessageContaining("end after it starts");
        assertThatThrownBy(() ->
                        orders.approve(planner2, byAdmin, new MaintenanceCommands.Approve(start, null, null), id()))
                .hasMessageContaining("Only requested");
    }

    @Test
    void startingRefusesWhileProductionRunsOnTheMachine() {
        String order = productionOrder(m1, "IN_PROGRESS");
        String number = str(row("SELECT number FROM manufacturing_orders WHERE id=?", order), "number");
        String id = approvedOrder(m1, false);
        assertThatThrownBy(() -> orders.start(technician, id, id()))
                .hasMessageContaining(number + " is running on M1")
                .hasMessageContaining("pause production");
        assertThat(machineStatus(m1)).isEqualTo("IDLE");
        assertThat(count("SELECT COUNT(*) FROM downtime_events WHERE tenant_id=? AND machine_id=?", t, m1))
                .isZero();
        update("UPDATE manufacturing_orders SET status='ON_HOLD' WHERE id=?", order);
        assertThat(orders.start(technician, id, id()).get("status")).isEqualTo("IN_PROGRESS");

        String partial = productionOrder(m2, "PARTIALLY_COMPLETED");
        String other = approvedOrder(m2, false);
        assertThatThrownBy(() -> orders.start(technician, other, id())).hasMessageContaining("is running on M2");
        update("UPDATE manufacturing_orders SET status='COMPLETED' WHERE id=?", partial);
        orders.start(technician, other, id());

        // One order in progress per machine.
        String second = approvedOrder(m1, false);
        assertThatThrownBy(() -> orders.start(technician, second, id())).hasMessageContaining("already in progress");
    }

    @Test
    void startCoversTheWorkWithDowntime() {
        // An unrelated open event is closed when maintenance starts; scheduled corrective work is planned downtime.
        var shortage = downtime.report(operator, report(m1, "MATERIAL_SHORTAGE", null, null), id());
        String requested = str(orders.request(operator, corrective(m1, false), id()), "id");
        String start =
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0).toString();
        String end = OffsetDateTime.now(ZoneOffset.UTC).plusHours(4).withNano(0).toString();
        orders.approve(planner, requested, new MaintenanceCommands.Approve(start, end, null), id());
        orders.start(technician, requested, id());
        assertThat(row("SELECT status FROM downtime_events WHERE id=?", shortage.get("id"))
                        .get("status"))
                .isEqualTo("CLOSED");
        var covering = row("SELECT * FROM downtime_events WHERE tenant_id=? AND work_order_id=?", t, requested);
        assertThat(covering.get("reason")).isEqualTo("PLANNED_MAINTENANCE");
        assertThat(covering.get("planned")).isEqualTo(true);
        assertThat(covering.get("status")).isEqualTo("OPEN");

        // Unscheduled corrective work without a reported breakdown is unplanned breakdown time.
        String unscheduled = approvedOrder(m2, false);
        orders.start(technician, unscheduled, id());
        var breakdown = row("SELECT * FROM downtime_events WHERE tenant_id=? AND work_order_id=?", t, unscheduled);
        assertThat(breakdown.get("reason")).isEqualTo("BREAKDOWN");
        assertThat(breakdown.get("planned")).isEqualTo(false);

        // A breakdown reported on its own is adopted by the order that repairs it.
        String m3 = machine("M3", "RUNNING");
        var reported = downtime.report(operator, report(m3, "BREAKDOWN", null, null), id());
        var linked = orders.request(
                operator,
                new MaintenanceCommands.RequestWorkOrder(
                        m3, "Motor tripped", null, "URGENT", false, null, str(reported, "id"), null),
                id());
        assertThat(row("SELECT work_order_id FROM downtime_events WHERE id=?", reported.get("id"))
                        .get("work_order_id"))
                .isEqualTo(linked.get("id"));
        assertThatThrownBy(() -> orders.request(operator, corrective(m3, true), id()))
                .hasMessageContaining("already has open downtime");
    }

    @Test
    void cancellationRules() {
        String requested = str(orders.request(operator, corrective(m1, false), id()), "id");
        assertThatThrownBy(() -> orders.cancel(operator, requested, new MaintenanceCommands.Cancel("Duplicate"), id()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(orders.cancel(planner, requested, new MaintenanceCommands.Cancel("Duplicate"), id())
                        .get("status"))
                .isEqualTo("CANCELLED");
        assertThat(workOrderRow(requested).get("cancel_reason")).isEqualTo("Duplicate");
        assertThatThrownBy(() -> orders.cancel(planner, requested, new MaintenanceCommands.Cancel("Again"), id()))
                .hasMessageContaining("already closed");

        // Issued parts must come back before an approved order is cancelled.
        String approved = approvedOrder(m1, false);
        var issue =
                parts.issue(technician, approved, new MaintenanceCommands.IssuePart(spare, BigDecimal.ONE, null), id());
        assertThatThrownBy(() -> orders.cancel(planner, approved, new MaintenanceCommands.Cancel("Not needed"), id()))
                .hasMessageContaining("Return the issued spare parts");
        @SuppressWarnings("unchecked")
        var movement = ((List<Map<String, Object>>) issue.get("movements")).getFirst();
        parts.returnPart(
                technician,
                approved,
                str(movement, "id"),
                new MaintenanceCommands.ReturnPart(BigDecimal.ONE, null),
                id());
        orders.cancel(planner, approved, new MaintenanceCommands.Cancel("Not needed"), id());

        String started = startedOrder(m2);
        assertThatThrownBy(() -> orders.cancel(planner, started, new MaintenanceCommands.Cancel("Stop"), id()))
                .hasMessageContaining("Work has started");
    }

    @Test
    void plannerStatusCorrectionsNeverBypassMaintenance() throws Exception {
        String id = startedOrder(m1);
        var idle = new MaintenanceCommands.MachineStatus("IDLE", "Wrongly shown as under maintenance");
        command(operator, "/api/maintenance/machines/" + m1 + "/status", idle).andExpect(status().isForbidden());
        command(
                        planner,
                        "/api/maintenance/machines/" + m1 + "/status",
                        new MaintenanceCommands.MachineStatus("MAINTENANCE", "x"))
                .andExpect(status().isBadRequest());
        assertThatThrownBy(() -> machines.correct(planner, m1, idle, id()))
                .hasMessageContaining("completing it ends the maintenance");
        orders.complete(technician, id, new MaintenanceCommands.Complete("Done", null, null, null), id());
        assertThat(machineStatus(m1)).isEqualTo("IDLE");
        assertThatThrownBy(() -> machines.correct(planner, m1, idle, id())).hasMessageContaining("already idle");

        var open = downtime.report(operator, report(m1, "NO_OPERATOR", null, null), id());
        var running = new MaintenanceCommands.MachineStatus("RUNNING", "Line restarted after the shift change");
        assertThatThrownBy(() -> machines.correct(planner, m1, running, id())).hasMessageContaining("open downtime");
        downtime.close(operator, str(open, "id"), new MaintenanceCommands.CloseDowntime(null, null), id());
        machines.correct(planner, m1, running, id());
        assertThat(machineStatus(m1)).isEqualTo("RUNNING");
        var manual = row(
                "SELECT * FROM machine_status_changes WHERE tenant_id=? AND machine_id=? AND source='MANUAL'", t, m1);
        assertThat(manual.get("from_status")).isEqualTo("IDLE");
        assertThat(manual.get("reason")).isEqualTo("Line restarted after the shift change");
        assertThat(actions("M1")).contains("MACHINE_STATUS_CORRECTED");

        // A legacy MAINTENANCE status without an order can be corrected.
        String legacy = machine("M9", "MAINTENANCE");
        machines.correct(planner, legacy, idle, id());
        assertThat(machineStatus(legacy)).isEqualTo("IDLE");
    }

    @Test
    void rolesTenantsAndIdempotentRetries() throws Exception {
        command(viewer, "/api/maintenance/work-orders", corrective(m1, false)).andExpect(status().isForbidden());
        command(warehouse, "/api/maintenance/work-orders", corrective(m1, false))
                .andExpect(status().isForbidden());
        command(technician, "/api/maintenance/work-orders", corrective(m1, false))
                .andExpect(status().isOk());

        // The MAINTENANCE role is a real account role.
        command(
                        admin,
                        "/api/users",
                        Map.of(
                                "email", "tech-" + t.substring(0, 8) + "@example.test",
                                "name", "Technician",
                                "password", "a-long-enough-password-123",
                                "role", "MAINTENANCE"))
                .andExpect(status().isOk());
        command(planner, "/api/maintenance/settings", new MaintenanceCommands.Settings(new BigDecimal("2000")))
                .andExpect(status().isForbidden());
        command(admin, "/api/maintenance/settings", new MaintenanceCommands.Settings(new BigDecimal("2000")))
                .andExpect(status().isOk());
        assertThat(dec(query.settings(viewer).get("labourRate"))).isEqualByComparingTo("2000");

        // Another tenant cannot see or act on these orders.
        String id = approvedOrder(m1, false);
        var other = createTenant();
        var outsiderTech = createUser(other.id(), "MAINTENANCE");
        var outsiderPlanner = createUser(other.id(), "PLANNER");
        command(outsiderTech, "/api/maintenance/work-orders/" + id + "/start", null)
                .andExpect(status().isNotFound());
        command(outsiderPlanner, "/api/maintenance/work-orders/" + id + "/cancel", new MaintenanceCommands.Cancel("x"))
                .andExpect(status().isNotFound());
        query(outsiderPlanner, "/api/maintenance/work-orders/" + id).andExpect(status().isNotFound());

        // A retried start replays the stored result without a second status change; the key is bound to its action.
        String key = id();
        var first = json(command(technician, "/api/maintenance/work-orders/" + id + "/start", null, key)
                .andExpect(status().isOk()));
        var retry = json(command(technician, "/api/maintenance/work-orders/" + id + "/start", null, key)
                .andExpect(status().isOk()));
        assertThat(retry.get("status")).isEqualTo(first.get("status"));
        assertThat(count("SELECT COUNT(*) FROM machine_status_changes WHERE tenant_id=? AND machine_id=?", t, m1))
                .isEqualTo(1);
        command(
                        technician,
                        "/api/maintenance/work-orders/" + id + "/labour",
                        new MaintenanceCommands.Labour(BigDecimal.ONE, null, null),
                        key)
                .andExpect(status().isConflict());
    }
}
