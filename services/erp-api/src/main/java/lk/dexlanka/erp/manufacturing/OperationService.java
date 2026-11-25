package lk.dexlanka.erp.manufacturing;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.routing.SkillRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shop-floor execution of manufacturing-order operations (V10). OPERATOR and PLANNER (and ADMIN) run them:
 *
 * <ul>
 *   <li><b>Sequence</b>: strictly sequential. Issuing materials makes the first operation READY; finishing operation N
 *       makes the next one READY. No overlap.
 *   <li><b>Start</b> (READY → IN_PROGRESS) on the planned work center or an alternate of the routing operation; refused
 *       while the work center is under MAINTENANCE or already runs another operation (one open time log per work
 *       center). Starting the first operation of a RELEASED order starts the order through the existing lifecycle
 *       ({@link ManufacturingService#apply}: IN_PROGRESS, {@code started_at}, MO_START). The order must not be on hold.
 *   <li><b>Skills</b>: an operation that requires a skill starts or resumes only for a user holding it at the required
 *       level with an unexpired certification ({@link SkillRules}). PLANNER/ADMIN may override with a reason, recorded
 *       on the time log and audited (OP_SKILL_OVERRIDDEN); an OPERATOR cannot.
 *   <li><b>Time</b>: every uninterrupted SETUP or RUN interval is a time log row (start/end, work center, user, crew).
 *       Pause and finish close it; "setup done" closes the SETUP interval and opens a RUN interval.
 *   <li><b>Quantities</b>: good/scrap reported per operation (any number of reports, history kept); cumulative good +
 *       scrap of an operation cannot exceed its input: the order quantity for the first operation, the previous
 *       operation's good quantity for the others. Stock and costs still post only through the order's output command.
 *   <li><b>Work center status</b>: start/resume sets an IDLE work center RUNNING; pause/finish sets it IDLE when nothing
 *       else runs there. MAINTENANCE is only read, never changed.
 * </ul>
 */
@Service
public class OperationService {
    private static final Set<String> RUNNABLE_ORDER = Set.of("RELEASED", "IN_PROGRESS", "PARTIALLY_COMPLETED");

    private final Store s;
    private final ManufacturingService mo;
    private final OperationPlanner planner;
    private final SkillRules skills;

    OperationService(Store s, ManufacturingService mo, OperationPlanner planner, SkillRules skills) {
        this.s = s;
        this.mo = mo;
        this.planner = planner;
        this.skills = skills;
    }

    @Transactional
    public Map<String, Object> start(Actor a, String id, OperationCommands.Start c, String key) {
        a.allow("OPERATOR", "PLANNER");
        return s.once(a, key, "op-start:" + id, c, () -> {
            var op = operation(a, id);
            var order = mo.order(a, str(op, "order_id"));
            require(
                    "READY".equals(op.get("status")),
                    switch (str(op, "status")) {
                        case "PENDING" ->
                            RUNNABLE_ORDER.contains(str(order, "status")) || "ON_HOLD".equals(order.get("status"))
                                    ? "Finish the previous operation before starting operation " + op.get("seq")
                                    : "Issue the materials of " + str(order, "number")
                                            + " before starting its operations";
                        case "IN_PROGRESS" -> "This operation is already running";
                        case "PAUSED" -> "This operation is paused; resume it instead";
                        default -> "This operation is " + str(op, "status").toLowerCase() + " and cannot start";
                    });
            runnable(order);
            String machineId = blank(c.machineId()) ? str(op, "machine_id") : c.machineId();
            require(
                    allowedMachines(a, op).contains(machineId),
                    "Operation " + op.get("seq") + " can run only on its planned work center or a listed alternate");
            var machine = freeMachine(a, machineId);
            String override = skill(a, op, c.overrideReason());
            if ("RELEASED".equals(order.get("status"))) mo.apply(a, str(order, "id"), "start");
            String activity = blank(c.activity())
                    ? (dec(op.get("setup_minutes")).signum() > 0 && !logged(a, id, "SETUP") ? "SETUP" : "RUN")
                    : c.activity();
            s.db().update(
                            "UPDATE mo_operations SET status='IN_PROGRESS',activity=?,machine_id=?,operator_id=?,started_at=COALESCE(started_at,now()),updated_at=now() WHERE tenant_id=? AND id=?",
                            activity,
                            machineId,
                            a.id(),
                            a.tenant(),
                            id);
            openLog(a, op, machineId, activity, override);
            planner.running(a.tenant(), machineId);
            s.audit(
                    a,
                    "OP_STARTED",
                    str(order, "number"),
                    label(op) + " on " + str(machine, "code") + ": " + activity.toLowerCase() + " started");
            if (override != null) overridden(a, op, order, override);
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> pause(Actor a, String id, OperationCommands.Pause c, String key) {
        a.allow("OPERATOR", "PLANNER");
        return s.once(a, key, "op-pause:" + id, c, () -> {
            var op = operation(a, id);
            require("IN_PROGRESS".equals(op.get("status")), "Only a running operation can be paused");
            var order = mo.order(a, str(op, "order_id"));
            planner.closeLog(a.tenant(), id, "PAUSED");
            s.db().update(
                            "UPDATE mo_operations SET status='PAUSED',activity=NULL,updated_at=now() WHERE tenant_id=? AND id=?",
                            a.tenant(),
                            id);
            planner.idleIfFree(a.tenant(), str(op, "machine_id"));
            s.audit(
                    a,
                    "OP_PAUSED",
                    str(order, "number"),
                    label(op) + " on " + machineCode(a, op)
                            + (blank(c.reason()) ? "" : ": " + c.reason().strip()));
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> resume(Actor a, String id, OperationCommands.Resume c, String key) {
        a.allow("OPERATOR", "PLANNER");
        return s.once(a, key, "op-resume:" + id, c, () -> {
            var op = operation(a, id);
            require("PAUSED".equals(op.get("status")), "Only a paused operation can be resumed");
            var order = mo.order(a, str(op, "order_id"));
            require(
                    !"RELEASED".equals(order.get("status")),
                    "Order " + str(order, "number") + " has not started; start the operation instead");
            runnable(order);
            String machineId = str(op, "machine_id");
            var machine = freeMachine(a, machineId);
            String override = skill(a, op, c.overrideReason());
            String activity = blank(c.activity()) ? lastActivity(a, id) : c.activity();
            s.db().update(
                            "UPDATE mo_operations SET status='IN_PROGRESS',activity=?,operator_id=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            activity,
                            a.id(),
                            a.tenant(),
                            id);
            openLog(a, op, machineId, activity, override);
            planner.running(a.tenant(), machineId);
            s.audit(
                    a,
                    "OP_RESUMED",
                    str(order, "number"),
                    label(op) + " on " + str(machine, "code") + ": " + activity.toLowerCase() + " resumed");
            if (override != null) overridden(a, op, order, override);
            return result(a, id);
        });
    }

    /** Setup finished: the SETUP time log closes and a RUN time log opens on the same work center. */
    @Transactional
    public Map<String, Object> setupDone(Actor a, String id, String key) {
        a.allow("OPERATOR", "PLANNER");
        return s.once(a, key, "op-setup-done:" + id, Map.of(), () -> {
            var op = operation(a, id);
            require(
                    "IN_PROGRESS".equals(op.get("status")) && "SETUP".equals(op.get("activity")),
                    "Only an operation in setup can switch to run");
            var order = mo.order(a, str(op, "order_id"));
            var open = s.one(
                    "SELECT skill_override_reason FROM mo_operation_time_logs WHERE tenant_id=? AND operation_id=? AND ended_at IS NULL",
                    a.tenant(),
                    id);
            planner.closeLog(a.tenant(), id, "SWITCHED");
            s.db().update(
                            "UPDATE mo_operations SET activity='RUN',updated_at=now() WHERE tenant_id=? AND id=?",
                            a.tenant(),
                            id);
            openLog(a, op, str(op, "machine_id"), "RUN", (String) open.get("skill_override_reason"));
            s.audit(
                    a,
                    "OP_SETUP_DONE",
                    str(order, "number"),
                    label(op) + " on " + machineCode(a, op) + ": setup done, run started");
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> report(Actor a, String id, OperationCommands.Quantities c, String key) {
        a.allow("OPERATOR", "PLANNER");
        return s.once(a, key, "op-report:" + id, c, () -> {
            var op = operation(a, id);
            require(
                    Set.of("IN_PROGRESS", "PAUSED").contains(str(op, "status")),
                    "Report quantities on a running or paused operation");
            var order = mo.order(a, str(op, "order_id"));
            require(c.goodQty().add(c.scrapQty()).signum() > 0, "Report a good or scrap quantity");
            record(a, op, order, c);
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> finish(Actor a, String id, OperationCommands.Quantities c, String key) {
        a.allow("OPERATOR", "PLANNER");
        return s.once(a, key, "op-finish:" + id, c, () -> {
            var op = operation(a, id);
            require(
                    Set.of("IN_PROGRESS", "PAUSED").contains(str(op, "status")),
                    "Only a running or paused operation can be finished");
            var order = mo.order(a, str(op, "order_id"));
            if (c.goodQty().add(c.scrapQty()).signum() > 0) op = record(a, op, order, c);
            require(
                    dec(op.get("good_qty")).add(dec(op.get("scrap_qty"))).signum() > 0,
                    "Report the good and scrap quantity of this operation before finishing it");
            planner.closeLog(a.tenant(), id, "FINISHED");
            s.db().update(
                            "UPDATE mo_operations SET status='DONE',activity=NULL,finished_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                            a.tenant(),
                            id);
            s.db().update("""
                            UPDATE mo_operations SET status='READY',ready_at=now(),updated_at=now()
                            WHERE tenant_id=? AND order_id=? AND status='PENDING'
                              AND seq=(SELECT MIN(seq) FROM mo_operations WHERE tenant_id=? AND order_id=? AND status='PENDING')""", a.tenant(), order.get("id"), a.tenant(), order.get("id"));
            planner.idleIfFree(a.tenant(), str(op, "machine_id"));
            s.audit(
                    a,
                    "OP_FINISHED",
                    str(order, "number"),
                    label(op) + " on " + machineCode(a, op) + ": " + plain(op.get("good_qty")) + " good, "
                            + plain(op.get("scrap_qty")) + " scrap");
            return result(a, id);
        });
    }

    // ------------------------------------------------------------------ rules

    private Map<String, Object> operation(Actor a, String id) {
        return s.one("SELECT * FROM mo_operations WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    private static void runnable(Map<String, Object> order) {
        String status = str(order, "status");
        require(
                !"ON_HOLD".equals(status),
                "Order " + str(order, "number") + " is on hold; resume the order before working on it");
        require(
                RUNNABLE_ORDER.contains(status),
                "Order " + str(order, "number") + " is " + status.toLowerCase().replace('_', ' ')
                        + "; operations run only on released orders");
    }

    /** The planned work center, the routing operation's work center and its alternates. */
    private Set<String> allowedMachines(Actor a, Map<String, Object> op) {
        var out = new LinkedHashSet<String>();
        out.add(str(op, "planned_machine_id"));
        out.add(str(op, "machine_id"));
        for (var r : s.list(
                "SELECT machine_id FROM routing_operations WHERE tenant_id=? AND id=? UNION SELECT machine_id FROM routing_operation_alternates WHERE tenant_id=? AND operation_id=?",
                a.tenant(),
                op.get("routing_operation_id"),
                a.tenant(),
                op.get("routing_operation_id"))) out.add(str(r, "machine_id"));
        return out;
    }

    /** Locks the work center; refuses MAINTENANCE and a work center that already runs an operation. */
    private Map<String, Object> freeMachine(Actor a, String machineId) {
        var machine = s.one(
                "SELECT id,code,status FROM machines WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), machineId);
        require(
                !"MAINTENANCE".equals(machine.get("status")),
                "Work center " + str(machine, "code") + " is under maintenance");
        var busy = s.list("""
                SELECT o.number,op.seq,op.name FROM mo_operation_time_logs l
                JOIN mo_operations op ON op.tenant_id=l.tenant_id AND op.id=l.operation_id
                JOIN manufacturing_orders o ON o.tenant_id=op.tenant_id AND o.id=op.order_id
                WHERE l.tenant_id=? AND l.machine_id=? AND l.ended_at IS NULL""", a.tenant(), machineId);
        require(
                busy.isEmpty(),
                busy.isEmpty()
                        ? ""
                        : "Work center " + str(machine, "code") + " is running " + str(busy.getFirst(), "number")
                                + " operation " + busy.getFirst().get("seq") + " ("
                                + str(busy.getFirst(), "name") + "); pause or finish it first");
        return machine;
    }

    /**
     * The skill check; returns the override reason to record, or null when no override was needed. Refuses an
     * unqualified OPERATOR, and a PLANNER/ADMIN without a reason.
     */
    private String skill(Actor a, Map<String, Object> op, String overrideReason) {
        var check = skills.check(
                a.tenant(), a.id(), (String) op.get("skill_id"), ((Number) op.get("skill_level")).intValue());
        if (check.ok()) return null;
        boolean supervisor = a.role().equals("ADMIN") || a.role().equals("PLANNER");
        require(
                supervisor,
                check.message(a.name()) + ". Ask a qualified operator, or a planner to override with a reason");
        require(!blank(overrideReason), check.message(a.name()) + ". Enter an override reason to start anyway");
        return check.message(a.name()) + " — override: " + overrideReason.strip();
    }

    private void overridden(Actor a, Map<String, Object> op, Map<String, Object> order, String override) {
        s.audit(a, "OP_SKILL_OVERRIDDEN", str(order, "number"), label(op) + ": " + override);
    }

    private boolean logged(Actor a, String operation, String activity) {
        return !s.list(
                        "SELECT 1 FROM mo_operation_time_logs WHERE tenant_id=? AND operation_id=? AND activity=? LIMIT 1",
                        a.tenant(),
                        operation,
                        activity)
                .isEmpty();
    }

    private String lastActivity(Actor a, String operation) {
        var last = s.list(
                "SELECT activity FROM mo_operation_time_logs WHERE tenant_id=? AND operation_id=? ORDER BY started_at DESC,id DESC LIMIT 1",
                a.tenant(),
                operation);
        return last.isEmpty() ? "RUN" : str(last.getFirst(), "activity");
    }

    private void openLog(Actor a, Map<String, Object> op, String machineId, String activity, String override) {
        s.db().update(
                        "INSERT INTO mo_operation_time_logs(id,tenant_id,operation_id,order_id,machine_id,user_id,activity,crew_size,skill_override_reason) VALUES(?,?,?,?,?,?,?,?,?)",
                        id(),
                        a.tenant(),
                        op.get("id"),
                        op.get("order_id"),
                        machineId,
                        a.id(),
                        activity,
                        op.get("crew_size"),
                        override);
    }

    /**
     * Records a quantity report: whole pieces for {@code pcs} products, scrap needs notes, cumulative good + scrap
     * within the operation's input. Returns the updated operation row.
     */
    private Map<String, Object> record(
            Actor a, Map<String, Object> op, Map<String, Object> order, OperationCommands.Quantities c) {
        String uom = str(
                s.one("SELECT uom FROM items WHERE tenant_id=? AND id=?", a.tenant(), order.get("product_id")), "uom");
        s.quantity(uom, c.goodQty());
        s.quantity(uom, c.scrapQty());
        require(c.scrapQty().signum() == 0 || !blank(c.notes()), "Provide a reason for scrap");
        var previous = s.list(
                "SELECT seq,good_qty FROM mo_operations WHERE tenant_id=? AND order_id=? AND seq<? AND status<>'CANCELLED' ORDER BY seq DESC LIMIT 1",
                a.tenant(),
                op.get("order_id"),
                op.get("seq"));
        BigDecimal input = previous.isEmpty()
                ? dec(order.get("quantity"))
                : dec(previous.getFirst().get("good_qty"));
        BigDecimal total = dec(op.get("good_qty"))
                .add(dec(op.get("scrap_qty")))
                .add(c.goodQty())
                .add(c.scrapQty());
        require(
                total.compareTo(input) <= 0,
                "Operation " + op.get("seq") + " can process at most " + plain(input)
                        + (previous.isEmpty()
                                ? " (the order quantity)"
                                : " (the good output of operation "
                                        + previous.getFirst().get("seq") + ")")
                        + "; " + plain(dec(op.get("good_qty")).add(dec(op.get("scrap_qty")))) + " already reported");
        s.db().update(
                        "INSERT INTO mo_operation_reports(id,tenant_id,operation_id,order_id,machine_id,good_qty,scrap_qty,notes,reported_by) VALUES(?,?,?,?,?,?,?,?,?)",
                        id(),
                        a.tenant(),
                        op.get("id"),
                        op.get("order_id"),
                        op.get("machine_id"),
                        c.goodQty(),
                        c.scrapQty(),
                        blank(c.notes()) ? "" : c.notes().strip(),
                        a.id());
        s.db().update(
                        "UPDATE mo_operations SET good_qty=good_qty+?,scrap_qty=scrap_qty+?,updated_at=now() WHERE tenant_id=? AND id=?",
                        c.goodQty(),
                        c.scrapQty(),
                        a.tenant(),
                        op.get("id"));
        s.audit(
                a,
                "OP_REPORTED",
                str(order, "number"),
                label(op) + ": " + plain(c.goodQty()) + " good, " + plain(c.scrapQty()) + " scrap"
                        + (blank(c.notes()) ? "" : " (" + c.notes().strip() + ")"));
        return operation(a, str(op, "id"));
    }

    private String machineCode(Actor a, Map<String, Object> op) {
        return str(
                s.one("SELECT code FROM machines WHERE tenant_id=? AND id=?", a.tenant(), op.get("machine_id")),
                "code");
    }

    private Map<String, Object> result(Actor a, String id) {
        var row = s.one(
                "SELECT op.*,o.number AS order_number,o.status AS order_status,m.code AS machine_code,m.status AS machine_status FROM mo_operations op JOIN manufacturing_orders o ON o.tenant_id=op.tenant_id AND o.id=op.order_id JOIN machines m ON m.tenant_id=op.tenant_id AND m.id=op.machine_id WHERE op.tenant_id=? AND op.id=?",
                a.tenant(),
                id);
        return new LinkedHashMap<>(row);
    }

    private static String label(Map<String, Object> op) {
        return "Op " + op.get("seq") + " " + str(op, "name");
    }

    private static String plain(Object value) {
        return dec(value).stripTrailingZeros().toPlainString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
