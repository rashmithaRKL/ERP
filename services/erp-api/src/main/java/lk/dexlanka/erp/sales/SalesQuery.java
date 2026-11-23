package lk.dexlanka.erp.sales;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/sales/orders} (keyset-paged list with search and status filter) and {@code GET
 * /api/sales/orders/{id}} (header, lines, deliveries, returns, linked manufacturing orders and activity).
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SalesQuery {
    static final Set<String> STATUSES = Set.of("DRAFT", "CONFIRMED", "PARTIAL", "DELIVERED", "CLOSED", "CANCELLED");

    private static final String FROM = """
            FROM sales_orders so
            JOIN partners p ON p.tenant_id=so.tenant_id AND p.id=so.customer_id
            """;

    /** Parameters: tenant, then the LIKE pattern (or null) six times. */
    private static final String SEARCH = """
            WHERE so.tenant_id=?
              AND (?::text IS NULL OR so.number ILIKE ? OR p.name ILIKE ? OR so.customer_reference ILIKE ?
                   OR EXISTS(SELECT 1 FROM sales_order_lines sl
                             JOIN items si ON si.tenant_id=sl.tenant_id AND si.id=sl.product_id
                             WHERE sl.tenant_id=so.tenant_id AND sl.sales_order_id=so.id
                               AND (si.name ILIKE ? OR si.sku ILIKE ?)))
            """;

    private static final String PAGE = """
            SELECT so.id,so.number,so.status,so.customer_id,p.name AS customer,so.customer_reference,so.due_date,
                   so.subtotal,so.discount_total,so.total,so.status_reason,so.created_at,so.confirmed_at,
                   agg.line_count,agg.quantity,agg.delivered,agg.returned,agg.uom,agg.products
            """ + FROM + """
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
            """ + SEARCH + """
              AND (?::text IS NULL OR so.status=ANY(string_to_array(?,',')))
              AND (?::text IS NULL OR (so.created_at,so.id) <
                   (SELECT c.created_at,c.id FROM sales_orders c WHERE c.tenant_id=so.tenant_id AND c.id=?))
            ORDER BY so.created_at DESC,so.id DESC
            LIMIT ?""";

    private static final String COUNTS = "SELECT so.status,COUNT(*) AS n " + FROM + SEARCH + "GROUP BY so.status";

    private final Store s;

    SalesQuery(Store s) {
        this.s = s;
    }

    /**
     * One page of sales orders, newest first.
     *
     * @param status {@code all} (or blank), {@code open} (confirmed and partially delivered) or one status code
     * @param q free text matched against number, customer, customer reference and line products
     * @param before cursor: the {@code next} value of the previous page
     */
    public Map<String, Object> page(Actor a, String status, String q, int limit, String before) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        String statuses = statuses(status);
        String like = q == null || q.isBlank() ? null : "%" + escape(q.strip()) + "%";
        Object[] filter = {a.tenant(), like, like, like, like, like, like};
        String cursor = before == null || before.isBlank() ? null : before;
        var rows = s.list(PAGE, concat(filter, statuses, statuses, cursor, cursor, limit + 1));
        var more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        var counts = new LinkedHashMap<String, Object>();
        for (String st : new String[] {"DRAFT", "CONFIRMED", "PARTIAL", "DELIVERED", "CLOSED", "CANCELLED"})
            counts.put(st, 0L);
        for (var r : s.list(COUNTS, filter)) counts.put((String) r.get("status"), r.get("n"));
        long total = statuses == null
                ? counts.values().stream()
                        .mapToLong(v -> ((Number) v).longValue())
                        .sum()
                : Set.of(statuses.split(",")).stream()
                        .mapToLong(st -> ((Number) counts.get(st)).longValue())
                        .sum();
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        out.put("total", total);
        out.put("counts", counts);
        return out;
    }

    public Map<String, Object> detail(Actor a, String id) {
        String t = a.tenant();
        var order = s.one("""
                SELECT so.*,p.name AS customer,p.email AS customer_email,p.phone AS customer_phone,
                       p.billing_address,p.shipping_address,p.payment_days,p.credit_limit,p.active AS customer_active,
                       cu.name AS created_by_name,cf.name AS confirmed_by_name,cl.name AS closed_by_name
                FROM sales_orders so
                JOIN partners p ON p.tenant_id=so.tenant_id AND p.id=so.customer_id
                LEFT JOIN users cu ON cu.id=so.created_by
                LEFT JOIN users cf ON cf.id=so.confirmed_by
                LEFT JOIN users cl ON cl.id=so.closed_by
                WHERE so.tenant_id=? AND so.id=?""", t, id);
        var lines = s.list("""
                SELECT l.*,i.sku,i.name AS product,i.uom,i.active AS product_active,
                       l.quantity-l.delivered AS outstanding,
                       COALESCE((SELECT SUM(o.quantity-o.scrap_qty) FROM manufacturing_orders o
                                 WHERE o.tenant_id=l.tenant_id AND o.sales_order_line_id=l.id
                                   AND o.status<>'CANCELLED'),0) AS planned,
                       COALESCE((SELECT SUM(b.quantity-b.reserved) FROM lots lt
                                 JOIN stock_balances b ON b.tenant_id=lt.tenant_id AND b.lot_id=lt.id
                                 WHERE lt.tenant_id=l.tenant_id AND lt.item_id=l.product_id
                                   AND lt.status='RELEASED'),0) AS available
                FROM sales_order_lines l
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.product_id
                WHERE l.tenant_id=? AND l.sales_order_id=?
                ORDER BY l.line_no""", t, id);
        var deliveries = s.list("""
                SELECT d.*,lt.number AS lot,sol.line_no,i.name AS product,i.sku,i.uom,d.quantity-d.returned AS returnable,
                       u.name AS created_by_name
                FROM deliveries d
                JOIN lots lt ON lt.tenant_id=d.tenant_id AND lt.id=d.lot_id
                JOIN items i ON i.tenant_id=lt.tenant_id AND i.id=lt.item_id
                LEFT JOIN sales_order_lines sol ON sol.tenant_id=d.tenant_id AND sol.id=d.sales_order_line_id
                LEFT JOIN users u ON u.id=d.created_by
                WHERE d.tenant_id=? AND d.sales_order_id=?
                ORDER BY d.created_at,d.id""", t, id);
        var returns = s.list("""
                SELECT r.*,lt.number AS lot,q.status AS inspection_status,q.notes AS inspection_notes,
                       q.inspected_by,q.inspected_at,d.reference AS delivery_reference,sol.line_no,
                       i.name AS product,i.sku,i.uom,u.name AS created_by_name,
                       CASE q.status WHEN 'PENDING' THEN 'AWAITING_QC' WHEN 'PASSED' THEN 'RESTOCKED' ELSE 'REJECTED' END AS status
                FROM customer_returns r
                JOIN lots lt ON lt.tenant_id=r.tenant_id AND lt.id=r.lot_id
                JOIN inspections q ON q.tenant_id=r.tenant_id AND q.id=r.inspection_id
                JOIN deliveries d ON d.tenant_id=r.tenant_id AND d.id=r.delivery_id
                JOIN sales_order_lines sol ON sol.tenant_id=r.tenant_id AND sol.id=r.sales_order_line_id
                JOIN items i ON i.tenant_id=sol.tenant_id AND i.id=sol.product_id
                LEFT JOIN users u ON u.id=r.created_by
                WHERE r.tenant_id=? AND r.sales_order_id=?
                ORDER BY r.created_at,r.id""", t, id);
        var manufacturing = s.list("""
                SELECT o.id,o.number,o.status,o.quantity,o.good_qty,o.scrap_qty,o.due_date,o.sales_order_line_id,
                       sol.line_no,i.name AS product,i.uom
                FROM manufacturing_orders o
                JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id
                LEFT JOIN sales_order_lines sol ON sol.tenant_id=o.tenant_id AND sol.id=o.sales_order_line_id
                WHERE o.tenant_id=? AND o.sales_order_id=?
                ORDER BY o.created_at,o.number""", t, id);
        var lots = s.list("""
                SELECT lt.id,lt.number,lt.item_id,lt.created_at,b.quantity-b.reserved AS available,w.name AS warehouse
                FROM lots lt
                JOIN stock_balances b ON b.tenant_id=lt.tenant_id AND b.lot_id=lt.id
                JOIN warehouses w ON w.tenant_id=lt.tenant_id AND w.id=lt.warehouse_id
                WHERE lt.tenant_id=? AND lt.status='RELEASED' AND b.quantity>b.reserved
                  AND lt.item_id IN (SELECT product_id FROM sales_order_lines
                                     WHERE tenant_id=? AND sales_order_id=? AND delivered<quantity)
                ORDER BY lt.created_at,lt.id
                LIMIT 200""", t, t, id);
        var activity = s.list("""
                SELECT e.* FROM audit_events e
                WHERE e.tenant_id=?
                  AND (e.reference=?
                       OR (e.action='DISPATCH_POSTED' AND e.reference IN
                           (SELECT d.reference FROM deliveries d WHERE d.tenant_id=? AND d.sales_order_id=?))
                       OR e.reference IN (SELECT r.lot_id FROM customer_returns r WHERE r.tenant_id=? AND r.sales_order_id=?))
                ORDER BY e.created_at,e.id
                LIMIT 200""", t, order.get("number"), t, id, t, id);
        BigDecimal receivables = s.db().queryForObject(
                        SalesService.RECEIVABLES_SQL,
                        BigDecimal.class,
                        t,
                        order.get("customer_id"),
                        t,
                        order.get("customer_id"));
        var credit = new LinkedHashMap<String, Object>();
        credit.put("limit", order.get("credit_limit"));
        credit.put("receivables", receivables);
        credit.put(
                "exceeded",
                order.get("credit_limit") != null
                        && receivables
                                        .add(
                                                order.get("status").equals("DRAFT")
                                                        ? dec(order.get("total"))
                                                        : BigDecimal.ZERO)
                                        .compareTo(dec(order.get("credit_limit")))
                                > 0);
        var out = new LinkedHashMap<String, Object>();
        out.put("order", order);
        out.put("lines", lines);
        out.put("deliveries", deliveries);
        out.put("returns", returns);
        out.put("manufacturing", manufacturing);
        out.put("lots", lots);
        out.put("activity", activity);
        out.put("credit", credit);
        return out;
    }

    private static String statuses(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("all")) return null;
        if (status.equalsIgnoreCase("open")) return "CONFIRMED,PARTIAL";
        String code = status.toUpperCase(Locale.ROOT);
        require(STATUSES.contains(code), "Unknown sales order status filter");
        return code;
    }

    static String escape(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Object[] concat(Object[] head, Object... tail) {
        var out = new Object[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
