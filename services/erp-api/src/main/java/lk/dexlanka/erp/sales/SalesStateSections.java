package lk.dexlanka.erp.sales;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateRequest;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/**
 * {@code /api/state} keys owned by sales: {@code sales} (one summary row per order, for exports and pickers) and
 * {@code salesLines} (open lines of confirmed orders, for planning manufacturing against a customer line). Pages use
 * {@code GET /api/sales/orders} for the full, paginated documents.
 */
@Component
class SalesStateSections implements StateContributor {
    private final Store s;

    SalesStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(
                StateSection.of("sales", 200, this::sales), StateSection.of("salesLines", 1300, this::salesLines));
    }

    private List<Map<String, Object>> sales(StateRequest r) {
        return s.list("""
                SELECT so.id,so.number,so.status,so.customer_id,p.name AS customer,so.customer_reference,so.due_date,
                       so.total,so.created_at,agg.line_count,agg.products AS product,agg.uom,
                       agg.quantity,agg.delivered,agg.returned
                FROM sales_orders so
                JOIN partners p ON p.tenant_id=so.tenant_id AND p.id=so.customer_id
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS line_count,
                           COALESCE(SUM(l.quantity),0) AS quantity,
                           COALESCE(SUM(l.delivered),0) AS delivered,
                           COALESCE(SUM(l.returned),0) AS returned,
                           CASE WHEN COUNT(DISTINCT i.uom)=1 THEN MIN(i.uom) END AS uom,
                           string_agg(i.name, ', ' ORDER BY l.line_no) AS products
                    FROM sales_order_lines l
                    JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.product_id
                    WHERE l.tenant_id=so.tenant_id AND l.sales_order_id=so.id) agg ON true
                WHERE so.tenant_id=?
                ORDER BY so.due_date,so.number
                LIMIT 250""", r.tenant());
    }

    private List<Map<String, Object>> salesLines(StateRequest r) {
        return s.list("""
                SELECT l.id,l.sales_order_id,so.number,so.status,so.due_date,p.name AS customer,l.line_no,
                       l.product_id,i.name AS product,i.sku,i.uom,l.quantity,l.delivered,
                       COALESCE((SELECT SUM(o.quantity-o.scrap_qty) FROM manufacturing_orders o
                                 WHERE o.tenant_id=l.tenant_id AND o.sales_order_line_id=l.id
                                   AND o.status<>'CANCELLED'),0) AS planned
                FROM sales_order_lines l
                JOIN sales_orders so ON so.tenant_id=l.tenant_id AND so.id=l.sales_order_id
                JOIN partners p ON p.tenant_id=so.tenant_id AND p.id=so.customer_id
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.product_id
                WHERE l.tenant_id=? AND so.status IN ('CONFIRMED','PARTIAL') AND l.delivered<l.quantity
                ORDER BY so.due_date,so.number,l.line_no
                LIMIT 250""", r.tenant());
    }
}
