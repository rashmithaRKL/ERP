package lk.dexlanka.erp.reporting;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Material requirements planning: unreserved demand of DRAFT/APPROVED orders plus safety stock, against released
 * unreserved stock and approved incoming purchases. Covers every non-finished item type (raw materials, packaging and
 * semi-finished intermediates); inactive items keep their open demand but no longer ask for safety stock.
 */
@Component
class PlanningStateSections implements StateContributor {
    private final Store s;

    PlanningStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("mrp", 260, this::mrp));
    }

    private List<Map<String, Object>> mrp(StateRequest r) {
        var t = r.tenant();
        return s.list("""
                WITH demand AS(
                    SELECT om.item_id,SUM(om.required_qty) AS gross
                    FROM order_materials om
                    JOIN manufacturing_orders o ON o.tenant_id=om.tenant_id AND o.id=om.order_id
                    WHERE om.tenant_id=? AND o.status IN ('DRAFT','APPROVED')
                    GROUP BY om.item_id),
                stock AS(
                    SELECT l.item_id,SUM(b.quantity-b.reserved) AS available
                    FROM lots l
                    JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                    WHERE l.tenant_id=? AND l.status='RELEASED'
                    GROUP BY l.item_id),
                incoming AS(
                    SELECT l.item_id,SUM(l.base_quantity-l.received) AS incoming
                    FROM purchase_order_lines l
                    JOIN purchase_orders po ON po.tenant_id=l.tenant_id AND po.id=l.purchase_order_id
                    WHERE l.tenant_id=? AND po.status IN ('APPROVED','PARTIAL')
                    GROUP BY l.item_id)
                SELECT i.id,i.sku,i.name,i.uom,i.reorder_level,i.type,i.active,i.reorder_qty,i.lead_time_days,
                       COALESCE(d.gross,0) AS gross,
                       COALESCE(st.available,0) AS available,
                       COALESCE(p.incoming,0) AS incoming,
                       GREATEST(0,COALESCE(d.gross,0)+CASE WHEN i.active THEN i.reorder_level ELSE 0 END
                                  -COALESCE(st.available,0)-COALESCE(p.incoming,0)) AS shortage
                FROM items i
                LEFT JOIN demand d ON d.item_id=i.id
                LEFT JOIN stock st ON st.item_id=i.id
                LEFT JOIN incoming p ON p.item_id=i.id
                WHERE i.tenant_id=? AND i.type<>'FINISHED_GOOD'
                ORDER BY shortage DESC,i.name
                LIMIT 250""", t, t, t, t);
    }
}
