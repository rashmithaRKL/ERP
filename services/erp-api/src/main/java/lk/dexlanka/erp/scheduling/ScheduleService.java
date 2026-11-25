package lk.dexlanka.erp.scheduling;

import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;
import static lk.dexlanka.erp.scheduling.ScheduleInputs.at;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.routing.CalendarService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Scheduling commands (V13, PLANNER; ADMIN always): run the scheduler into a DRAFT run, publish it (the previous
 * published run becomes SUPERSEDED, older drafts DISCARDED), discard a draft, pin and unpin operations (a pin is
 * validated by scheduling with it: refused unless the scheduler can honour it without displacing another pin), and
 * the remaining-duration estimate of an order without a routing. Every command follows the write-command pattern
 * (tenant lock, idempotency, rules, writes, audit in one transaction).
 */
@Service
public class ScheduleService {
    private final Store s;
    private final ScheduleInputs inputs;

    ScheduleService(Store s, ScheduleInputs inputs) {
        this.s = s;
        this.inputs = inputs;
    }

    // ------------------------------------------------------------------ runs

    @Transactional
    public Map<String, Object> run(Actor a, ScheduleCommands.Run c, String key) {
        a.allow("PLANNER");
        var params = new ScheduleInputs.Params(
                c.horizonDays() == null ? ScheduleInputs.DEFAULT_HORIZON_DAYS : c.horizonDays(),
                c.useAlternates() == null || c.useAlternates(),
                c.breakdownHours() == null ? ScheduleInputs.DEFAULT_BREAKDOWN_HOURS : c.breakdownHours(),
                c.includeDrafts() != null && c.includeDrafts());
        require(params.horizonDays() >= 1 && params.horizonDays() <= 90, "Choose a horizon of 1 to 90 days");
        require(
                params.breakdownHours() >= 1 && params.breakdownHours() <= 168,
                "Assume an open breakdown lasts 1 to 168 hours");
        return s.once(a, key, "schedule-run", c, () -> create(a, params, c.notes()));
    }

    /** The scheduler's "now": the current minute. */
    static long now() {
        return Instant.now().truncatedTo(ChronoUnit.MINUTES).getEpochSecond() / 60;
    }

    /** Computes a schedule without writing anything (tests and pin validation use it with a chosen "now"). */
    ScheduleEngine.Result compute(String tenant, ScheduleInputs.Params params, long now) {
        return ScheduleEngine.schedule(inputs.load(tenant, params, now));
    }

