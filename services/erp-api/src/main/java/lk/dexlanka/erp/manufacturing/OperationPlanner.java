package lk.dexlanka.erp.manufacturing;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Manufacturing-order operations (V10) as seen from the order lifecycle in {@link ManufacturingService}. Every method
 * runs inside the caller's command (tenant locked, idempotency handled by the caller). An order without operations
 * (its product had no current routing when it was created) is untouched by all of them.
 *
 * <ul>
 *   <li>{@link #snapshot}: on creation, the product's current routing revision is frozen into {@code mo_operations}
 *       (PENDING), with planned minutes for the order quantity.
 *   <li>{@link #release}: when materials are issued (order RELEASED) the first operation becomes READY.
 *   <li>{@link #pauseRunning}: pausing the order pauses its running operations.
 *   <li>{@link #cancel}: cancelling the order cancels its operations.
 *   <li>{@link #checkOutput}: output posted for the order cannot exceed the last operation's good quantity, and the
 *       final output needs every operation done.
 * </ul>
 */
@Component
class OperationPlanner {
    private final Store s;

    OperationPlanner(Store s) {
        this.s = s;
    }

    /**
     * Freezes the product's current routing into PENDING operations of a new order; returns {@code {id, number,
     * revision, operations}} of the routing, or null when the product has no current routing. Planned run minutes =
     * order quantity x run minutes per base unit (6 dp, half up); setup minutes once per order. The order's work center
     * runs every operation where it is the routing's work center or one of its alternates; other operations keep the
     * routing's work center.
     */
    Map<String, Object> snapshot(Actor a, String orderId, String productId, BigDecimal quantity, String orderMachine) {
        var current = s.list(
                "SELECT r.id,r.number,r.revision FROM routing_defaults d JOIN routings r ON r.tenant_id=d.tenant_id AND r.id=d.routing_id WHERE d.tenant_id=? AND d.product_id=? AND r.status='APPROVED'",
                a.tenant(),
                productId);
        if (current.isEmpty()) return null;
        var routing = current.getFirst();
        var alternateOf = new HashSet<String>();
        for (var x : s.list(
                "SELECT x.operation_id FROM routing_operation_alternates x JOIN routing_operations o ON o.tenant_id=x.tenant_id AND o.id=x.operation_id WHERE x.tenant_id=? AND o.routing_id=? AND x.machine_id=?",
                a.tenant(),
                routing.get("id"),
                orderMachine)) alternateOf.add(str(x, "operation_id"));
        int count = 0;
        for (var op : s.list(
                "SELECT * FROM routing_operations WHERE tenant_id=? AND routing_id=? ORDER BY seq",
                a.tenant(),
                routing.get("id"))) {
            boolean orderRuns = orderMachine.equals(op.get("machine_id")) || alternateOf.contains(str(op, "id"));
            String machine = orderRuns ? orderMachine : str(op, "machine_id");
            BigDecimal run =
                    quantity.multiply(dec(op.get("run_minutes_per_unit"))).setScale(6, RoundingMode.HALF_UP);
            s.db().update(
                            "INSERT INTO mo_operations(id,tenant_id,order_id,routing_operation_id,seq,name,machine_id,planned_machine_id,planned_quantity,setup_minutes,run_minutes_per_unit,planned_run_minutes,crew_size,skill_id,skill_level,inspection_point,instructions) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            id(),
                            a.tenant(),
                            orderId,
                            op.get("id"),
                            op.get("seq"),
                            op.get("name"),
                            machine,
                            machine,
                            quantity,
                            op.get("setup_minutes"),
                            op.get("run_minutes_per_unit"),
                            run,
                            op.get("crew_size"),
                            op.get("skill_id"),
                            op.get("skill_level"),
                            op.get("inspection_point"),
                            op.get("instructions"));
            count++;
        }
        s.db().update(
                        "UPDATE manufacturing_orders SET routing_id=?,routing_revision=? WHERE tenant_id=? AND id=?",
                        routing.get("id"),
                        routing.get("revision"),
                        a.tenant(),
                        orderId);
        var out = new LinkedHashMap<String, Object>(routing);
        out.put("operations", count);
        return out;
    }

    boolean routed(String tenant, String orderId) {
        return !s.list("SELECT 1 FROM mo_operations WHERE tenant_id=? AND order_id=? LIMIT 1", tenant, orderId)
                .isEmpty();
    }

    /** The order was released (materials issued): its first pending operation becomes READY. */
    void release(String tenant, String orderId) {
        s.db().update("""
                        UPDATE mo_operations SET status='READY',ready_at=now(),updated_at=now()
                        WHERE tenant_id=? AND order_id=? AND status='PENDING'
                          AND seq=(SELECT MIN(seq) FROM mo_operations WHERE tenant_id=? AND order_id=? AND status<>'CANCELLED')""", tenant, orderId, tenant, orderId);
    }

    /** The order was put on hold: every running operation pauses (its time log closes) and frees its work center. */
    void pauseRunning(Actor a, Map<String, Object> order) {
        for (var op : s.list(
                "SELECT op.*,m.code AS machine_code FROM mo_operations op JOIN machines m ON m.tenant_id=op.tenant_id AND m.id=op.machine_id WHERE op.tenant_id=? AND op.order_id=? AND op.status='IN_PROGRESS' ORDER BY op.seq",
                a.tenant(),
                order.get("id"))) {
            closeLog(a.tenant(), str(op, "id"), "ORDER_PAUSED");
            s.db().update(
                            "UPDATE mo_operations SET status='PAUSED',activity=NULL,updated_at=now() WHERE tenant_id=? AND id=?",
                            a.tenant(),
                            op.get("id"));
            idleIfFree(a.tenant(), str(op, "machine_id"));
            s.audit(
                    a,
                    "OP_PAUSED",
                    str(order, "number"),
                    "Op " + op.get("seq") + " " + str(op, "name") + " on " + str(op, "machine_code")
                            + ": order put on hold");
        }
    }

    /** The order was cancelled (before material issue): its operations are cancelled. */
    void cancel(String tenant, String orderId) {
        s.db().update(
                        "UPDATE mo_operations SET status='CANCELLED',updated_at=now() WHERE tenant_id=? AND order_id=? AND status IN ('PENDING','READY')",
                        tenant,
                        orderId);
    }

    /**
     * Output posted for a routed order: the order's cumulative good output cannot exceed the good quantity reported by
     * its last operation, and the final output (everything remaining) needs every operation done.
     */
    void checkOutput(String tenant, Map<String, Object> order, BigDecimal good, boolean last) {
        var ops = s.list(
                "SELECT seq,name,status,good_qty FROM mo_operations WHERE tenant_id=? AND order_id=? AND status<>'CANCELLED' ORDER BY seq",
                tenant,
                order.get("id"));
        if (ops.isEmpty()) return;
        var lastOp = ops.getLast();
        require(
                dec(order.get("good_qty")).add(good).compareTo(dec(lastOp.get("good_qty"))) <= 0,
                "Good output cannot exceed the "
                        + dec(lastOp.get("good_qty")).stripTrailingZeros().toPlainString()
                        + " good units reported by the last operation (" + lastOp.get("seq") + " "
                        + str(lastOp, "name") + ")");
        if (last) {
            var open = ops.stream()
                    .filter(o -> !"DONE".equals(o.get("status")))
                    .map(o -> String.valueOf(o.get("seq")))
                    .toList();
            require(
                    open.isEmpty(),
                    "Finish every operation before posting the final output (open: " + String.join(", ", open) + ")");
        }
    }

    /** Closes the open time log of an operation, if any. */
    void closeLog(String tenant, String operationId, String reason) {
        s.db().update(
                        "UPDATE mo_operation_time_logs SET ended_at=now(),end_reason=? WHERE tenant_id=? AND operation_id=? AND ended_at IS NULL",
                        reason,
                        tenant,
                        operationId);
    }

    /**
     * A RUNNING work center becomes IDLE when nothing runs there any more: no running operation and no running order
     * without operations. MAINTENANCE (owned by the maintenance workflow) is never changed here.
     */
    void idleIfFree(String tenant, String machineId) {
        s.db().update("""
                        UPDATE machines SET status='IDLE'
                        WHERE tenant_id=? AND id=? AND status='RUNNING'
                          AND NOT EXISTS (SELECT 1 FROM mo_operations op
                                          WHERE op.tenant_id=machines.tenant_id AND op.machine_id=machines.id AND op.status='IN_PROGRESS')
                          AND NOT EXISTS (SELECT 1 FROM manufacturing_orders o
                                          WHERE o.tenant_id=machines.tenant_id AND o.machine_id=machines.id
                                            AND o.status IN ('IN_PROGRESS','PARTIALLY_COMPLETED')
                                            AND NOT EXISTS (SELECT 1 FROM mo_operations x WHERE x.tenant_id=o.tenant_id AND x.order_id=o.id))""", tenant, machineId);
    }

    /** An IDLE work center becomes RUNNING (MAINTENANCE is never overridden). */
    void running(String tenant, String machineId) {
        s.db().update(
                        "UPDATE machines SET status='RUNNING' WHERE tenant_id=? AND id=? AND status='IDLE'",
                        tenant,
                        machineId);
    }
}
