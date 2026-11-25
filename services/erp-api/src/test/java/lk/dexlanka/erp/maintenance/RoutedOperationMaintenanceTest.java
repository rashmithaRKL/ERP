package lk.dexlanka.erp.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import lk.dexlanka.erp.RoutingTestBase;
import lk.dexlanka.erp.manufacturing.OperationCommands;
import lk.dexlanka.erp.manufacturing.OperationService;
import lk.dexlanka.erp.platform.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V10 × V11: a routed order runs where its operations run, not on the order's own work center. Maintenance is refused
 * on a work center with an operation in progress and allowed once it is paused; the paused operation resumes only after
 * the work order completes.
 */
class RoutedOperationMaintenanceTest extends RoutingTestBase {
    @Autowired
    OperationService operations;

    @Autowired
    WorkOrderService orders;

    Actor technician;
    String order, number, op10, op20;

    @BeforeEach
    void routedOrderRunningOnPackaging() {
        technician = createUser(t, "MAINTENANCE");
        approvedRouting(bottle, "R1", op(10, "Blow moulding", bm1), op(20, "Leak test & pack", pk1));
        order = releasedOrder("1000", bm1, "NORMAL");
        number = str(orderRow(order), "number");
        op10 = opId(10);
        op20 = opId(20);
        operations.start(operator, op10, new OperationCommands.Start(null, "RUN", null), id());
        operations.finish(operator, op10, new OperationCommands.Quantities(bd("1000"), bd("0"), null), id());
        operations.start(operator, op20, new OperationCommands.Start(null, "RUN", null), id());
        assertThat(orderRow(order).get("machine_id")).isEqualTo(bm1);
        assertThat(machineStatus(pk1)).isEqualTo("RUNNING");
    }

    private String opId(int seq) {
        return str(row("SELECT id FROM mo_operations WHERE tenant_id=? AND order_id=? AND seq=?", t, order, seq), "id");
    }

    private String approvedWorkOrder(String machine) {
        String id = str(
                orders.request(
                        operator,
                        new MaintenanceCommands.RequestWorkOrder(
                                machine, "Inspect drive", null, "HIGH", false, null, null, null),
                        id()),
                "id");
        orders.approve(planner, id, new MaintenanceCommands.Approve(null, null, technician.id()), id());
        return id;
    }

    @Test
    void maintenanceWaitsForTheRunningOperationNotTheOrdersOwnWorkCenter() {
        // The order's own work center (op 10 is done) is free for maintenance.
        String onBlowMoulder = approvedWorkOrder(bm1);
        assertThat(orders.start(technician, onBlowMoulder, id()).get("status")).isEqualTo("IN_PROGRESS");
        assertThat(machineStatus(bm1)).isEqualTo("MAINTENANCE");

        // The packaging line runs op 20: refused until the operation is paused.
        String onPackaging = approvedWorkOrder(pk1);
        assertThatThrownBy(() -> orders.start(technician, onPackaging, id()))
                .hasMessageContaining(number + " is running on PK-1")
                .hasMessageContaining("running operation");
        assertThat(machineStatus(pk1)).isEqualTo("RUNNING");

        operations.pause(operator, op20, new OperationCommands.Pause("Drive check"), id());
        assertThat(machineStatus(pk1)).isEqualTo("IDLE");
        assertThat(orders.start(technician, onPackaging, id()).get("status")).isEqualTo("IN_PROGRESS");
        assertThat(machineStatus(pk1)).isEqualTo("MAINTENANCE");

        // The operation cannot resume on a work center under maintenance; it can once the work order completes.
        assertThatThrownBy(() -> operations.resume(operator, op20, new OperationCommands.Resume(null, null), id()))
                .hasMessageContaining("maintenance");
        orders.complete(
                technician, onPackaging, new MaintenanceCommands.Complete("Drive OK", null, List.of(), null), id());
        assertThat(machineStatus(pk1)).isEqualTo("IDLE");
        assertThat(operations
                        .resume(operator, op20, new OperationCommands.Resume(null, null), id())
                        .get("status"))
                .isEqualTo("IN_PROGRESS");
        assertThat(machineStatus(pk1)).isEqualTo("RUNNING");
    }
}
