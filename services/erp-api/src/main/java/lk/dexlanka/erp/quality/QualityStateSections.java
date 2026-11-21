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
        return List.of(StateSection.of("inspections", 230, this::inspections));
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
}
