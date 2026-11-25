package lk.dexlanka.erp.maintenance;

import static lk.dexlanka.erp.maintenance.DowntimeService.blankToNull;
import static lk.dexlanka.erp.maintenance.MaintenanceRules.reasonLabel;
import static lk.dexlanka.erp.maintenance.MaintenanceRules.requireNotFuture;
import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintenance work orders ({@code MWO-…}).
 *
 * <p>Lifecycle: {@code REQUESTED} → {@code APPROVED} (or directly {@code SCHEDULED} when approved with a window) →
 * {@code SCHEDULED} → {@code IN_PROGRESS} → {@code COMPLETED}; {@code CANCELLED} before work starts. Corrective orders
 * are requested by operators, planners or technicians (optionally together with an open BREAKDOWN downtime event) and
 * approved by a different PLANNER/ADMIN (self-approval is refused, also for ADMIN). Preventive orders are generated from
 * plans by {@link PlanService#generate} already approved and scheduled. MAINTENANCE technicians start, record, log
 * labour on and complete the work.
 *
 * <p>Starting work refuses while a manufacturing order is {@code IN_PROGRESS}/{@code PARTIALLY_COMPLETED} on the machine
 * (a planner pauses it first), sets the machine to {@code MAINTENANCE} and makes sure an open downtime event covers the
 * work: the order's linked event, an unlinked open BREAKDOWN (adopted), or a new one (any other open event is closed at
 * the start). The new event is PLANNED_MAINTENANCE (planned) for preventive and scheduled corrective work and BREAKDOWN
 * (unplanned) for unscheduled corrective work. Completing returns the machine to {@code IDLE}, closes the order's open
 * downtime and, for preventive work, reschedules the plan from the completion date.
 */
@Service
public class WorkOrderService {
    static final String[] REQUESTERS = {"OPERATOR", "PLANNER", "MAINTENANCE"};

    private final Store s;
    private final MaintenanceRules rules;

    WorkOrderService(Store s, MaintenanceRules rules) {
        this.s = s;
        this.rules = rules;
    }

    // ------------------------------------------------------------------ request, approve, schedule

    @Transactional
    public Map<String, Object> request(Actor a, MaintenanceCommands.RequestWorkOrder c, String key) {
        a.allow(REQUESTERS);
        return s.once(a, key, "maintenance-request", c, () -> {
            var machine = rules.machine(a, c.machineId());
            String linked = blankToNull(c.downtimeEventId());
            require(
                    !(c.breakdown() && linked != null),
                    "Either report a new breakdown or link the downtime already reported, not both");
            String id = id();
            String number = rules.number(a, "maintenance_work_orders", "MWO");
            String priority =
                    c.priority() == null || c.priority().isBlank() ? (c.breakdown() ? "HIGH" : "NORMAL") : c.priority();
            s.db().update(
                            """
                            INSERT INTO maintenance_work_orders(id,tenant_id,number,type,machine_id,title,description,priority,status,requested_by)
                            VALUES(?,?,?,'CORRECTIVE',?,?,?,?,'REQUESTED',?)""",
                            id,
                            a.tenant(),
                            number,
                            machine.get("id"),
                            c.title().strip(),
                            c.description() == null ? "" : c.description().strip(),
                            priority,
                            a.id());
            String downtime = null;
            if (linked != null) {
                var event = rules.downtime(a, linked);
                require(event.get("machine_id").equals(machine.get("id")), "That downtime belongs to another machine");
                require(event.get("status").equals("OPEN"), "Only open downtime can be linked to a new work order");
                if (event.get("work_order_id") != null) {
                    var other = s.one(
                            "SELECT number,status FROM maintenance_work_orders WHERE tenant_id=? AND id=?",
                            a.tenant(),
                            event.get("work_order_id"));
                    require(
                            other.get("status").equals("CANCELLED"),
                            "Downtime " + event.get("number") + " is already linked to " + other.get("number"));
                }
                s.db().update(
                                "UPDATE downtime_events SET work_order_id=? WHERE tenant_id=? AND id=?",
                                id,
                                a.tenant(),
                                linked);
                downtime = str(event, "number");
            } else if (c.breakdown()) {
                var open = rules.openDowntime(a, str(machine, "id"));
                require(
                        open == null,
                        machine.get("code") + " already has open downtime " + open(open)
                                + "; link it to the request instead");
                OffsetDateTime start = MaintenanceRules.timeOrNow(c.downtimeStartedAt(), "breakdown start");
                requireNotFuture(start, "The breakdown start");
                String order = blankToNull(c.manufacturingOrderId());
                if (order != null) {
                    var mo = s.one(
                            "SELECT number,machine_id FROM manufacturing_orders WHERE tenant_id=? AND id=?",
                            a.tenant(),
                            order);
                    require(
                            mo.get("machine_id").equals(machine.get("id")),
                            mo.get("number") + " runs on another machine");
                }
                var event = rules.insertDowntime(
                        a,
                        machine,
                        "BREAKDOWN",
                        false,
                        start,
                        null,
                        order,
                        id,
                        "Reported with " + number,
                        null,
                        null,
                        null);
                downtime = str(event, "number");
                s.audit(a, "DOWNTIME_REPORTED", downtime, machine.get("code") + " · Breakdown (unplanned) · open");
            }
            s.audit(
                    a,
                    "MWO_REQUESTED",
                    number,
                    machine.get("code") + " · corrective · " + c.title().strip()
                            + (downtime == null ? "" : " · downtime " + downtime));
            return result(a, id);
        });
    }