    /** Runs and stores a DRAFT run inside the caller's command. */
    Map<String, Object> create(Actor a, ScheduleInputs.Params params, String notes) {
        long now = now();
        var input = inputs.load(a.tenant(), params, now);
        var result = ScheduleEngine.schedule(input);
        String id = id();
        String number = s.number(a, "SCH");
        var published = s.list(
                "SELECT id FROM schedule_runs WHERE tenant_id=? AND status='PUBLISHED'", a.tenant());
        long late = result.orders().stream().filter(ScheduleEngine.OrderResult::late).count();
        long unscheduled = result.orders().stream()
                .filter(o -> o.status().equals(ScheduleEngine.NO_ROUTING)
                        || o.status().equals(ScheduleEngine.BEYOND_HORIZON))
                .count();
        long honoured = result.pins().values().stream()
                .filter(p -> p.status().equals(ScheduleEngine.HONOURED))
                .count();
        long conflicts = result.pins().size() - honoured;
        Long makespan = result.operations().stream()
                .map(ScheduleEngine.Placed::end)
                .max(Long::compare)
                .orElse(null);
        s.db().update(
                        """
                        INSERT INTO schedule_runs(id,tenant_id,number,status,horizon_start,horizon_end,horizon_days,use_alternates,breakdown_hours,
                          include_drafts,notes,base_run_id,orders_count,operations_count,late_orders,unscheduled_orders,pins_honoured,pin_conflicts,
                          makespan_end,created_by)
                        VALUES(?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                        id,
                        a.tenant(),
                        number,
                        at(input.now()),
                        at(input.horizonEnd()),
                        params.horizonDays(),
                        params.useAlternates(),
                        params.breakdownHours(),
                        params.includeDrafts(),
                        notes == null ? "" : notes.strip(),
                        published.isEmpty() ? null : published.getFirst().get("id"),
                        result.orders().size(),
                        result.operations().size(),
                        late,
                        unscheduled,
                        honoured,
                        conflicts,
                        makespan == null ? null : at(makespan));
        var orderRows = new ArrayList<Object[]>();
        for (var o : result.orders()) {
            var order = o.order();
            orderRows.add(new Object[] {
                a.tenant(),
                id,
                order.id(),
                o.sequence(),
                order.status(),
                order.priority(),
                order.dueDate(),
                at(order.dueEnd()),
                order.parentId(),
                order.routed(),
                order.estimate(),
                o.status(),
                o.start() == null ? null : at(o.start()),
                o.end() == null ? null : at(o.end()),
                o.late(),
                (int) Math.min(Integer.MAX_VALUE, o.latenessMinutes()),
                (int) Math.min(Integer.MAX_VALUE, o.remainingMinutes()),
                String.join("\n", o.warnings())
            });
        }
        s.db().batchUpdate("""
                INSERT INTO schedule_run_orders(tenant_id,run_id,order_id,sequence,order_status,priority,due_date,due_end,parent_order_id,
                  routed,estimate_minutes,status,projected_start,projected_end,late,lateness_minutes,remaining_minutes,warnings)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", orderRows);
        var opRows = new ArrayList<Object[]>();
        for (var p : result.operations()) {
            var op = p.operation();
            var segments = p.segments().stream()
                    .map(x -> Map.of("start", at(x.start()).toString(), "end", at(x.end()).toString()))
                    .toList();
            opRows.add(new Object[] {
                id(),
                a.tenant(),
                id,
                p.order().id(),
                op.id(),
                op.seq(),
                op.name(),
                op.status(),
                p.machineId(),
                op.plannedMachineId(),
                p.alternate(),
                at(p.start()),
                at(p.end()),
                (int) p.minutes(),
                op.remaining(),
                op.planned(),
                op.actual(),
                s.encode(segments),
                p.late(),
                op.running(),
                p.pin() == null ? null : p.pin().id(),
                p.pinStatus(),
                p.pinNote() == null ? "" : p.pinNote()
            });
        }
        s.db().batchUpdate("""
                INSERT INTO schedule_run_operations(id,tenant_id,run_id,order_id,operation_id,seq,name,operation_status,machine_id,
                  planned_machine_id,alternate,start_at,end_at,minutes,remaining_minutes,planned_minutes,actual_minutes,segments,late,
                  running,pin_id,pin_status,pin_note)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)""", opRows);
        var blockRows = new ArrayList<Object[]>();
        for (var b : input.blocks())
            blockRows.add(new Object[] {
                id(),
                a.tenant(),
                id,
                b.machineId(),
                b.kind(),
                b.reference(),
                b.label(),
                at(b.start()),
                at(b.end()),
                b.assumedEnd()
            });
        s.db().batchUpdate("""
                INSERT INTO schedule_run_blocks(id,tenant_id,run_id,machine_id,kind,reference,label,start_at,end_at,assumed_end)
                VALUES(?,?,?,?,?,?,?,?,?,?)""", blockRows);
        s.audit(
                a,
                "SCHEDULE_RUN_CREATED",
                number,
                result.operations().size() + " operations of " + result.orders().size() + " orders over "
                        + params.horizonDays() + " days; " + late + " late, " + unscheduled + " unscheduled"
                        + (result.pins().isEmpty() ? "" : "; " + honoured + " of " + result.pins().size() + " pins honoured"));
        var out = new LinkedHashMap<String, Object>();
        out.put("id", id);
        out.put("number", number);
        out.put("status", "DRAFT");
        out.put("orders", result.orders().size());
        out.put("operations", result.operations().size());
        out.put("lateOrders", late);
        out.put("unscheduledOrders", unscheduled);
        out.put("pinsHonoured", honoured);
        out.put("pinConflicts", conflicts);
        return out;
    }

    @Transactional
    public Map<String, Object> publish(Actor a, String id, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "schedule-publish:" + id, Map.of(), () -> {
            var run = s.one("SELECT * FROM schedule_runs WHERE tenant_id=? AND id=?", a.tenant(), id);
            require(run.get("status").equals("DRAFT"), "Only a draft schedule can be published");
            require(
                    s.list(
                                    """
                                    SELECT 1 FROM schedule_runs r WHERE r.tenant_id=? AND r.status IN ('DRAFT','PUBLISHED','SUPERSEDED') AND r.id<>?
                                      AND (r.created_at,r.id) > (?::timestamptz,?)""",
                                    a.tenant(),
                                    id,
                                    run.get("created_at"),
                                    id)
                            .isEmpty(),
                    "A newer schedule run exists; publish the newest run or run the scheduler again");
            var previous = s.list(
                    "SELECT id,number FROM schedule_runs WHERE tenant_id=? AND status='PUBLISHED'", a.tenant());
            s.db().update(
                            "UPDATE schedule_runs SET status='SUPERSEDED',superseded_at=now(),superseded_by_run_id=? WHERE tenant_id=? AND status='PUBLISHED'",
                            id,
                            a.tenant());
            s.db().update(
                            "UPDATE schedule_runs SET status='DISCARDED',discarded_at=now(),discarded_by=? WHERE tenant_id=? AND status='DRAFT' AND id<>?",
                            a.id(),
                            a.tenant(),
                            id);
            s.db().update(
                            "UPDATE schedule_runs SET status='PUBLISHED',published_at=now(),published_by=? WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "SCHEDULE_PUBLISHED",
                    str(run, "number"),
                    run.get("late_orders") + " late orders"
                            + (previous.isEmpty() ? "" : "; supersedes " + str(previous.getFirst(), "number")));
            return runResult(a, id);
        });
    }

