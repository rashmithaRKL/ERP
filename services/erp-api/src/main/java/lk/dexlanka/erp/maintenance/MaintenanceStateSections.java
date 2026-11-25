package lk.dexlanka.erp.maintenance;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateRequest;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/**
 * {@code /api/state} key {@code maintenance} (order 2100): small attention counts for the overview and the attention
 * center, with at most five rows each of open breakdowns and overdue preventive plans. Screens read the paged
 * {@code /api/maintenance/**} endpoints for everything else.
 */
@Component
class MaintenanceStateSections implements StateContributor {
    private final Store s;

    MaintenanceStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("maintenance", 2100, this::maintenance));
    }

    private Map<String, Object> maintenance(StateRequest r) {
        LocalDate today = MaintenanceRules.today();
        var counts = s.one(
                """
                SELECT
                  (SELECT COUNT(*) FROM downtime_events WHERE tenant_id=? AND status='OPEN' AND reason='BREAKDOWN') AS open_breakdowns,
                  (SELECT COUNT(*) FROM downtime_events WHERE tenant_id=? AND status='OPEN') AS open_downtime,
                  (SELECT COUNT(*) FROM maintenance_plans WHERE tenant_id=? AND active AND next_due_date<?::date) AS overdue_plans,
                  (SELECT COUNT(*) FROM maintenance_work_orders WHERE tenant_id=? AND status='REQUESTED') AS awaiting_approval,
                  (SELECT COUNT(*) FROM maintenance_work_orders WHERE tenant_id=? AND status='IN_PROGRESS') AS in_progress""",
                r.tenant(),
                r.tenant(),
                r.tenant(),
                today,
                r.tenant(),
                r.tenant());
        var result = new LinkedHashMap<String, Object>(counts);
        result.put("breakdowns", s.list("""
                SELECT d.id,d.number,d.machine_id,m.code AS machine,d.started_at,w.number AS work_order,w.status AS work_order_status
                FROM downtime_events d
                JOIN machines m ON m.tenant_id=d.tenant_id AND m.id=d.machine_id
                LEFT JOIN maintenance_work_orders w ON w.tenant_id=d.tenant_id AND w.id=d.work_order_id
                WHERE d.tenant_id=? AND d.status='OPEN' AND d.reason='BREAKDOWN'
                ORDER BY d.started_at
                LIMIT 5""", r.tenant()));
        result.put("overdue", s.list("""
                SELECT p.id,p.number,p.title,p.machine_id,m.code AS machine,p.next_due_date
                FROM maintenance_plans p JOIN machines m ON m.tenant_id=p.tenant_id AND m.id=p.machine_id
                WHERE p.tenant_id=? AND p.active AND p.next_due_date<?::date
                ORDER BY p.next_due_date,p.number
                LIMIT 5""", r.tenant(), today));
        return result;
    }
}
