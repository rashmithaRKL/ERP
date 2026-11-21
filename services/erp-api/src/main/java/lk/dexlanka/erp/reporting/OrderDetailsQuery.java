package lk.dexlanka.erp.reporting;

import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** {@code GET /api/manufacturing/{id}}: one tenant-owned order with its material snapshot, actuals and audit trail. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class OrderDetailsQuery {
    private final Store s;

    OrderDetailsQuery(Store s) {
        this.s = s;
    }

    public Map<String, Object> details(Actor a, String id) {
        var order = s.one(
                "SELECT o.*,i.name AS product,i.uom FROM manufacturing_orders o JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id WHERE o.tenant_id=? AND o.id=?",
                a.tenant(),
                id);
        return Map.of(
                "order",
                order,
                "materials",
                s.list(
                        "SELECT m.*,i.name,i.sku,i.uom FROM order_materials m JOIN items i ON i.tenant_id=m.tenant_id AND i.id=m.item_id WHERE m.tenant_id=? AND m.order_id=? ORDER BY i.name",
                        a.tenant(),
                        id),
                "actuals",
                s.list(
                        "SELECT * FROM production_actuals WHERE tenant_id=? AND order_id=? ORDER BY created_at",
                        a.tenant(),
                        id),
                "events",
                s.list(
                        "SELECT * FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at",
                        a.tenant(),
                        order.get("number")));
    }
}