    @Transactional
    public Map<String, Object> discard(Actor a, String id, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "schedule-discard:" + id, Map.of(), () -> {
            var run = s.one("SELECT * FROM schedule_runs WHERE tenant_id=? AND id=?", a.tenant(), id);
            require(run.get("status").equals("DRAFT"), "Only a draft schedule can be discarded");
            s.db().update(
                            "UPDATE schedule_runs SET status='DISCARDED',discarded_at=now(),discarded_by=? WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(a, "SCHEDULE_DISCARDED", str(run, "number"), "Draft schedule discarded");
            return runResult(a, id);
        });
    }

    private Map<String, Object> runResult(Actor a, String id) {
        return new LinkedHashMap<>(s.one(
                "SELECT id,number,status,published_at,late_orders,unscheduled_orders FROM schedule_runs WHERE tenant_id=? AND id=?",
                a.tenant(),
                id));
    }

    /** The parameters of the newest run (pins are validated and re-runs made with them), else the defaults. */
    ScheduleInputs.Params latestParams(String tenant) {
        var last = s.list(
                "SELECT horizon_days,use_alternates,breakdown_hours,include_drafts FROM schedule_runs WHERE tenant_id=? AND status<>'DISCARDED' ORDER BY created_at DESC,id DESC LIMIT 1",
                tenant);
        if (last.isEmpty()) return ScheduleInputs.Params.defaults();
        var r = last.getFirst();
        return new ScheduleInputs.Params(
                ((Number) r.get("horizon_days")).intValue(),
                Boolean.TRUE.equals(r.get("use_alternates")),
                ((Number) r.get("breakdown_hours")).intValue(),
                Boolean.TRUE.equals(r.get("include_drafts")));
    }

    // ------------------------------------------------------------------ pins