    /** PLANNER/ADMIN other than the requester. With both window ends the order becomes SCHEDULED. */
    @Transactional
    public Map<String, Object> approve(Actor a, String id, MaintenanceCommands.Approve c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "approve-mwo:" + id, c, () -> {
            var wo = rules.workOrder(a, id);
            require(wo.get("status").equals("REQUESTED"), "Only requested work orders can be approved");
            require(
                    !a.id().equals(wo.get("requested_by")),
                    "A different authorized person must approve this work order");
            var window = window(c.scheduledStart(), c.scheduledEnd(), false);
            String assignee = assignee(a, c.assigneeId(), wo);
            s.db().update(
                            "UPDATE maintenance_work_orders SET status=?,approved_by=?,approved_at=now(),scheduled_start=?,scheduled_end=?,assignee_id=? WHERE tenant_id=? AND id=?",
                            window == null ? "APPROVED" : "SCHEDULED",
                            a.id(),
                            window == null ? null : window[0],
                            window == null ? null : window[1],
                            assignee,
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "MWO_APPROVED",
                    str(wo, "number"),
                    window == null ? "Approved" : "Approved and scheduled " + window[0] + " – " + window[1]);
            return result(a, id);
        });
    }

    /** PLANNER: sets (or moves) the maintenance window and the assignee of an approved order. */
    @Transactional
    public Map<String, Object> schedule(Actor a, String id, MaintenanceCommands.Schedule c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "schedule-mwo:" + id, c, () -> {
            var wo = rules.workOrder(a, id);
            String status = str(wo, "status");
            require(!status.equals("REQUESTED"), "Approve the work order before scheduling it");
            require(
                    Set.of("APPROVED", "SCHEDULED").contains(status),
                    status.equals("IN_PROGRESS")
                            ? "Work has started; the maintenance window can no longer change"
                            : "This work order is closed");
            var window = window(c.scheduledStart(), c.scheduledEnd(), true);
            String assignee = assignee(a, c.assigneeId(), wo);
            s.db().update(
                            "UPDATE maintenance_work_orders SET status='SCHEDULED',scheduled_start=?,scheduled_end=?,assignee_id=? WHERE tenant_id=? AND id=?",
                            window[0],
                            window[1],
                            assignee,
                            a.tenant(),
                            id);
            s.audit(a, "MWO_SCHEDULED", str(wo, "number"), "Window " + window[0] + " – " + window[1]);
            return result(a, id);
        });
    }

    // ------------------------------------------------------------------ execution

    @Transactional
    public Map<String, Object> start(Actor a, String id, String key) {
        a.allow("MAINTENANCE");
        return s.once(a, key, "start-mwo:" + id, Map.of(), () -> {
            var wo = rules.workOrder(a, id);
            String status = str(wo, "status");
            require(!status.equals("REQUESTED"), "Approve the work order before starting it");
            require(
                    Set.of("APPROVED", "SCHEDULED").contains(status),
                    status.equals("IN_PROGRESS") ? "Work has already started" : "This work order is closed");
            var machine = rules.machine(a, str(wo, "machine_id"));
            String code = str(machine, "code");
            var running = rules.runningOrders(a, str(machine, "id"));
            require(
                    running.isEmpty(),
                    String.join(", ", running) + (running.size() == 1 ? " is" : " are") + " running on " + code
                            + "; pause production (the order, or its running operation on the Shop floor) before maintenance starts");
            var busy = s.list(
                    "SELECT number FROM maintenance_work_orders WHERE tenant_id=? AND machine_id=? AND status='IN_PROGRESS'",
                    a.tenant(),
                    machine.get("id"));
            require(
                    busy.isEmpty(),
                    busy.isEmpty() ? "" : busy.getFirst().get("number") + " is already in progress on " + code);
            String number = str(wo, "number");
            OffsetDateTime now = MaintenanceRules.now();
            String downtime = coverWithDowntime(a, wo, machine, now);
            rules.setMachineStatus(a, machine, "MAINTENANCE", "MAINTENANCE_ORDER", number, "Work started");
            s.db().update(
                            "UPDATE maintenance_work_orders SET status='IN_PROGRESS',started_by=?,started_at=?,assignee_id=COALESCE(assignee_id,?) WHERE tenant_id=? AND id=?",
                            a.id(),
                            now,
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(a, "MWO_STARTED", number, code + " under maintenance · downtime " + downtime);
            return result(a, id);
        });
    }

    /**
     * The open downtime event that covers the work: the order's own, an unlinked open breakdown of the machine, or a new
     * event (any other open event of the machine is closed first). Returns its number.
     */
    private String coverWithDowntime(Actor a, Map<String, Object> wo, Map<String, Object> machine, OffsetDateTime now) {
        String id = str(wo, "id");
        var open = rules.openDowntime(a, str(machine, "id"));
        if (open != null && id.equals(open.get("work_order_id"))) return str(open, "number");
        if (open != null && open.get("reason").equals("BREAKDOWN") && !linkedToOpenOrder(a, open)) {
            s.db().update(
                            "UPDATE downtime_events SET work_order_id=? WHERE tenant_id=? AND id=?",
                            id,
                            a.tenant(),
                            open.get("id"));
            return str(open, "number");
        }
        if (open != null) rules.closeDowntime(a, open, now, "Closed when " + wo.get("number") + " started");
        boolean planned = wo.get("type").equals("PREVENTIVE") || wo.get("scheduled_start") != null;
        var event = rules.insertDowntime(
                a,
                machine,
                planned ? "PLANNED_MAINTENANCE" : "BREAKDOWN",
                planned,
                now,
                null,
                null,
                id,
                "Maintenance " + wo.get("number"),
                null,
                null,
                null);
        return event.get("number") + " (" + reasonLabel(planned ? "PLANNED_MAINTENANCE" : "BREAKDOWN") + ")";
    }

    private boolean linkedToOpenOrder(Actor a, Map<String, Object> event) {
        if (event.get("work_order_id") == null) return false;
        var other = s.one(
                "SELECT status FROM maintenance_work_orders WHERE tenant_id=? AND id=?",
                a.tenant(),
                event.get("work_order_id"));
        return MaintenanceRules.OPEN_ORDER.contains(str(other, "status"));
    }

    /** MAINTENANCE: checklist results, findings and actions while the work is in progress. */
    @Transactional
    public Map<String, Object> progress(Actor a, String id, MaintenanceCommands.Progress c, String key) {
        a.allow("MAINTENANCE");
        return s.once(a, key, "progress-mwo:" + id, c, () -> {
            var wo = rules.workOrder(a, id);
            require(wo.get("status").equals("IN_PROGRESS"), "Start the work order before recording progress");
            var checklist = rules.applyResults(wo.get("checklist"), c.checklist());
            s.db().update(
                            "UPDATE maintenance_work_orders SET checklist=?::jsonb,findings=COALESCE(?,findings),actions=COALESCE(?,actions) WHERE tenant_id=? AND id=?",
                            s.encode(checklist),
                            c.findings() == null ? null : c.findings().strip(),
                            c.actions() == null ? null : c.actions().strip(),
                            a.tenant(),
                            id);
            long done = checklist.size() - MaintenanceRules.openItems(checklist);
            s.audit(
                    a,
                    "MWO_PROGRESS_RECORDED",
                    str(wo, "number"),
                    checklist.isEmpty() ? "Notes updated" : done + " of " + checklist.size() + " checklist items done");
            return result(a, id);
        });
    }

    /**
     * MAINTENANCE: labour hours of a technician at the tenant's labour rate; posts Dr {@value
     * MaintenanceRules#EXPENSE_ACCOUNT} / Cr {@value MaintenanceRules#LABOUR_CLEARING} (reversed for negative hours).
     */
    @Transactional
    public Map<String, Object> labour(Actor a, String id, MaintenanceCommands.Labour c, String key) {
        a.allow("MAINTENANCE");
        return s.once(a, key, "labour-mwo:" + id, c, () -> {
            s.openPeriod(a);
            var wo = rules.workOrder(a, id);
            require(wo.get("status").equals("IN_PROGRESS"), "Labour is logged while the work order is in progress");
            require(c.hours().signum() != 0, "Enter the hours worked");
            String technician =
                    blankToNull(c.technicianId()) == null ? a.id() : str(rules.technician(a, c.technicianId()), "id");
            BigDecimal rate = rules.labourRate(a.tenant());
            BigDecimal cost = money(c.hours().multiply(rate));
            BigDecimal hours = dec(wo.get("labour_hours")).add(c.hours());
            require(
                    hours.signum() >= 0,
                    "Only " + dec(wo.get("labour_hours")).stripTrailingZeros().toPlainString()
                            + " labour hours are recorded on this order");
            String number = str(wo, "number");
            s.db().update(
                            "INSERT INTO maintenance_labour(id,tenant_id,work_order_id,technician_id,hours,rate,cost,notes,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                            id(),
                            a.tenant(),
                            id,
                            technician,
                            c.hours(),
                            rate,
                            cost,
                            c.notes() == null ? "" : c.notes().strip(),
                            a.id());
            s.db().update(
                            "UPDATE maintenance_work_orders SET labour_hours=?,labour_cost=GREATEST(labour_cost+?,0) WHERE tenant_id=? AND id=?",
                            hours,
                            cost,
                            a.tenant(),
                            id);
            if (cost.signum() > 0)
                s.journal(
                        a,
                        number,
                        "Maintenance labour",
                        MaintenanceRules.EXPENSE_ACCOUNT,
                        MaintenanceRules.LABOUR_CLEARING,
                        cost);
            else
                s.journal(
                        a,
                        number,
                        "Maintenance labour reversed",
                        MaintenanceRules.LABOUR_CLEARING,
                        MaintenanceRules.EXPENSE_ACCOUNT,
                        cost.negate());
            s.audit(
                    a,
                    "MWO_LABOUR_LOGGED",
                    number,
                    c.hours().stripTrailingZeros().toPlainString() + " h at Rs. "
                            + money(rate).toPlainString() + " = Rs. " + cost.toPlainString());
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> complete(Actor a, String id, MaintenanceCommands.Complete c, String key) {
        a.allow("MAINTENANCE");
        return s.once(a, key, "complete-mwo:" + id, c, () -> {
            var wo = rules.workOrder(a, id);
            require(wo.get("status").equals("IN_PROGRESS"), "Only work in progress can be completed");
            var checklist = rules.applyResults(wo.get("checklist"), c.checklist());
            long open = MaintenanceRules.openItems(checklist);
            require(
                    open == 0,
                    "Record a result for every checklist item (" + open + (open == 1 ? " item" : " items")
                            + " still open)");
            var machine = rules.machine(a, str(wo, "machine_id"));
            String number = str(wo, "number");
            OffsetDateTime now = MaintenanceRules.now();
            if (c.meterReading() != null) PlanService.recordMeter(s, rules, a, machine, c.meterReading(), number);
            long downtimeMinutes = 0;
            for (var event : s.list(
                    "SELECT * FROM downtime_events WHERE tenant_id=? AND work_order_id=? AND status='OPEN' FOR UPDATE",
                    a.tenant(),
                    id)) {
                rules.closeDowntime(a, event, now, "Closed by " + number);
                downtimeMinutes += MaintenanceRules.minutes(event.get("started_at"), now);
            }
            rules.setMachineStatus(a, machine, "IDLE", "MAINTENANCE_ORDER", number, "Work completed");
            s.db().update(
                            "UPDATE maintenance_work_orders SET status='COMPLETED',completed_by=?,completed_at=?,findings=?,actions=?,checklist=?::jsonb,meter_reading=? WHERE tenant_id=? AND id=?",
                            a.id(),
                            now,
                            c.findings().strip(),
                            c.actions() == null
                                    ? str(wo, "actions")
                                    : c.actions().strip(),
                            s.encode(checklist),
                            c.meterReading(),
                            a.tenant(),
                            id);
            String next = "";
            if (wo.get("plan_id") != null) next = " · next due " + PlanService.reschedule(s, rules, a, wo);
            s.audit(
                    a,
                    "MWO_COMPLETED",
                    number,
                    machine.get("code") + " back to idle · downtime " + downtimeMinutes + " min · parts Rs. "
                            + money(dec(wo.get("parts_cost"))).toPlainString() + " · labour "
                            + dec(wo.get("labour_hours")).stripTrailingZeros().toPlainString() + " h" + next);
            return result(a, id);
        });
    }

    /**
     * PLANNER: cancels an order before work starts (issued parts must be returned first). Cancelling a preventive order
     * skips that occurrence: the plan's next due date moves on by one interval.
     */
    @Transactional
    public Map<String, Object> cancel(Actor a, String id, MaintenanceCommands.Cancel c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "cancel-mwo:" + id, c, () -> {
            var wo = rules.workOrder(a, id);
            String status = str(wo, "status");
            require(
                    Set.of("REQUESTED", "APPROVED", "SCHEDULED").contains(status),
                    status.equals("IN_PROGRESS")
                            ? "Work has started; complete the order with its findings instead"
                            : "This work order is already closed");
            BigDecimal unreturned = s.db().queryForObject(
                            "SELECT COALESCE(SUM(CASE WHEN kind='ISSUE' THEN quantity ELSE -quantity END),0) FROM maintenance_part_movements WHERE tenant_id=? AND work_order_id=?",
                            BigDecimal.class,
                            a.tenant(),
                            id);
            require(unreturned.signum() == 0, "Return the issued spare parts before cancelling this work order");
            s.db().update(
                            "UPDATE maintenance_work_orders SET status='CANCELLED',cancelled_by=?,cancelled_at=now(),cancel_reason=? WHERE tenant_id=? AND id=?",
                            a.id(),
                            c.reason().strip(),
                            a.tenant(),
                            id);
            String next = "";
            if (wo.get("plan_id") != null) next = PlanService.skip(s, a, wo);
            s.audit(a, "MWO_CANCELLED", str(wo, "number"), c.reason().strip() + next);
            return result(a, id);
        });
    }

    /** ADMIN: the maintenance labour rate (LKR per hour) used for new labour entries. */
    @Transactional
    public Map<String, Object> settings(Actor a, MaintenanceCommands.Settings c, String key) {
        a.allow();
        return s.once(a, key, "maintenance-settings", c, () -> {
            s.db().update("""
                            INSERT INTO maintenance_settings(tenant_id,labour_rate,updated_by,updated_at) VALUES(?,?,?,now())
                            ON CONFLICT (tenant_id) DO UPDATE SET labour_rate=EXCLUDED.labour_rate,updated_by=EXCLUDED.updated_by,updated_at=now()""", a.tenant(), c.labourRate(), a.id());
            s.audit(
                    a,
                    "MAINTENANCE_SETTINGS_UPDATED",
                    "MAINTENANCE",
                    "Labour rate Rs. " + money(c.labourRate()).toPlainString() + " per hour");
            return Map.of("labourRate", c.labourRate());
        });
    }

    // ------------------------------------------------------------------ helpers

    /** {start, end} of a maintenance window, or null when neither is given (and not {@code required}). */
    private static OffsetDateTime[] window(String startText, String endText, boolean required) {
        OffsetDateTime start = MaintenanceRules.time(startText, "window start");
        OffsetDateTime end = MaintenanceRules.time(endText, "window end");
        if (!required && start == null && end == null) return null;
        require(start != null && end != null, "Enter both the start and the end of the maintenance window");
        require(end.isAfter(start), "The maintenance window must end after it starts");
        require(
                java.time.Duration.between(start, end).toDays() <= 31,
                "A maintenance window can be at most 31 days long");
        return new OffsetDateTime[] {start, end};
    }

    private String assignee(Actor a, String requested, Map<String, Object> wo) {
        String value = blankToNull(requested);
        if (value == null) return (String) wo.get("assignee_id");
        return str(rules.technician(a, value), "id");
    }

    private static String open(Map<String, Object> event) {
        return event == null ? "" : event.get("number") + " (" + reasonLabel(event.get("reason")) + ")";
    }

    Map<String, Object> result(Actor a, String id) {
        return new LinkedHashMap<>(s.one(
                "SELECT id,number,type,status,machine_id,plan_id,due_date,priority,scheduled_start,scheduled_end,assignee_id,labour_hours,labour_cost,parts_cost FROM maintenance_work_orders WHERE tenant_id=? AND id=?",
                a.tenant(),
                id));
    }

    static List<String> statuses() {
        return List.of("REQUESTED", "APPROVED", "SCHEDULED", "IN_PROGRESS", "COMPLETED", "CANCELLED");
    }
}
