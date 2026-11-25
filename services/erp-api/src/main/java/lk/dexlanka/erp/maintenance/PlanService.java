package lk.dexlanka.erp.maintenance;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preventive maintenance plans ({@code PM-…}) and machine run-hour meter readings.
 *
 * <p>A plan always has a calendar interval in days and may add a run-hour interval: whichever comes first. A recorded
 * meter reading that reaches the run-hour interval (counted from the reading at the last completed service) pulls the
 * plan's next due date forward to today. {@link #generate} creates one PREVENTIVE work order per active plan due within
 * the horizon (idempotent per plan and due date, never while the plan already has an open order), approved by the
 * generating planner and scheduled on the due date at 08:00 Asia/Colombo (or the next full hour when that has passed)
 * for the plan's estimated hours. Completing the order reschedules the plan from the completion date; cancelling it
 * skips that occurrence.
 */
@Service
public class PlanService {
    private static final LocalTime DEFAULT_START = LocalTime.of(8, 0);

    private final Store s;
    private final MaintenanceRules rules;

    PlanService(Store s, MaintenanceRules rules) {
        this.s = s;
        this.rules = rules;
    }

    @Transactional
    public Map<String, Object> create(Actor a, MaintenanceCommands.Plan c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "maintenance-plan", c, () -> {
            var machine = rules.machine(a, c.machineId());
            LocalDate due = MaintenanceRules.date(c.nextDueDate(), "first due date");
            var checklist = cleanChecklist(c.checklist());
            String id = id();
            String number = rules.number(a, "maintenance_plans", "PM");
            BigDecimal baseline = c.intervalHours() == null ? null : rules.lastMeterReading(a, str(machine, "id"));
            s.db().update("""
                            INSERT INTO maintenance_plans(id,tenant_id,number,machine_id,title,description,checklist,interval_days,interval_hours,
                              estimated_hours,priority,next_due_date,meter_at_last_service,created_by)
                            VALUES(?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)""", id, a.tenant(), number, machine.get("id"), c.title().strip(), c.description() == null ? "" : c.description().strip(), s.encode(checklist), c.intervalDays(), c.intervalHours(), c.estimatedHours() == null ? new BigDecimal("2") : c.estimatedHours(), priority(c.priority()), due, baseline, a.id());
            s.audit(
                    a,
                    "PM_PLAN_CREATED",
                    number,
                    machine.get("code") + " · " + c.title().strip() + " · every " + interval(c.intervalDays(), c.intervalHours())
                            + " · first due " + due);
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> update(Actor a, String id, MaintenanceCommands.PlanUpdate c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "update-plan:" + id, c, () -> {
            var plan = s.one("SELECT * FROM maintenance_plans WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
            LocalDate due = MaintenanceRules.date(c.nextDueDate(), "next due date");
            var checklist = cleanChecklist(c.checklist());
            s.db().update("""
                            UPDATE maintenance_plans SET title=?,description=?,checklist=?::jsonb,interval_days=?,interval_hours=?,estimated_hours=?,
                              priority=?,next_due_date=?,active=?,updated_at=now()
                            WHERE tenant_id=? AND id=?""", c.title().strip(), c.description() == null ? "" : c.description().strip(), s.encode(checklist), c.intervalDays(), c.intervalHours(), c.estimatedHours() == null ? plan.get("estimated_hours") : c.estimatedHours(), priority(c.priority()), due, c.active(), a.tenant(), id);
            s.audit(
                    a,
                    "PM_PLAN_UPDATED",
                    str(plan, "number"),
                    c.title().strip() + " · every " + interval(c.intervalDays(), c.intervalHours()) + " · next due " + due
                            + (c.active() ? "" : " · inactive"));
            return result(a, id);
        });
    }

    /** PLANNER: preventive work orders for every active plan due within {@code horizonDays}. */
    @Transactional
    public Map<String, Object> generate(Actor a, MaintenanceCommands.Generate c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "generate-pm", c, () -> {
            LocalDate today = MaintenanceRules.today();
            var created = new ArrayList<Map<String, Object>>();
            var skipped = new ArrayList<Map<String, Object>>();
            for (var plan : s.list("""
                            SELECT p.*,m.code AS machine_code FROM maintenance_plans p
                            JOIN machines m ON m.tenant_id=p.tenant_id AND m.id=p.machine_id
                            WHERE p.tenant_id=? AND p.active AND p.next_due_date<=?
                            ORDER BY p.next_due_date,p.number
                            FOR UPDATE OF p""", a.tenant(), today.plusDays(c.horizonDays()))) {
                LocalDate due = ((java.sql.Date) plan.get("next_due_date")).toLocalDate();
                var open = s.list(
                        "SELECT number FROM maintenance_work_orders WHERE tenant_id=? AND plan_id=? AND status IN ('REQUESTED','APPROVED','SCHEDULED','IN_PROGRESS')",
                        a.tenant(),
                        plan.get("id"));
                var same = s.list(
                        "SELECT number FROM maintenance_work_orders WHERE tenant_id=? AND plan_id=? AND due_date=?",
                        a.tenant(),
                        plan.get("id"),
                        due);
                if (!open.isEmpty() || !same.isEmpty()) {
                    var skip = new LinkedHashMap<String, Object>();
                    skip.put("plan", plan.get("number"));
                    skip.put("workOrder", (open.isEmpty() ? same : open).getFirst().get("number"));
                    skipped.add(skip);
                    continue;
                }
                created.add(createPreventive(a, plan, due));
            }
            s.audit(
                    a,
                    "PM_ORDERS_GENERATED",
                    "PM",
                    created.size() + (created.size() == 1 ? " preventive order" : " preventive orders") + " created"
                            + (created.isEmpty()
                                    ? ""
                                    : " (" + String.join(
                                                    ", ",
                                                    created.stream()
                                                            .map(m -> str(m, "number"))
                                                            .toList())
                                            + ")")
                            + " · " + skipped.size() + " already ordered · horizon " + c.horizonDays() + " days");
            var result = new LinkedHashMap<String, Object>();
            result.put("created", created);
            result.put("skipped", skipped);
            return result;
        });
    }

    private Map<String, Object> createPreventive(Actor a, Map<String, Object> plan, LocalDate due) {
        String id = id();
        String number = rules.number(a, "maintenance_work_orders", "MWO");
        OffsetDateTime start = due.atTime(DEFAULT_START)
                .atZone(MaintenanceRules.BUSINESS_ZONE)
                .toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime now = MaintenanceRules.now();
        if (start.isBefore(now)) start = now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
        long minutes = dec(plan.get("estimated_hours"))
                .multiply(new BigDecimal(60))
                .setScale(0, java.math.RoundingMode.CEILING)
                .longValueExact();
        OffsetDateTime end = start.plusMinutes(Math.max(minutes, 1));
        List<String> titles = rules.readTasks(plan.get("checklist"));
        s.db().update("""
                        INSERT INTO maintenance_work_orders(id,tenant_id,number,type,machine_id,plan_id,due_date,title,description,priority,status,
                          scheduled_start,scheduled_end,checklist,requested_by,approved_by,approved_at)
                        VALUES(?,?,?,'PREVENTIVE',?,?,?,?,?,?,'SCHEDULED',?,?,?::jsonb,?,?,now())""", id, a.tenant(), number, plan.get("machine_id"), plan.get("id"), due, plan.get("title"), plan.get("description"), plan.get("priority"), start, end, s.encode(MaintenanceRules.checklist(titles)), a.id(), a.id());
        s.audit(
                a,
                "MWO_GENERATED",
                number,
                "Preventive " + plan.get("number") + " · " + plan.get("machine_code") + " · due " + due);
        var m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("number", number);
        m.put("plan", plan.get("number"));
        m.put("machine", plan.get("machine_code"));
        m.put("dueDate", due.toString());
        m.put("checklistItems", titles.size());
        return m;
    }

    /** MAINTENANCE/PLANNER/OPERATOR: a run-hour meter reading of a machine (never below the previous reading). */
    @Transactional
    public Map<String, Object> meter(Actor a, String machineId, MaintenanceCommands.Meter c, String key) {
        a.allow("MAINTENANCE", "PLANNER", "OPERATOR");
        return s.once(a, key, "meter:" + machineId, c, () -> {
            var machine = rules.machine(a, machineId);
            var due = recordMeter(s, rules, a, machine, c.reading(), c.notes());
            var result = new LinkedHashMap<String, Object>();
            result.put("machineId", machineId);
            result.put("reading", c.reading());
            result.put("plansDue", due);
            return result;
        });
    }

    // ------------------------------------------------------------------ shared with WorkOrderService

    /**
     * Records a meter reading (audited) and pulls run-hour plans of the machine whose interval is reached forward to
     * today. Returns the numbers of those plans.
     */
    static List<String> recordMeter(
            Store s, MaintenanceRules rules, Actor a, Map<String, Object> machine, BigDecimal reading, String note) {
        String machineId = str(machine, "id");
        BigDecimal last = rules.lastMeterReading(a, machineId);
        require(
                last == null || reading.compareTo(last) >= 0,
                "The run-hour meter cannot go backwards: the last reading of " + machine.get("code") + " is "
                        + last.stripTrailingZeros().toPlainString() + " h");
        s.db().update(
                        "INSERT INTO machine_meter_readings(id,tenant_id,machine_id,reading,notes,recorded_by) VALUES(?,?,?,?,?,?)",
                        id(),
                        a.tenant(),
                        machineId,
                        reading,
                        note == null ? "" : note.strip(),
                        a.id());
        LocalDate today = MaintenanceRules.today();
        var due = new ArrayList<String>();
        for (var plan : s.list(
                "SELECT * FROM maintenance_plans WHERE tenant_id=? AND machine_id=? AND active AND interval_hours IS NOT NULL FOR UPDATE",
                a.tenant(),
                machineId)) {
            if (plan.get("meter_at_last_service") == null) {
                // The first reading of a run-hour plan starts its count.
                s.db().update(
                                "UPDATE maintenance_plans SET meter_at_last_service=?,updated_at=now() WHERE tenant_id=? AND id=?",
                                reading,
                                a.tenant(),
                                plan.get("id"));
                continue;
            }
            BigDecimal run = reading.subtract(dec(plan.get("meter_at_last_service")));
            if (run.compareTo(dec(plan.get("interval_hours"))) >= 0) {
                LocalDate next = ((java.sql.Date) plan.get("next_due_date")).toLocalDate();
                if (next.isAfter(today))
                    s.db().update(
                                    "UPDATE maintenance_plans SET next_due_date=?,updated_at=now() WHERE tenant_id=? AND id=?",
                                    today,
                                    a.tenant(),
                                    plan.get("id"));
                due.add(str(plan, "number"));
            }
        }
        s.audit(
                a,
                "MACHINE_METER_RECORDED",
                str(machine, "code"),
                reading.stripTrailingZeros().toPlainString() + " run hours"
                        + (due.isEmpty() ? "" : " · due now: " + String.join(", ", due)));
        return due;
    }

    /**
     * After a preventive order is completed: the plan's next due date is the completion date plus the interval, and the
     * run-hour count restarts from the latest meter reading. Returns the new due date.
     */
    static LocalDate reschedule(Store s, MaintenanceRules rules, Actor a, Map<String, Object> wo) {
        var plan = s.one(
                "SELECT * FROM maintenance_plans WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), wo.get("plan_id"));
        LocalDate next = MaintenanceRules.today().plusDays(((Number) plan.get("interval_days")).longValue());
        BigDecimal meter = rules.lastMeterReading(a, str(wo, "machine_id"));
        s.db().update(
                        "UPDATE maintenance_plans SET next_due_date=?,last_completed_at=now(),meter_at_last_service=COALESCE(?,meter_at_last_service),updated_at=now() WHERE tenant_id=? AND id=?",
                        next,
                        meter,
                        a.tenant(),
                        plan.get("id"));
        return next;
    }

    /**
     * After a preventive order is cancelled: when the plan still waits for that occurrence, its next due date moves on by
     * one interval (the occurrence is skipped). Returns an audit suffix.
     */
    static String skip(Store s, Actor a, Map<String, Object> wo) {
        var plan = s.one(
                "SELECT * FROM maintenance_plans WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), wo.get("plan_id"));
        LocalDate occurrence = ((java.sql.Date) wo.get("due_date")).toLocalDate();
        LocalDate current = ((java.sql.Date) plan.get("next_due_date")).toLocalDate();
        if (!current.equals(occurrence)) return "";
        LocalDate next = occurrence.plusDays(((Number) plan.get("interval_days")).longValue());
        s.db().update(
                        "UPDATE maintenance_plans SET next_due_date=?,updated_at=now() WHERE tenant_id=? AND id=?",
                        next,
                        a.tenant(),
                        plan.get("id"));
        return " · " + plan.get("number") + " occurrence " + occurrence + " skipped, next due " + next;
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> cleanChecklist(List<String> items) {
        var out = new ArrayList<String>();
        for (String item : items) if (item != null && !item.isBlank()) out.add(item.strip());
        return out;
    }

    private static String priority(String value) {
        return value == null || value.isBlank() ? "NORMAL" : value;
    }

    private static String interval(Integer days, BigDecimal hours) {
        return days + (days == 1 ? " day" : " days")
                + (hours == null ? "" : " or " + hours.stripTrailingZeros().toPlainString() + " run hours");
    }

    private Map<String, Object> result(Actor a, String id) {
        return new LinkedHashMap<>(s.one(
                "SELECT id,number,machine_id,title,interval_days,interval_hours,estimated_hours,priority,next_due_date,active FROM maintenance_plans WHERE tenant_id=? AND id=?",
                a.tenant(),
                id));
    }
}