    @Transactional
    public Map<String, Object> pin(Actor a, ScheduleCommands.Pin c, String key) {
        a.allow("PLANNER");
        String target = c.operationId() == null || c.operationId().isBlank() ? "order:" + c.orderId() : c.operationId();
        return s.once(a, key, "schedule-pin:" + target, c, () -> {
            String operationId = blank(c.operationId()) ? null : c.operationId();
            String orderId;
            String label;
            if (operationId != null) {
                var op = s.one(
                        "SELECT op.*,o.number FROM mo_operations op JOIN manufacturing_orders o ON o.tenant_id=op.tenant_id AND o.id=op.order_id WHERE op.tenant_id=? AND op.id=?",
                        a.tenant(),
                        operationId);
                orderId = str(op, "order_id");
                require(
                        blank(c.orderId()) || c.orderId().equals(orderId),
                        "The operation belongs to another manufacturing order");
                require(
                        op.get("status").equals("PENDING") || op.get("status").equals("READY"),
                        "Only an operation that has not started can be pinned");
                label = str(op, "number") + " operation " + op.get("seq");
            } else {
                require(!blank(c.orderId()), "Choose the operation or the order to pin");
                var order = s.one(
                        "SELECT o.*,e.minutes AS estimate,EXISTS (SELECT 1 FROM mo_operations x WHERE x.tenant_id=o.tenant_id AND x.order_id=o.id) AS routed FROM manufacturing_orders o LEFT JOIN schedule_estimates e ON e.tenant_id=o.tenant_id AND e.order_id=o.id WHERE o.tenant_id=? AND o.id=?",
                        a.tenant(),
                        c.orderId());
                require(!Boolean.TRUE.equals(order.get("routed")), "Pin one of the order's operations");
                require(order.get("estimate") != null, "Enter a duration estimate before pinning this order");
                require(
                        !List.of("IN_PROGRESS", "PARTIALLY_COMPLETED").contains(str(order, "status")),
                        "Only an order that has not started can be pinned");
                orderId = c.orderId();
                label = str(order, "number");
            }
            var machine = s.one("SELECT id,code FROM machines WHERE tenant_id=? AND id=?", a.tenant(), c.machineId());
            long requested = parseStart(c.start());
            long now = now();
            require(requested >= now, "Pin a start in the future");
            var params = latestParams(a.tenant());
            var input = inputs.load(a.tenant(), params, now);
            require(requested < input.horizonEnd(), "Pin a start within the " + params.horizonDays() + "-day horizon");
            var order = input.orders().stream()
                    .filter(o -> o.id().equals(orderId))
                    .findFirst()
                    .orElse(null);
            require(order != null, "This manufacturing order is not open for scheduling");
            var op = order.operations().stream()
                    .filter(o -> operationId == null ? o.id() == null : operationId.equals(o.id()))
                    .findFirst()
                    .orElse(null);
            require(op != null, "This operation is not open for scheduling");
            require(
                    op.candidates().contains(c.machineId()),
                    "This operation cannot run on " + str(machine, "code")
                            + "; choose its work center or one of its alternates");
            // The start moves forward to the next free working minute of the work center (calendar minus blocks).
            var timeline = new ScheduleEngine.Timeline(
                    input.machines().stream()
                            .filter(m -> m.id().equals(c.machineId()))
                            .map(m -> new ScheduleEngine.Machine(
                                    m.id(), m.code(), ScheduleEngine.clip(m.working(), now, input.horizonEnd())))
                            .findFirst()
                            .orElseThrow(),
                    input.blocks().stream()
                            .filter(b -> b.machineId().equals(c.machineId()))
                            .toList());
            Long start = timeline.nextWorking(requested);
            require(
                    start != null,
                    str(machine, "code") + " has no working time after that start within the horizon");
            String pinKey = ScheduleEngine.key(orderId, operationId);
            var others = input.pins().stream().filter(p -> !p.key().equals(pinKey)).toList();
            var baseline = ScheduleEngine.schedule(withPins(input, others));
            var candidate = new ScheduleEngine.Pin("candidate", orderId, operationId, c.machineId(), start);
            var withPin = new ArrayList<>(others);
            withPin.add(candidate);
            var preview = ScheduleEngine.schedule(withPins(input, withPin));
            var outcome = preview.pins().get("candidate");
            require(
                    outcome != null && outcome.status().equals(ScheduleEngine.HONOURED),
                    "This pin cannot be honoured: " + (outcome == null ? "the operation is not open" : outcome.note()));
            for (var other : others) {
                var before = baseline.pins().get(other.id());
                var after = preview.pins().get(other.id());
                require(
                        before == null
                                || !before.status().equals(ScheduleEngine.HONOURED)
                                || after.status().equals(ScheduleEngine.HONOURED),
                        "This pin would displace another pinned operation (" + after.note() + ")");
            }
            s.db().update(
                            "UPDATE schedule_pins SET removed_at=now(),removed_by=? WHERE tenant_id=? AND order_id=? AND operation_id IS NOT DISTINCT FROM ?::text AND removed_at IS NULL",
                            a.id(),
                            a.tenant(),
                            orderId,
                            operationId);
            String id = id();
            s.db().update(
                            "INSERT INTO schedule_pins(id,tenant_id,order_id,operation_id,machine_id,start_at,reason,created_by) VALUES(?,?,?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            orderId,
                            operationId,
                            c.machineId(),
                            at(start),
                            c.reason() == null ? "" : c.reason().strip(),
                            a.id());
            s.audit(
                    a,
                    "SCHEDULE_PINNED",
                    label,
                    "Pinned to " + str(machine, "code") + " at " + ScheduleEngine.local(start)
                            + (blank(c.reason()) ? "" : " · " + c.reason().strip()));
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("orderId", orderId);
            out.put("operationId", operationId);
            out.put("machineId", c.machineId());
            out.put("start", at(start).toString());
            out.put("moved", start != requested);
            out.put("run", c.rerun() ? create(a, params, "Re-run after pinning " + label) : null);
            return out;
        });
    }

