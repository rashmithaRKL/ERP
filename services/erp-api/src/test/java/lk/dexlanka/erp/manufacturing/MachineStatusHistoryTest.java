package lk.dexlanka.erp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.RoutingTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V16: production-driven RUNNING/IDLE changes of a work center are recorded in {@code machine_status_changes} like the
 * maintenance ones (source OPERATION for routed operations, ORDER for orders without a routing), appear in the machine
 * history, never touch MAINTENANCE and are not repeated by idempotent retries.
 */
class MachineStatusHistoryTest extends RoutingTestBase {
    @Autowired
    OperationService operations;

    private List<Map<String, Object>> changes(String machine) {
        return rows(
                "SELECT from_status,to_status,source,reference,reason,changed_by FROM machine_status_changes WHERE tenant_id=? AND machine_id=? ORDER BY changed_at,id",
                t,
                machine);
    }

    private String opId(String order, int seq) {
        return str(row("SELECT id FROM mo_operations WHERE tenant_id=? AND order_id=? AND seq=?", t, order, seq), "id");
    }

    @Test
    void routedOperationsRecordRunningAndIdleWithTheOrderReference() throws Exception {
        approvedRouting(bottle, "R1", op(10, "Blow", bm1), op(20, "Pack", pk1));
        String order = releasedOrder("100", bm1, "NORMAL");
        String number = str(orderRow(order), "number");
        String blow = opId(order, 10);

        String key = id();
        command(operator, "/api/operations/" + blow + "/start", new OperationCommands.Start(null, "RUN", null), key)
                .andExpect(status().isOk());
        // A retried start replays the stored result: no second status change.
        command(operator, "/api/operations/" + blow + "/start", new OperationCommands.Start(null, "RUN", null), key)
                .andExpect(status().isOk());
        assertThat(machineStatus(bm1)).isEqualTo("RUNNING");
        command(operator, "/api/operations/" + blow + "/pause", new OperationCommands.Pause("Coffee"))
                .andExpect(status().isOk());
        command(operator, "/api/operations/" + blow + "/resume", new OperationCommands.Resume(null, null))
                .andExpect(status().isOk());
        command(
                        operator,
                        "/api/operations/" + blow + "/finish",
                        new OperationCommands.Quantities(bd("100"), bd("0"), ""))
                .andExpect(status().isOk());
        assertThat(machineStatus(bm1)).isEqualTo("IDLE");

        var history = changes(bm1);
        assertThat(history)
                .extracting(r -> r.get("from_status") + ">" + r.get("to_status"))
                .containsExactly("IDLE>RUNNING", "RUNNING>IDLE", "IDLE>RUNNING", "RUNNING>IDLE");
        assertThat(history).allSatisfy(r -> {
            assertThat(r.get("source")).isEqualTo("OPERATION");
            assertThat(r.get("reference")).isEqualTo(number);
            assertThat(r.get("changed_by")).isEqualTo(operator.id());
        });
        assertThat(history.getFirst().get("reason")).isEqualTo("Op 10 Blow: run started");
        assertThat(history.getLast().get("reason")).isEqualTo("Op 10 Blow finished");

        // The machine drawer's history lists them (newest first), as production changes, not manual corrections.
        var drawer = json(
                query(viewer, "/api/maintenance/machines/" + bm1 + "/history").andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) drawer.get("items");
        assertThat(items)
                .filteredOn(i -> "STATUS".equals(i.get("kind")))
                .hasSize(4)
                .allSatisfy(i -> {
                    assertThat(i.get("reference")).isEqualTo(number);
                    assertThat(i.get("flag")).isEqualTo(false);
                    assertThat(i.get("source")).isEqualTo("OPERATION");
                });
        assertThat(items)
                .filteredOn(i -> !"STATUS".equals(i.get("kind")))
                .allSatisfy(i -> assertThat(i.get("source")).isNull());

        // Putting the order on hold pauses the next running operation and records the idle change.
        String pack = opId(order, 20);
        command(operator, "/api/operations/" + pack + "/start", new OperationCommands.Start(null, null, null))
                .andExpect(status().isOk());
        mo.transition(planner, order, "pause", id());
        assertThat(changes(pk1))
                .extracting(r -> r.get("to_status") + " " + r.get("reason"))
                .containsExactly("RUNNING Op 20 Pack: run started", "IDLE Op 20 Pack paused: order put on hold");
    }

    @Test
    void ordersWithoutARoutingDriveTheirWorkCenterToo() throws Exception {
        String order = releasedOrder("10", bm2, "NORMAL");
        String number = str(orderRow(order), "number");
        mo.transition(operator, order, "start", id());
        assertThat(machineStatus(bm2)).isEqualTo("RUNNING");
        mo.transition(operator, order, "pause", id());
        assertThat(machineStatus(bm2)).isEqualTo("IDLE");
        mo.transition(operator, order, "resume", id());
        // Partial output keeps the work center running; the final output frees it.
        mo.complete(operator, order, new ManufacturingCommands.Complete(bd("4"), bd("0"), bd("0"), bd("0"), ""), id());
        assertThat(machineStatus(bm2)).isEqualTo("RUNNING");
        mo.complete(operator, order, new ManufacturingCommands.Complete(bd("6"), bd("0"), bd("0"), bd("0"), ""), id());
        assertThat(machineStatus(bm2)).isEqualTo("IDLE");
        assertThat(changes(bm2))
                .extracting(r -> r.get("source") + " " + r.get("to_status") + " " + r.get("reason"))
                .containsExactly(
                        "ORDER RUNNING Production started",
                        "ORDER IDLE Order put on hold",
                        "ORDER RUNNING Production resumed",
                        "ORDER IDLE Final output posted");
        assertThat(changes(bm2)).extracting(r -> r.get("reference")).containsOnly(number);
    }

    @Test
    void maintenanceIsNeverOverriddenAndHistoryStaysAppendOnly() throws Exception {
        // A work center under maintenance: pausing an order without a routing that was running there never touches it,
        // and (V16, like starting it or resuming a routed operation) the order cannot resume until maintenance ends.
        String order = releasedOrder("10", bm1, "NORMAL");
        mo.transition(operator, order, "start", id());
        update("UPDATE machines SET status='MAINTENANCE' WHERE tenant_id=? AND id=?", t, bm1);
        mo.transition(operator, order, "pause", id());
        assertThatThrownBy(() -> mo.transition(operator, order, "resume", id()))
                .hasMessageContaining("Machine is under maintenance");
        assertThat(orderRow(order).get("status")).isEqualTo("ON_HOLD");
        assertThat(machineStatus(bm1)).isEqualTo("MAINTENANCE");
        assertThat(changes(bm1)).extracting(r -> r.get("to_status")).containsExactly("RUNNING");
        update("UPDATE machines SET status='IDLE' WHERE tenant_id=? AND id=?", t, bm1);
        mo.transition(operator, order, "resume", id());
        assertThat(machineStatus(bm1)).isEqualTo("RUNNING");

        // History rows cannot be changed or deleted; the source must be one of the four known sources.
        assertThatThrownBy(() -> update("UPDATE machine_status_changes SET reason='x' WHERE tenant_id=?", t))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> update(
                        "INSERT INTO machine_status_changes(id,tenant_id,machine_id,from_status,to_status,source,changed_by) VALUES(?,?,?,'IDLE','RUNNING','TELEMETRY',?)",
                        id(),
                        t,
                        bm1,
                        operator.id()))
                .hasMessageContaining("machine_status_changes_source_check");
    }
}
