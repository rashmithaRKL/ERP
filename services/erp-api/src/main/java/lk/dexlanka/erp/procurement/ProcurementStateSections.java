package lk.dexlanka.erp.procurement;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateRequest;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/** {@code /api/state} keys owned by procurement. */
@Component
class ProcurementStateSections implements StateContributor {
    private final Store s;

    ProcurementStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(
                StateSection.of("purchases", 210, this::purchases), StateSection.of("receipts", 240, this::receipts));
    }

    private List<Map<String, Object>> purchases(StateRequest r) {
        return s.list("""
                SELECT po.*,p.name AS supplier,i.name AS item,i.uom
                FROM purchase_orders po
                JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id
                JOIN items i ON i.tenant_id=po.tenant_id AND i.id=po.item_id
                WHERE po.tenant_id=?
                ORDER BY po.created_at DESC
                LIMIT 250""", r.tenant());
    }

    private List<Map<String, Object>> receipts(StateRequest r) {
        return s.list("""
                SELECT r.*,l.number AS lot,l.status,p.number AS purchase_order
                FROM receipts r
                JOIN lots l ON l.tenant_id=r.tenant_id AND l.id=r.lot_id
                JOIN purchase_orders p ON p.tenant_id=r.tenant_id AND p.id=r.purchase_order_id
                WHERE r.tenant_id=?
                ORDER BY r.created_at DESC
                LIMIT 250""", r.tenant());
    }
}
