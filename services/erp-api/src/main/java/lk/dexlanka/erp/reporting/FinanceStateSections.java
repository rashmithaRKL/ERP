package lk.dexlanka.erp.reporting;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/** Financial aggregates and journal lines; returned to ADMIN and PLANNER only. */
@Component
class FinanceStateSections implements StateContributor {
    private final Store s;

    FinanceStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(
                StateSection.of("financial", 310, this::financial).visibleToRoles("ADMIN", "PLANNER"),
                StateSection.of("journal", 320, this::journal).visibleToRoles("ADMIN", "PLANNER"));
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

    private List<Map<String, Object>> journal(StateRequest r) {
        return s.list("""
                SELECT e.reference,e.description,e.created_at,l.account,l.debit,l.credit
                FROM journal_entries e
                JOIN journal_lines l ON l.tenant_id=e.tenant_id AND l.entry_id=e.id
                WHERE e.tenant_id=?
                ORDER BY e.created_at DESC,e.id,l.debit DESC
                LIMIT 250""", r.tenant());
    }
}
