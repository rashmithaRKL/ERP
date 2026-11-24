package lk.dexlanka.erp.manufacturing;

import static lk.dexlanka.erp.platform.Store.require;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/manufacturing/orders} (V9): manufacturing orders keyset-paged ({@code created_at DESC, id DESC}) with
 * a status filter and free-text search, for the Manufacturing orders list and the Shop floor. Rows carry the same
 * columns as the {@code orders} key of {@code /api/state} plus {@code parent_number}.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ManufacturingQuery {
    static final List<String> STATUSES = List.of(
            "DRAFT",
            "APPROVED",
            "MATERIALS_RESERVED",
            "RELEASED",
            "IN_PROGRESS",
            "ON_HOLD",
            "PARTIALLY_COMPLETED",
            "QUALITY_REVIEW",
            "COMPLETED",
            "CLOSED",
            "CANCELLED");

    /**
     * Status groups accepted by the filter besides single codes: {@code active} (not completed, closed or cancelled),
     * {@code floor} (released work an operator can see on the shop floor) and {@code production} (running).
     */
    static final Map<String, String> GROUPS = Map.of(
            "active",
            "DRAFT,APPROVED,MATERIALS_RESERVED,RELEASED,IN_PROGRESS,ON_HOLD,PARTIALLY_COMPLETED,QUALITY_REVIEW",
            "floor",
            "RELEASED,IN_PROGRESS,ON_HOLD,PARTIALLY_COMPLETED,QUALITY_REVIEW",
            "production",
            "IN_PROGRESS,PARTIALLY_COMPLETED");

    private static final String FROM = """
             FROM manufacturing_orders o
            JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id
            JOIN machines m ON m.tenant_id=o.tenant_id AND m.id=o.machine_id
            LEFT JOIN sales_orders so ON so.tenant_id=o.tenant_id AND so.id=o.sales_order_id
            LEFT JOIN partners p ON p.tenant_id=so.tenant_id AND p.id=so.customer_id
            LEFT JOIN manufacturing_orders po ON po.tenant_id=o.tenant_id AND po.id=o.parent_order_id
            WHERE o.tenant_id=?
              AND (?::text IS NULL OR o.status=ANY(string_to_array(?,',')))
              AND (?::text IS NULL OR o.number ILIKE ? OR i.name ILIKE ? OR i.sku ILIKE ? OR m.code ILIKE ?
                   OR p.name ILIKE ?)
            """;

    private final Store s;

    ManufacturingQuery(Store s) {
        this.s = s;
    }

    /**
     * One page of orders, newest first: {@code {items, next, total, counts}}. {@code total} counts the orders matching
     * the filter; {@code counts} are the tenant's orders per status (unfiltered, for summary figures).
     *
     * @param status {@code all} (or blank), a group of {@link #GROUPS} or one status code
     * @param q matched against order number, product name/SKU, work center code and customer
     * @param before cursor: the {@code next} value of the previous page
     */
    public Map<String, Object> page(Actor a, String status, String q, int limit, String before) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        String statuses = statuses(status);
        String like = like(q);
        String cursor = before == null || before.isBlank() ? null : before;
        Object[] filter = {a.tenant(), statuses, statuses, like, like, like, like, like, like};
        var rows = s.list(
                "SELECT o.*,i.name AS product,i.sku,i.uom,m.code AS machine,p.name AS customer,po.number AS parent_number"
                        + FROM
                        + """
                  AND (?::text IS NULL OR (o.created_at,o.id) <
                       (SELECT c.created_at,c.id FROM manufacturing_orders c WHERE c.tenant_id=o.tenant_id AND c.id=?))
                ORDER BY o.created_at DESC,o.id DESC
                LIMIT ?""",
                concat(filter, cursor, cursor, limit + 1));
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        long total = s.db().queryForObject("SELECT COUNT(*)" + FROM, Long.class, filter);
        var counts = new LinkedHashMap<String, Object>();
        for (String st : STATUSES) counts.put(st, 0L);
        for (var r : s.list(
                "SELECT status,COUNT(*) AS n FROM manufacturing_orders WHERE tenant_id=? GROUP BY status", a.tenant()))
            counts.put((String) r.get("status"), r.get("n"));
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        out.put("total", total);
        out.put("counts", counts);
        return out;
    }

    static String statuses(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("all")) return null;
        String group = GROUPS.get(status.toLowerCase(Locale.ROOT));
        if (group != null) return group;
        String code = status.toUpperCase(Locale.ROOT);
        require(STATUSES.contains(code), "Unknown manufacturing order status filter");
        return code;
    }

    static String like(String q) {
        if (q == null || q.isBlank()) return null;
        return "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static Object[] concat(Object[] head, Object... tail) {
        var out = new Object[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
