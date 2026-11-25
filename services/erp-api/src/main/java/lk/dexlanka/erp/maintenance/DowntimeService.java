package lk.dexlanka.erp.maintenance;

import static lk.dexlanka.erp.maintenance.MaintenanceRules.reasonLabel;
import static lk.dexlanka.erp.maintenance.MaintenanceRules.requireNotFuture;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Machine downtime events: report (open, or already closed when the end is known), close, and correct.
 *
 * <p>Operators, planners and maintenance technicians report and close downtime. A closed event is never edited: a
 * planner's correction marks it {@code SUPERSEDED} and records the corrected event ({@code CLOSED}, or {@code VOID} when
 * it is withdrawn) with {@code corrects_id} pointing at the original, so the history stays append-only. Effective events
 * of one machine never overlap (DB trigger {@code downtime_no_overlap}). Downtime does not change the machine status;
 * only maintenance work orders and the planners' corrections do.
 */
@Service
public class DowntimeService {
    static final String[] REPORTERS = {"OPERATOR", "PLANNER", "MAINTENANCE"};

    private final Store s;
    private final MaintenanceRules rules;

    DowntimeService(Store s, MaintenanceRules rules) {
        this.s = s;
        this.rules = rules;
    }

    @Transactional
    public Map<String, Object> report(Actor a, MaintenanceCommands.ReportDowntime c, String key) {
        a.allow(REPORTERS);
        return s.once(a, key, "downtime", c, () -> {
            var machine = rules.machine(a, c.machineId());
            OffsetDateTime start = MaintenanceRules.timeOrNow(c.startedAt(), "downtime start");
            OffsetDateTime end = MaintenanceRules.time(c.endedAt(), "downtime end");
            requireNotFuture(start, "The downtime start");
            if (end != null) requireNotFuture(end, "The downtime end");
            boolean planned = MaintenanceRules.planned(c.reason(), c.planned());
            String order = blankToNull(c.manufacturingOrderId());
            if (order != null) {
                var mo = s.one(
                        "SELECT number,machine_id FROM manufacturing_orders WHERE tenant_id=? AND id=?",
                        a.tenant(),
                        order);
                require(mo.get("machine_id").equals(machine.get("id")), mo.get("number") + " runs on another machine");
            }
            if (end == null) {
                var open = rules.openDowntime(a, str(machine, "id"));
                require(
                        open == null,
                        machine.get("code") + " already has open downtime " + open(open) + "; close it first");
            }
            var event = rules.insertDowntime(
                    a, machine, c.reason(), planned, start, end, order, null, c.notes(), null, null, null);
            s.audit(
                    a,
                    "DOWNTIME_REPORTED",
                    str(event, "number"),
                    machine.get("code") + " · " + reasonLabel(c.reason()) + (planned ? " (planned)" : " (unplanned)")
                            + (end == null ? " · open" : " · " + MaintenanceRules.minutes(start, end) + " min"));
            return result(a, str(event, "id"));
        });
    }

    @Transactional
    public Map<String, Object> close(Actor a, String id, MaintenanceCommands.CloseDowntime c, String key) {
        a.allow(REPORTERS);
        return s.once(a, key, "close-downtime:" + id, c, () -> {
            var event = rules.downtime(a, id);
            require(event.get("status").equals("OPEN"), "Only open downtime can be closed");
            if (event.get("work_order_id") != null) {
                var wo = s.one(
                        "SELECT number,status FROM maintenance_work_orders WHERE tenant_id=? AND id=?",
                        a.tenant(),
                        event.get("work_order_id"));
                require(
                        !wo.get("status").equals("IN_PROGRESS"),
                        wo.get("number") + " is in progress; completing it closes this downtime");
            }
            OffsetDateTime end = MaintenanceRules.timeOrNow(c.endedAt(), "downtime end");
            requireNotFuture(end, "The downtime end");
            rules.closeDowntime(a, event, end, c.notes());
            var machine =
                    s.one("SELECT code FROM machines WHERE tenant_id=? AND id=?", a.tenant(), event.get("machine_id"));
            s.audit(
                    a,
                    "DOWNTIME_CLOSED",
                    str(event, "number"),
                    machine.get("code") + " · " + reasonLabel(event.get("reason")) + " · "
                            + MaintenanceRules.minutes(event.get("started_at"), end) + " min");
            return result(a, id);
        });
    }

    /** PLANNER: replaces a closed event by a corrected one (or withdraws it). */
    @Transactional
    public Map<String, Object> correct(Actor a, String id, MaintenanceCommands.CorrectDowntime c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "correct-downtime:" + id, c, () -> {
            var event = rules.downtime(a, id);
            require(
                    event.get("status").equals("CLOSED"),
                    event.get("status").equals("OPEN")
                            ? "Close the downtime before correcting it"
                            : "This downtime event was already corrected; correct the current event instead");
            OffsetDateTime start = MaintenanceRules.time(c.startedAt(), "downtime start");
            OffsetDateTime end = MaintenanceRules.time(c.endedAt(), "downtime end");
            requireNotFuture(start, "The downtime start");
            requireNotFuture(end, "The downtime end");
            boolean planned = MaintenanceRules.planned(c.reason(), c.planned());
            var machine = rules.machine(a, str(event, "machine_id"));
            // The original leaves the effective record first, so the corrected event may overlap its old times.
            s.db().update("UPDATE downtime_events SET status='SUPERSEDED' WHERE tenant_id=? AND id=?", a.tenant(), id);
            String notes = c.notes() == null ? str(event, "notes") : c.notes();
            var corrected = rules.insertDowntime(
                    a,
                    machine,
                    c.reason(),
                    planned,
                    start,
                    end,
                    (String) event.get("manufacturing_order_id"),
                    (String) event.get("work_order_id"),
                    notes,
                    c.withdraw() ? "VOID" : "CLOSED",
                    id,
                    c.correctionReason().strip());
            if (c.withdraw())
                s.audit(
                        a,
                        "DOWNTIME_VOIDED",
                        str(event, "number"),
                        machine.get("code") + " · withdrawn by " + corrected.get("number") + " · "
                                + c.correctionReason().strip());
            else
                s.audit(
                        a,
                        "DOWNTIME_CORRECTED",
                        str(event, "number"),
                        machine.get("code") + " · replaced by " + corrected.get("number") + " · "
                                + reasonLabel(c.reason()) + ", " + MaintenanceRules.minutes(start, end) + " min · "
                                + c.correctionReason().strip());
            return result(a, str(corrected, "id"));
        });
    }

    private Map<String, Object> result(Actor a, String id) {
        return new LinkedHashMap<>(s.one(
                "SELECT id,number,machine_id,reason,planned,started_at,ended_at,status,work_order_id,corrects_id FROM downtime_events WHERE tenant_id=? AND id=?",
                a.tenant(),
                id));
    }

    private static String open(Map<String, Object> event) {
        return event == null ? "" : event.get("number") + " (" + reasonLabel(event.get("reason")) + ")";
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
