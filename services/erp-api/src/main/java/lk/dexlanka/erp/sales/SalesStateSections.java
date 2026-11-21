package lk.dexlanka.erp.sales;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateRequest;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/** {@code /api/state} keys owned by sales. */
@Component
class SalesStateSections implements StateContributor {
    private final Store s;

    SalesStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("sales", 200, this::sales));
    }

    private List<Map<String, Object>> sales(StateRequest r) {
        return s.list("""
                SELECT so.*,p.name AS customer,i.name AS product,i.uom
                FROM sales_orders so
                JOIN partners p ON p.tenant_id=so.tenant_id AND p.id=so.customer_id
                JOIN items i ON i.tenant_id=so.tenant_id AND i.id=so.product_id
                WHERE so.tenant_id=?
                ORDER BY so.due_date
                LIMIT 250""", r.tenant());
    }
}
