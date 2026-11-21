package lk.dexlanka.erp.manufacturing;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateRequest;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/** {@code /api/state} keys owned by manufacturing. */
@Component
class ManufacturingStateSections implements StateContributor {
    private final Store s;

    ManufacturingStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("orders", 170, this::orders));
    }

    private List<Map<String, Object>> orders(StateRequest r) {
        return s.list("""
                SELECT o.*,i.name AS product,i.sku,i.uom,m.code AS machine,p.name AS customer
                FROM manufacturing_orders o
                JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id
                JOIN machines m ON m.tenant_id=o.tenant_id AND m.id=o.machine_id
                LEFT JOIN sales_orders so ON so.tenant_id=o.tenant_id AND so.id=o.sales_order_id
                LEFT JOIN partners p ON p.tenant_id=so.tenant_id AND p.id=so.customer_id
                WHERE o.tenant_id=?
                ORDER BY o.created_at DESC,o.number
                LIMIT 250""", r.tenant());
    }
}
