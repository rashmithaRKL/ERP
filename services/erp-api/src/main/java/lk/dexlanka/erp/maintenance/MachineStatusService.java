package lk.dexlanka.erp.maintenance;

import static lk.dexlanka.erp.maintenance.MaintenanceRules.statusLabel;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A planner's correction of a wrong machine status ({@code IDLE} or {@code RUNNING}, with a reason). It never bypasses
 * maintenance: a machine with a work order in progress stays in {@code MAINTENANCE} until the order is completed, and a
 * machine with open downtime cannot be marked running. {@code MAINTENANCE} itself is only entered by starting a work
 * order. Every change is audited and recorded in {@code machine_status_changes} (source {@code MANUAL}).
 */
@Service
public class MachineStatusService {
    private final Store s;
    private final MaintenanceRules rules;

    MachineStatusService(Store s, MaintenanceRules rules) {
        this.s = s;
        this.rules = rules;
    }

    @Transactional
    public Map<String, Object> correct(Actor a, String machineId, MaintenanceCommands.MachineStatus c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "machine-status:" + machineId, c, () -> {
            var machine = rules.machine(a, machineId);
            String code = str(machine, "code");
            String from = str(machine, "status");
            require(
                    !from.equals(c.status()),
                    code + " is already " + statusLabel(from).toLowerCase());
            var busy = s.list(
                    "SELECT number FROM maintenance_work_orders WHERE tenant_id=? AND machine_id=? AND status='IN_PROGRESS'",
                    a.tenant(),
                    machineId);
            require(
                    busy.isEmpty(),
                    busy.isEmpty()
                            ? ""
                            : busy.getFirst().get("number") + " is in progress on " + code
                                    + "; completing it ends the maintenance");
            if (c.status().equals("RUNNING")) {
                var open = rules.openDowntime(a, machineId);
                require(
                        open == null,
                        open == null
                                ? ""
                                : code + " has open downtime " + open.get("number")
                                        + "; close it before marking the machine running");
            }
            rules.setMachineStatus(a, machine, c.status(), "MANUAL", null, c.reason());
            s.audit(
                    a,
                    "MACHINE_STATUS_CORRECTED",
                    code,
                    statusLabel(from) + " → " + statusLabel(c.status()) + " · "
                            + c.reason().strip());
            var result = new LinkedHashMap<String, Object>();
            result.put("id", machineId);
            result.put("code", code);
            result.put("status", c.status());
            return result;
        });
    }
}
