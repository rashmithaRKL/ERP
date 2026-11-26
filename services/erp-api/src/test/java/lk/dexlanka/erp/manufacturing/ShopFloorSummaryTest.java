package lk.dexlanka.erp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.RoutingTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V16 shop-floor consistency: the work-center summary counts open orders without a routing (one implicit operation
 * each: in progress = running, on hold = paused, released = ready) next to the routed operations, so a work center that
 * runs such an order never reads "0 running"; and a queued operation carries its position among the order's operations
 * ("Step 2 of 2 · Op 20"), not just its sequence number.
 */
class ShopFloorSummaryTest extends RoutingTestBase {
    @Autowired
    OperationService operations;

    @SuppressWarnings("unchecked")
    private Map<String, Object> center(String machine) throws Exception {
        var items = (List<Map<String, Object>>)
                json(query(operator, "/api/operations/work-centers").andExpect(status().isOk()))
                        .get("items");
        return items.stream()
                .filter(m -> machine.equals(m.get("id")))
                .findFirst()
                .orElseThrow();
    }

    private static int n(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    @Test
    void workCenterCountsIncludeOrdersWithoutARouting() throws Exception {
        // Three orders without a routing on BM-2: released (ready), in progress (running) and on hold (paused).
        releasedOrder("10", bm2, "NORMAL");
        String running = releasedOrder("10", bm2, "NORMAL");
        mo.transition(operator, running, "start", id());
        String held = releasedOrder("10", bm2, "NORMAL");
        mo.transition(operator, held, "start", id());
        mo.transition(operator, held, "pause", id());

        var b2 = center(bm2);
        assertThat(b2.get("status")).isEqualTo("RUNNING");
        assertThat(n(b2, "running") + n(b2, "paused") + n(b2, "ready")).isZero(); // no routed operations
        assertThat(n(b2, "legacy_orders")).isEqualTo(3);
        assertThat(n(b2, "legacy_running")).isEqualTo(1);
        assertThat(n(b2, "legacy_paused")).isEqualTo(1);
        assertThat(n(b2, "legacy_ready")).isEqualTo(1);

        // Finished or not yet released orders are not shop-floor work.
        mo.complete(
                operator, running, new ManufacturingCommands.Complete(bd("10"), bd("0"), bd("0"), bd("0"), ""), id());
        order("10", bm2, "NORMAL");
        b2 = center(bm2);
        assertThat(n(b2, "legacy_orders")).isEqualTo(2);
        assertThat(n(b2, "legacy_running")).isZero();
        assertThat(b2.get("status")).isEqualTo("IDLE");
        assertThat(n(center(bm1), "legacy_orders")).isZero();
    }

    @Test
    void queuedOperationsCarryTheirStepPosition() throws Exception {
        approvedRouting(bottle, "R1", op(10, "Blow", bm1), op(20, "Pack", pk1));
        String order = releasedOrder("100", bm1, "NORMAL");
        String blow =
                str(row("SELECT id FROM mo_operations WHERE tenant_id=? AND order_id=? AND seq=10", t, order), "id");
        operations.start(operator, blow, new OperationCommands.Start(null, null, null), id());
        operations.finish(operator, blow, new OperationCommands.Quantities(bd("100"), bd("0"), ""), id());

        @SuppressWarnings("unchecked")
        var queue = (List<Map<String, Object>>)
                json(query(operator, "/api/operations/queue?machineId=" + pk1).andExpect(status().isOk()))
                        .get("items");
        assertThat(queue).hasSize(1);
        var pack = queue.getFirst();
        assertThat(n(pack, "seq")).isEqualTo(20);
        assertThat(n(pack, "operation_position")).isEqualTo(2);
        assertThat(n(pack, "operation_count")).isEqualTo(2);
        assertThat(n(center(pk1), "ready")).isEqualTo(1);
    }
}