    @Transactional
    public Map<String, Object> unpin(Actor a, String id, ScheduleCommands.Unpin c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "schedule-unpin:" + id, c, () -> {
            var pin = s.one(
                    "SELECT p.*,o.number,op.seq,m.code FROM schedule_pins p JOIN manufacturing_orders o ON o.tenant_id=p.tenant_id AND o.id=p.order_id LEFT JOIN mo_operations op ON op.tenant_id=p.tenant_id AND op.id=p.operation_id JOIN machines m ON m.tenant_id=p.tenant_id AND m.id=p.machine_id WHERE p.tenant_id=? AND p.id=?",
                    a.tenant(),
                    id);
            require(pin.get("removed_at") == null, "This pin was already removed");
            s.db().update(
                            "UPDATE schedule_pins SET removed_at=now(),removed_by=? WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            String label = str(pin, "number") + (pin.get("seq") == null ? "" : " operation " + pin.get("seq"));
            s.audit(a, "SCHEDULE_UNPINNED", label, "Pin on " + str(pin, "code") + " removed");
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("removed", true);
            out.put("run", c.rerun() ? create(a, latestParams(a.tenant()), "Re-run after removing a pin") : null);
            return out;
        });
    }

    private static ScheduleEngine.Input withPins(ScheduleEngine.Input in, List<ScheduleEngine.Pin> pins) {
        return new ScheduleEngine.Input(
                in.now(), in.horizonEnd(), in.useAlternates(), in.machines(), in.blocks(), in.orders(), pins);
    }

    /** {@code yyyy-MM-ddTHH:mm[:ss]} in Asia/Colombo, or an ISO timestamp with offset; whole minutes. */
    static long parseStart(String text) {
        try {
            Instant instant = text.matches(".*([zZ]|[+-]\\d{2}:?\\d{2})$")
                    ? OffsetDateTime.parse(text).toInstant()
                    : LocalDateTime.parse(text).atZone(CalendarService.ZONE).toInstant();
            return Math.floorDiv(instant.getEpochSecond(), 60);
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a valid start date and time");
        }
    }

    // ------------------------------------------------------------------ estimates

    @Transactional
    public Map<String, Object> estimate(Actor a, ScheduleCommands.Estimate c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "schedule-estimate:" + c.orderId(), c, () -> {
            var order = s.one(
                    "SELECT o.*,EXISTS (SELECT 1 FROM mo_operations x WHERE x.tenant_id=o.tenant_id AND x.order_id=o.id) AS routed FROM manufacturing_orders o WHERE o.tenant_id=? AND o.id=?",
                    a.tenant(),
                    c.orderId());
            require(
                    !Boolean.TRUE.equals(order.get("routed")),
                    "This order has a routing; its operations are scheduled from the routing");
            require(
                    !List.of("QUALITY_REVIEW", "COMPLETED", "CLOSED", "CANCELLED").contains(str(order, "status")),
                    "This manufacturing order is no longer open for scheduling");
            s.db().update(
                            """
                            INSERT INTO schedule_estimates(tenant_id,order_id,minutes,notes,updated_by) VALUES(?,?,?,?,?)
                            ON CONFLICT (tenant_id,order_id) DO UPDATE SET minutes=EXCLUDED.minutes,notes=EXCLUDED.notes,
                              updated_by=EXCLUDED.updated_by,updated_at=now()""",
                            a.tenant(),
                            c.orderId(),
                            c.minutes(),
                            c.notes() == null ? "" : c.notes().strip(),
                            a.id());
            s.audit(
                    a,
                    "SCHEDULE_ESTIMATE_SET",
                    str(order, "number"),
                    "Remaining duration estimate " + c.minutes().stripTrailingZeros().toPlainString() + " min on "
                            + str(
                                    s.one(
                                            "SELECT code FROM machines WHERE tenant_id=? AND id=?",
                                            a.tenant(),
                                            order.get("machine_id")),
                                    "code"));
            var out = new LinkedHashMap<String, Object>();
            out.put("orderId", c.orderId());
            out.put("minutes", c.minutes());
            return out;
        });
    }

    @Transactional
    public Map<String, Object> removeEstimate(Actor a, String orderId, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "schedule-estimate-remove:" + orderId, Map.of(), () -> {
            var order = s.one(
                    "SELECT id,number FROM manufacturing_orders WHERE tenant_id=? AND id=?", a.tenant(), orderId);
            require(
                    s.db().update("DELETE FROM schedule_estimates WHERE tenant_id=? AND order_id=?", a.tenant(), orderId)
                            > 0,
                    "This order has no duration estimate");
            s.db().update(
                            "UPDATE schedule_pins SET removed_at=now(),removed_by=? WHERE tenant_id=? AND order_id=? AND operation_id IS NULL AND removed_at IS NULL",
                            a.id(),
                            a.tenant(),
                            orderId);
            s.audit(a, "SCHEDULE_ESTIMATE_REMOVED", str(order, "number"), "Duration estimate removed");
            return Map.of("orderId", orderId, "removed", true);
        });
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
