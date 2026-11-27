package lk.dexlanka.erp.reporting;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Financial aggregates; returned to ADMIN, PLANNER and (V24) FINANCE only. (The {@code journal} key was removed in V9; Costs & reports
 * reads {@code GET /api/reports/journal}.)
 */
@Component
class FinanceStateSections implements StateContributor {
    private final Store s;

    FinanceStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(
                StateSection.of("financial", 310, this::financial).visibleToRoles("ADMIN", "PLANNER", "FINANCE"));
    }

    private Map<String, Object> financial(StateRequest r) {
        var t = r.tenant();
        return s.one("""
                SELECT (SELECT COALESCE(SUM(l.credit-l.debit),0)
                        FROM journal_lines l
                        JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id
                        WHERE l.tenant_id=? AND l.account='SALES_REVENUE'
                          AND e.created_at>=CURRENT_DATE-(? - 1)) AS revenue,
                       (SELECT COALESCE(SUM(wip_cost),0) FROM manufacturing_orders WHERE tenant_id=?) AS wip,
                       (SELECT COALESCE(SUM(material_cost+conversion_cost),0)
                        FROM manufacturing_orders WHERE tenant_id=?) AS actual_cost,
                       (SELECT COALESCE(SUM(estimated_cost),0)
                        FROM manufacturing_orders WHERE tenant_id=? AND material_cost>0) AS estimated_cost""", t, r.days(), t, t, t);
    }
}
