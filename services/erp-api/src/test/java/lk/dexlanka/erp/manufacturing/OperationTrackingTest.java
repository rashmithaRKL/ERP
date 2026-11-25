package lk.dexlanka.erp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.RoutingTestBase;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V10 manufacturing-order operations: routing snapshot, sequencing, start/pause/resume/setup-done/report/finish, time
 * logs, work-center status, skills, output posting, cascades from the order lifecycle, and orders without a routing.
 */
class OperationTrackingTest extends RoutingTestBase {
    @Autowired
    OperationService operations;

    @Autowired
    QualityService qualityService;

    String blowSkill, routing;

    @BeforeEach
    void routedBottle() {
        blowSkill = skill("BLOW");
        grant(operator, blowSkill, 2, LocalDate.now().plusMonths(6));
        routing = approvedRouting(
                bottle,
                "R1",
                op(10, "Blow moulding", bm1, List.of(bm2), "30", "0.05", "1", blowSkill, 2, false),
                op(20, "Leak test & pack", pk1, List.of(), "0", "0.02", "2", null, null, true));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> ids(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items"))
                .stream().map(r -> r.get("id")).toList();
    }

    private Map<String, Object> op(String order, int seq) {
        return row("SELECT * FROM mo_operations WHERE tenant_id=? AND order_id=? AND seq=?", t, order, seq);
    }

    private String opId(String order, int seq) {
        return str(op(order, seq), "id");
    }

    private Map<String, Object> start(Actor actor, String operation, String machine, String reason) throws Exception {
        return json(command(
                        actor,
                        "/api/operations/" + operation + "/start",
                        new OperationCommands.Start(machine, null, reason))
                .andExpect(status().isOk()));
    }

    private void qty(Actor actor, String operation, String path, String good, String scrap, String notes)
            throws Exception {
        command(
                        actor,
                        "/api/operations/" + operation + "/" + path,
                        new OperationCommands.Quantities(bd(good), bd(scrap), notes))
                .andExpect(status().isOk());
    }

    @Test
    void creationSnapshotsTheCurrentRoutingWithPlannedMinutesForTheOrderQuantity() throws Exception {
        String order = order("1000", bm1, "NORMAL");
        var o = orderRow(order);
        assertThat(o).containsEntry("routing_id", routing).containsEntry("routing_revision", "R1");
        var blow = op(order, 10);
        assertThat(blow).containsEntry("status", "PENDING").containsEntry("machine_id", bm1);
        assertThat(dec(blow.get("setup_minutes"))).isEqualByComparingTo("30");
        assertThat(dec(blow.get("planned_run_minutes"))).isEqualByComparingTo("50");
        assertThat(dec(blow.get("planned_minutes"))).isEqualByComparingTo("80");
        assertThat(dec(blow.get("planned_quantity"))).isEqualByComparingTo("1000");
        assertThat(blow).containsEntry("skill_id", blowSkill).containsEntry("skill_level", 2);
        var pack = op(order, 20);
        assertThat(dec(pack.get("planned_run_minutes"))).isEqualByComparingTo("20");
        assertThat(dec(pack.get("crew_size"))).isEqualByComparingTo("2");
        assertThat(pack).containsEntry("inspection_point", true).containsEntry("machine_id", pk1);
        assertThat(str(
                        row(
                                "SELECT detail FROM audit_events WHERE tenant_id=? AND action='MO_CREATED' AND reference=?",
                                t,
                                o.get("number")),
                        "detail"))
                .contains("routing R1 (2 operations)");

        // An order on the alternate work center runs the operations it may run there; decimal-safe minutes.
        String onAlternate = order("333", bm2, "NORMAL");
        assertThat(op(onAlternate, 10)).containsEntry("machine_id", bm2).containsEntry("planned_machine_id", bm2);
        assertThat(op(onAlternate, 20)).containsEntry("machine_id", pk1);
        assertThat(dec(op(onAlternate, 10).get("planned_run_minutes"))).isEqualByComparingTo("16.65");

        // A new approved revision does not change existing orders; the snapshot itself cannot be edited.
        String r2 = str(
                routings.copy(planner, routing, new lk.dexlanka.erp.routing.RoutingCommands.Copy("R2"), id()), "id");
        routings.approve(otherPlanner, r2, id());
        assertThat(orderRow(order)).containsEntry("routing_id", routing);
        assertThat(orderRow(order("10", bm1, "NORMAL"))).containsEntry("routing_id", r2);
        assertThatThrownBy(() ->
                        update("UPDATE mo_operations SET setup_minutes=1 WHERE tenant_id=? AND order_id=?", t, order))
                .hasMessageContaining("snapshot");
        assertThatThrownBy(() -> update("DELETE FROM mo_operations WHERE tenant_id=? AND order_id=?", t, order))
                .hasMessageContaining("cannot be deleted");

        var read = json(
                query(viewer, "/api/manufacturing/" + order + "/operations").andExpect(status().isOk()));
        assertThat(read.get("operations")).asList().hasSize(2);
        @SuppressWarnings("unchecked")
        var r = (Map<String, Object>) read.get("routing");
        assertThat(r).containsEntry("revision", "R1");
    }

    @Test
    void operationsRunInSequenceWithTimeLogsAndWorkCenterStatus() throws Exception {
        String order = order("1000", bm1, "NORMAL");
        String blow = opId(order, 10), pack = opId(order, 20);
        command(operator, "/api/operations/" + blow + "/start", new OperationCommands.Start(null, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("Issue the materials")));
        mo.transition(otherPlanner, order, "approve", id());
        mo.transition(planner, order, "reserve", id());
        mo.transition(warehouse, order, "issue", id());
        assertThat(op(order, 10)).containsEntry("status", "READY");
        assertThat(op(order, 20)).containsEntry("status", "PENDING");
        command(operator, "/api/operations/" + pack + "/start", new OperationCommands.Start(null, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("previous operation")));

        // Start: the released order starts through the existing lifecycle; setup first (setup minutes planned).
        var started = start(operator, blow, null, null);
        assertThat(started).containsEntry("status", "IN_PROGRESS").containsEntry("activity", "SETUP");
        var o = orderRow(order);
        assertThat(o.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(o.get("started_at")).isNotNull();
        assertThat(machineStatus(bm1)).isEqualTo("RUNNING");
        assertThat(op(order, 10)).containsEntry("operator_id", operator.id());

        command(operator, "/api/operations/" + blow + "/setup-done", null).andExpect(status().isOk());
        command(operator, "/api/operations/" + blow + "/setup-done", null).andExpect(status().isConflict());
        command(operator, "/api/operations/" + blow + "/pause", new OperationCommands.Pause("Material change"))
                .andExpect(status().isOk());
        assertThat(op(order, 10)).containsEntry("status", "PAUSED");
        assertThat(machineStatus(bm1)).isEqualTo("IDLE");
        command(operator2, "/api/operations/" + blow + "/resume", new OperationCommands.Resume(null, null))
                .andExpect(status().isConflict()); // operator2 lacks the blow moulding skill
        command(operator, "/api/operations/" + blow + "/resume", new OperationCommands.Resume(null, null))
                .andExpect(status().isOk());
        assertThat(op(order, 10)).containsEntry("activity", "RUN");
        assertThat(machineStatus(bm1)).isEqualTo("RUNNING");

        // Quantities: scrap needs a reason, whole pieces, never more than the input.
        command(
                        operator,
                        "/api/operations/" + blow + "/report",
                        new OperationCommands.Quantities(bd("10"), bd("5"), ""))
                .andExpect(status().isConflict());
        command(
                        operator,
                        "/api/operations/" + blow + "/report",
                        new OperationCommands.Quantities(bd("1.5"), bd("0"), ""))
                .andExpect(status().isConflict());
        qty(operator, blow, "report", "600", "10", "Start-up rejects");
        command(
                        operator,
                        "/api/operations/" + blow + "/report",
                        new OperationCommands.Quantities(bd("391"), bd("0"), ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("at most 1000")));
        qty(operator, blow, "finish", "380", "10", "Trim");
        var done = op(order, 10);
        assertThat(done).containsEntry("status", "DONE");
        assertThat(dec(done.get("good_qty"))).isEqualByComparingTo("980");
        assertThat(dec(done.get("scrap_qty"))).isEqualByComparingTo("20");
        assertThat(done.get("finished_at")).isNotNull();
        assertThat(op(order, 20)).containsEntry("status", "READY");
        assertThat(machineStatus(bm1)).isEqualTo("IDLE");
        assertThatThrownBy(() -> update("UPDATE mo_operations SET good_qty=1 WHERE tenant_id=? AND id=?", t, blow))
                .hasMessageContaining("immutable");

        // Time logs: SETUP (switched), RUN (paused), RUN (finished); closed rows are immutable.
        var logs = rows(
                "SELECT * FROM mo_operation_time_logs WHERE tenant_id=? AND operation_id=? ORDER BY started_at,id",
                t,
                blow);
        assertThat(logs)
                .extracting(l -> l.get("activity"), l -> l.get("end_reason"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SETUP", "SWITCHED"),
                        org.assertj.core.groups.Tuple.tuple("RUN", "PAUSED"),
                        org.assertj.core.groups.Tuple.tuple("RUN", "FINISHED"));
        assertThat(logs).allSatisfy(l -> {
            assertThat(l.get("minutes")).isNotNull();
            assertThat(l.get("machine_id")).isEqualTo(bm1);
        });
        assertThatThrownBy(() -> update(
                        "UPDATE mo_operation_time_logs SET ended_at=now() WHERE tenant_id=? AND operation_id=?",
                        t,
                        blow))
                .hasMessageContaining("immutable");
        assertThat(count("SELECT COUNT(*) FROM mo_operation_reports WHERE tenant_id=? AND operation_id=?", t, blow))
                .isEqualTo(2);

        // Operation 20: input = 980 good from operation 10; crew of two doubles labour minutes.
        start(operator2, pack, null, null); // no skill required
        assertThat(op(order, 20)).containsEntry("activity", "RUN");
        assertThat(machineStatus(pk1)).isEqualTo("RUNNING");
        command(
                        operator2,
                        "/api/operations/" + pack + "/report",
                        new OperationCommands.Quantities(bd("981"), bd("0"), ""))
                .andExpect(status().isConflict());
        qty(operator2, pack, "finish", "970", "10", "Leak test failures");
        assertThat(machineStatus(pk1)).isEqualTo("IDLE");
        var packLog = row("SELECT * FROM mo_operation_time_logs WHERE tenant_id=? AND operation_id=?", t, pack);
        assertThat(dec(packLog.get("labour_minutes")))
                .isCloseTo(
                        dec(packLog.get("minutes")).multiply(bd("2")),
                        org.assertj.core.api.Assertions.within(bd("0.00001")));

        var read = json(query(viewer, "/api/manufacturing/" + order + "/operations"));
        assertThat(read.get("logs")).asList().hasSize(4);
        assertThat(rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND reference=? AND action LIKE 'OP_%' ORDER BY created_at,id",
                        t, o.get("number")))
                .extracting(e -> e.get("action"))
                .containsExactlyInAnyOrder( // audits of one command share its transaction timestamp
                        "OP_STARTED",
                        "OP_SETUP_DONE",
                        "OP_PAUSED",
                        "OP_RESUMED",
                        "OP_REPORTED",
                        "OP_REPORTED",
                        "OP_FINISHED",
                        "OP_STARTED",
                        "OP_REPORTED",
                        "OP_FINISHED");
    }

    @Test
    void outputPostingFollowsTheLastOperation() throws Exception {
        String order = releasedOrder("1000", bm1, "NORMAL");
        String blow = opId(order, 10), pack = opId(order, 20);
        start(operator, blow, null, null);
        qty(operator, blow, "finish", "980", "20", "Start-up");
        start(operator, pack, null, null);
        qty(operator, pack, "report", "500", "0", "");
        var complete = new ManufacturingCommands.Complete(bd("600"), bd("0"), bd("0"), bd("0"), "");
        command(operator, "/api/manufacturing/" + order + "/complete", complete)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("last operation")));
        command(
                        operator,
                        "/api/manufacturing/" + order + "/complete",
                        new ManufacturingCommands.Complete(bd("500"), bd("0"), bd("100"), bd("50"), ""))
                .andExpect(status().isOk());
        assertThat(orderRow(order)).containsEntry("status", "PARTIALLY_COMPLETED");
        qty(operator, pack, "report", "470", "10", "Leaks");
        // The final output (everything remaining) needs every operation done.
        var last = new ManufacturingCommands.Complete(bd("470"), bd("30"), bd("0"), bd("0"), "Blow and leak scrap");
        command(operator, "/api/manufacturing/" + order + "/complete", last)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("Finish every operation")));
        qty(operator, pack, "finish", "0", "0", "");
        command(operator, "/api/manufacturing/" + order + "/complete", last).andExpect(status().isOk());
        assertThat(orderRow(order)).containsEntry("status", "QUALITY_REVIEW");
    }

    @Test
    void maintenanceAlternatesAndBusyWorkCenters() throws Exception {
        String order = releasedOrder("100", bm1, "NORMAL");
        String blow = opId(order, 10);
        // The maintenance workflow owns MAINTENANCE; operations only read it.
        update("UPDATE machines SET status='MAINTENANCE' WHERE tenant_id=? AND id=?", t, bm1);
        command(operator, "/api/operations/" + blow + "/start", new OperationCommands.Start(null, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("under maintenance")));
        command(operator, "/api/operations/" + blow + "/start", new OperationCommands.Start(pk1, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("alternate")));
        assertThat(orderRow(order)).containsEntry("status", "RELEASED");
        // The alternate work center works, although the order's own work center is under maintenance.
        start(operator, blow, bm2, null);
        assertThat(op(order, 10)).containsEntry("machine_id", bm2).containsEntry("planned_machine_id", bm1);
        assertThat(machineStatus(bm2)).isEqualTo("RUNNING");
        assertThat(machineStatus(bm1)).isEqualTo("MAINTENANCE");

        // One running operation per work center.
        String second = releasedOrder("100", bm2, "URGENT");
        command(
                        operator,
                        "/api/operations/" + opId(second, 10) + "/start",
                        new OperationCommands.Start(null, "RUN", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("is running")));

        // Maintenance declared while running: pausing keeps MAINTENANCE.
        update("UPDATE machines SET status='MAINTENANCE' WHERE tenant_id=? AND id=?", t, bm2);
        command(operator, "/api/operations/" + blow + "/pause", new OperationCommands.Pause("Breakdown"))
                .andExpect(status().isOk());
        assertThat(machineStatus(bm2)).isEqualTo("MAINTENANCE");

        // A running order without a routing keeps its work center RUNNING when an operation there finishes.
        update("UPDATE machines SET status='IDLE' WHERE tenant_id=? AND id IN (?,?)", t, bm1, bm2);
        String plainProduct = item("PLAIN", "FINISHED_GOOD", "pcs", "1");
        String plainBom = str(
                catalog.bom(
                        planner,
                        new lk.dexlanka.erp.catalog.CatalogCommands.Bom(
                                plainProduct,
                                "P1",
                                bd("1000"),
                                bd("1"),
                                List.of(new lk.dexlanka.erp.catalog.CatalogCommands.BomLine(resin, bd("10")))),
                        id()),
                "id");
        catalog.approveBom(admin, plainBom, id());
        String plain = str(
                mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                plainBom,
                                bm2,
                                null,
                                bd("100"),
                                "NORMAL",
                                LocalDate.now().toString()),
                        id()),
                "id");
        mo.transition(otherPlanner, plain, "approve", id());
        mo.transition(planner, plain, "reserve", id());
        mo.transition(warehouse, plain, "issue", id());
        mo.transition(operator, plain, "start", id());
        command(operator, "/api/operations/" + blow + "/resume", new OperationCommands.Resume("RUN", null))
                .andExpect(status().isOk());
        qty(operator, blow, "finish", "100", "0", "");
        assertThat(machineStatus(bm2)).isEqualTo("RUNNING"); // set by the resume, kept for the plain order
    }

    @Test
    void skillsAreEnforcedAndOnlySupervisorsOverride() throws Exception {
        String order = releasedOrder("100", bm1, "NORMAL");
        String blow = opId(order, 10);
        var noSkill = new OperationCommands.Start(null, null, null);
        command(operator2, "/api/operations/" + blow + "/start", noSkill)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("does not hold the required skill")));
        command(operator2, "/api/operations/" + blow + "/start", new OperationCommands.Start(null, null, "Urgent"))
                .andExpect(status().isConflict()); // operators cannot override
        grant(operator2, blowSkill, 1, null);
        command(operator2, "/api/operations/" + blow + "/start", noSkill)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("level 1")));
        grant(operator2, blowSkill, 3, LocalDate.now().minusDays(1));
        command(operator2, "/api/operations/" + blow + "/start", noSkill)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("expired")));
        command(planner, "/api/operations/" + blow + "/start", noSkill)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("override reason")));
        assertThat(op(order, 10)).containsEntry("status", "READY");

        start(planner, blow, null, "Certified operator on leave; supervised trial");
        var log = row("SELECT * FROM mo_operation_time_logs WHERE tenant_id=? AND operation_id=?", t, blow);
        assertThat(str(log, "skill_override_reason")).contains("supervised trial");
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='OP_SKILL_OVERRIDDEN'", t))
                .isEqualTo(1);
    }

    @Test
    void orderPauseAndCancelCascadeToOperations() throws Exception {
        String order = releasedOrder("100", bm1, "NORMAL");
        String blow = opId(order, 10);
        start(operator, blow, null, "");
        mo.transition(operator, order, "pause", id());
        assertThat(op(order, 10)).containsEntry("status", "PAUSED");
        assertThat(row("SELECT end_reason FROM mo_operation_time_logs WHERE tenant_id=? AND operation_id=?", t, blow))
                .containsEntry("end_reason", "ORDER_PAUSED");
        assertThat(machineStatus(bm1)).isEqualTo("IDLE");
        command(operator, "/api/operations/" + blow + "/resume", new OperationCommands.Resume(null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("on hold")));
        mo.transition(operator, order, "resume", id());
        command(operator, "/api/operations/" + blow + "/resume", new OperationCommands.Resume(null, null))
                .andExpect(status().isOk());

        String draft = order("50", bm1, "NORMAL");
        mo.transition(planner, draft, "cancel", id());
        assertThat(rows("SELECT status FROM mo_operations WHERE tenant_id=? AND order_id=?", t, draft))
                .extracting(r -> r.get("status"))
                .containsOnly("CANCELLED");
    }

    @Test
    void ordersWithoutARoutingKeepTheirLifecycle() throws Exception {
        String plainProduct = item("PLAIN", "FINISHED_GOOD", "pcs", "1");
        String plainBom = str(
                catalog.bom(
                        planner,
                        new lk.dexlanka.erp.catalog.CatalogCommands.Bom(
                                plainProduct,
                                "P1",
                                bd("1000"),
                                bd("1"),
                                List.of(new lk.dexlanka.erp.catalog.CatalogCommands.BomLine(resin, bd("10")))),
                        id()),
                "id");
        catalog.approveBom(admin, plainBom, id());
        String order = str(
                mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                plainBom,
                                bm1,
                                null,
                                bd("100"),
                                "NORMAL",
                                LocalDate.now().toString()),
                        id()),
                "id");
        assertThat(orderRow(order).get("routing_id")).isNull();
        assertThat(count("SELECT COUNT(*) FROM mo_operations WHERE tenant_id=? AND order_id=?", t, order))
                .isZero();
        mo.transition(otherPlanner, order, "approve", id());
        mo.transition(planner, order, "reserve", id());
        mo.transition(warehouse, order, "issue", id());
        mo.transition(operator, order, "start", id());
        mo.transition(operator, order, "pause", id());
        mo.transition(operator, order, "resume", id());
        assertThat(machineStatus(bm1)).isEqualTo("IDLE"); // orders without operations never change work-center status
        mo.complete(
                operator, order, new ManufacturingCommands.Complete(bd("100"), bd("0"), bd("10"), bd("5"), ""), id());
        var inspection = row(
                "SELECT i.id FROM inspections i JOIN lots l ON l.tenant_id=i.tenant_id AND l.id=i.lot_id WHERE l.tenant_id=? AND l.source_id=?",
                t,
                order);
        qualityService.inspect(quality, str(inspection, "id"), new QualityCommands.Inspect("PASSED", "ok"), id());
        mo.transition(planner, order, "close", id());
        assertThat(orderRow(order)).containsEntry("status", "CLOSED");

        var read = json(
                query(viewer, "/api/manufacturing/" + order + "/operations").andExpect(status().isOk()));
        assertThat(read.get("routing")).isNull();
        assertThat(read.get("operations")).asList().isEmpty();
        var floor = json(query(viewer, "/api/manufacturing/orders?status=all&routing=none"));
        assertThat(ids(floor)).contains(order);
        var routed = json(query(viewer, "/api/manufacturing/orders?status=all&routing=routed"));
        assertThat(ids(routed)).doesNotContain(order);
        query(viewer, "/api/manufacturing/orders?routing=maybe").andExpect(status().isConflict());
    }

    @Test
    void permissionsIdempotencyAndTenantIsolation() throws Exception {
        String order = releasedOrder("100", bm1, "NORMAL");
        String blow = opId(order, 10);
        var body = new OperationCommands.Start(null, "RUN", null);
        command(viewer, "/api/operations/" + blow + "/start", body).andExpect(status().isForbidden());
        command(warehouse, "/api/operations/" + blow + "/start", body).andExpect(status().isForbidden());
        command(quality, "/api/operations/" + blow + "/pause", new OperationCommands.Pause(""))
                .andExpect(status().isForbidden());

        String key = id();
        var first = json(command(operator, "/api/operations/" + blow + "/start", body, key)
                .andExpect(status().isOk()));
        var replay = json(command(operator, "/api/operations/" + blow + "/start", body, key)
                .andExpect(status().isOk()));
        assertThat(replay.get("status")).isEqualTo(first.get("status"));
        assertThat(count("SELECT COUNT(*) FROM mo_operation_time_logs WHERE tenant_id=? AND operation_id=?", t, blow))
                .isEqualTo(1);
        command(operator, "/api/operations/" + blow + "/start", new OperationCommands.Start(null, "SETUP", null), key)
                .andExpect(status().isConflict());

        var other = createTenant();
        var outsider = createUser(other.id(), "OPERATOR");
        command(outsider, "/api/operations/" + blow + "/pause", new OperationCommands.Pause(""))
                .andExpect(status().isNotFound());
        query(outsider, "/api/operations/" + blow).andExpect(status().isNotFound());
        query(outsider, "/api/operations/queue?machineId=" + bm1).andExpect(status().isNotFound());
        query(outsider, "/api/manufacturing/" + order + "/operations").andExpect(status().isNotFound());
        assertThat(json(query(outsider, "/api/operations/time-logs")).get("items"))
                .asList()
                .isEmpty();
        // Failed commands leave nothing behind.
        assertThat(op(order, 10)).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    void queueWorkCentersAndTimeLogReads() throws Exception {
        String normal = releasedOrder("100", bm1, "NORMAL");
        String urgent = releasedOrder("100", bm1, "URGENT");
        String onAlternate = releasedOrder("100", bm2, "HIGH");
        start(operator, opId(normal, 10), null, null);

        var queue =
                json(query(operator, "/api/operations/queue?machineId=" + bm1).andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) queue.get("items");
        // Running first, then by priority: urgent (bm1), then the HIGH order planned on bm2 offered as an alternate.
        assertThat(items).extracting(i -> i.get("order_id")).containsExactly(normal, urgent, onAlternate);
        assertThat(items.get(2)).containsEntry("alternate", true);
        assertThat(items.get(1)).containsEntry("skill_check", "QUALIFIED").containsEntry("alternate", false);
        assertThat(((Number) items.get(1).get("input_qty")).intValue()).isEqualTo(100);
        var page = json(query(operator, "/api/operations/queue?machineId=" + bm1 + "&limit=2"));
        assertThat(page.get("items")).asList().hasSize(2);
        var rest =
                json(query(operator, "/api/operations/queue?machineId=" + bm1 + "&limit=2&before=" + page.get("next")));
        @SuppressWarnings("unchecked")
        var restItems = (List<Map<String, Object>>) rest.get("items");
        assertThat(restItems).extracting(i -> i.get("order_id")).containsExactly(onAlternate);
        var asOperator2 = json(query(operator2, "/api/operations/queue?machineId=" + bm1));
        @SuppressWarnings("unchecked")
        var other = (List<Map<String, Object>>) asOperator2.get("items");
        assertThat(other.get(1)).containsEntry("skill_check", "MISSING");

        var centers = json(query(viewer, "/api/operations/work-centers").andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) centers.get("items");
        var b1 = rows.stream().filter(r -> bm1.equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(((Number) b1.get("running")).intValue()).isEqualTo(1);
        assertThat(((Number) b1.get("ready")).intValue()).isEqualTo(1);
        assertThat(((Number) b1.get("available_minutes_today")).intValue()).isEqualTo(1440);

        var detail = json(query(viewer, "/api/operations/" + opId(normal, 10)).andExpect(status().isOk()));
        assertThat(detail.get("machines")).asList().hasSize(2);
        assertThat(detail.get("logs")).asList().hasSize(1);

        var logs =
                json(query(viewer, "/api/operations/time-logs?machineId=" + bm1).andExpect(status().isOk()));
        assertThat(logs.get("items")).asList().hasSize(1);
        assertThat(json(query(viewer, "/api/operations/time-logs?orderId=" + urgent))
                        .get("items"))
                .asList()
                .isEmpty();
    }
}
