package lk.dexlanka.erp.quality;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateRequest;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/** {@code /api/state} keys owned by quality control. */
@Component
class QualityStateSections implements StateContributor {
    private final Store s;

    QualityStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(
                StateSection.of("inspections", 230, this::inspections),
                StateSection.of("qualityAlerts", 2200, this::alerts));
    }

    private List<Map<String, Object>> inspections(StateRequest r) {
        return s.list("""
                SELECT q.*,l.number AS lot,l.source_id,l.source_type,i.name AS item,b.quantity
                FROM inspections q
                JOIN lots l ON l.tenant_id=q.tenant_id AND l.id=q.lot_id
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                WHERE q.tenant_id=?
                ORDER BY q.created_at DESC
                LIMIT 250""", r.tenant());
    }

    /**
     * V12 attention counts (one small object): open NCRs, open critical NCRs, concessions awaiting approval, supplier
     * returns awaiting the warehouse, open rework orders and overdue CAPA actions (all, and the caller's own).
     */
    private Map<String, Object> alerts(StateRequest r) {
        return s.one("""
                SELECT (SELECT COUNT(*) FROM ncrs WHERE tenant_id=t.id AND status='OPEN') AS open_ncrs,
                       (SELECT COUNT(*) FROM ncrs WHERE tenant_id=t.id AND status='OPEN' AND severity='CRITICAL')
                         AS open_critical_ncrs,
                       (SELECT COUNT(*) FROM ncr_dispositions WHERE tenant_id=t.id AND status='PENDING_APPROVAL')
                         AS pending_concessions,
                       (SELECT COUNT(*) FROM ncr_dispositions WHERE tenant_id=t.id AND status='AWAITING_RETURN')
                         AS awaiting_returns,
                       (SELECT COUNT(*) FROM rework_orders WHERE tenant_id=t.id AND status='OPEN') AS open_rework,
                       (SELECT COUNT(*) FROM capa_actions x JOIN capas c ON c.tenant_id=x.tenant_id AND c.id=x.capa_id
                        WHERE x.tenant_id=t.id AND x.status='OPEN' AND x.due_date<CURRENT_DATE
                          AND c.status NOT IN ('CLOSED','CANCELLED')) AS overdue_capa_actions,
                       (SELECT COUNT(*) FROM capa_actions x JOIN capas c ON c.tenant_id=x.tenant_id AND c.id=x.capa_id
                        WHERE x.tenant_id=t.id AND x.status='OPEN' AND x.due_date<CURRENT_DATE AND x.owner_id=?
                          AND c.status NOT IN ('CLOSED','CANCELLED')) AS my_overdue_capa_actions
                FROM tenants t WHERE t.id=?""", r.actor().id(), r.tenant());
    }
}
