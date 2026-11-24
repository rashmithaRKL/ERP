package lk.dexlanka.erp.procurement;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateRequest;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/**
 * {@code /api/state} key owned by procurement: {@code purchases} (one summary row per order, lines aggregated; for
 * exports and attention items). Pages use the paginated {@code GET /api/purchases/...} endpoints for the documents;
 * the {@code receipts} key was removed in V9 ({@code GET /api/purchases/receipts}).
 */
@Component
class ProcurementStateSections implements StateContributor {
    private final Store s;

    ProcurementStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("purchases", 210, this::purchases));
    }

    private List<Map<String, Object>> purchases(StateRequest r) {
        return s.list("""
                SELECT po.id,po.number,po.status,po.supplier_id,p.name AS supplier,po.supplier_reference,po.due_date,
                       po.total,po.created_at,po.created_by,po.submitted_by,po.status_reason,
                       agg.line_count,agg.items AS item,agg.uom,agg.quantity,agg.received,agg.returned
                FROM purchase_orders po
                LEFT JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS line_count,
                           COALESCE(SUM(l.base_quantity),0) AS quantity,
                           COALESCE(SUM(l.received),0) AS received,
                           COALESCE(SUM(l.returned),0) AS returned,
                           CASE WHEN COUNT(DISTINCT i.uom)=1 THEN MIN(i.uom) END AS uom,
                           string_agg(i.name, ', ' ORDER BY l.line_no) AS items
                    FROM purchase_order_lines l
                    JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                    WHERE l.tenant_id=po.tenant_id AND l.purchase_order_id=po.id) agg ON true
                WHERE po.tenant_id=?
                ORDER BY po.created_at DESC,po.id DESC
                LIMIT 250""", r.tenant());
    }
}
