package lk.dexlanka.erp.quality;

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
 * {@code GET /api/quality/inspections} (V9): quality inspections keyset-paged ({@code created_at DESC, id DESC}) with
 * status, type and text filters. Rows carry the columns of the {@code inspections} key of {@code /api/state} plus the
 * item SKU and unit and the source document number (manufacturing order, purchase order or sales order).
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InspectionQuery {
    static final List<String> STATUSES = List.of("PENDING", "PASSED", "FAILED");
    static final List<String> TYPES = List.of("INCOMING", "FINAL", "RETURN", "OPENING", "REWORK");

    private static final String FROM = """
             FROM inspections q
            JOIN lots l ON l.tenant_id=q.tenant_id AND l.id=q.lot_id
            JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
            LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
            WHERE q.tenant_id=?
              AND (?::text IS NULL OR q.status=?)
              AND (?::text IS NULL OR q.type=?)
              AND (?::text IS NULL OR l.number ILIKE ? OR i.name ILIKE ? OR i.sku ILIKE ? OR q.notes ILIKE ?)
            """;

    private final Store s;

    InspectionQuery(Store s) {
        this.s = s;
    }

    /**
     * One page of inspections, newest first: {@code {items, next, total, counts}}. {@code total} counts the inspections
     * matching the filter; {@code counts} are the tenant's inspections per status (unfiltered, for summary figures).
     *
     * @param status {@code all} (or blank) or PENDING, PASSED, FAILED
     * @param type blank or INCOMING, FINAL, RETURN, OPENING, REWORK
     * @param q matched against lot number, item name/SKU and inspection notes
     */
    public Map<String, Object> page(Actor a, String status, String type, String q, int limit, String before) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        String st = code(status, STATUSES, "Unknown inspection status filter");
        String ty = code(type, TYPES, "Unknown inspection type filter");
        String like = q == null || q.isBlank()
                ? null
                : "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String cursor = before == null || before.isBlank() ? null : before;
        Object[] filter = {a.tenant(), st, st, ty, ty, like, like, like, like, like};
        var args = new Object[filter.length + 3];
        System.arraycopy(filter, 0, args, 0, filter.length);
        args[filter.length] = cursor;
        args[filter.length + 1] = cursor;
        args[filter.length + 2] = limit + 1;
        var rows = s.list("""
                SELECT q.*,l.number AS lot,l.source_id,l.source_type,l.status AS lot_status,i.name AS item,i.sku,
                       i.uom,COALESCE(b.quantity,0) AS quantity,
                       CASE l.source_type
                         WHEN 'MANUFACTURING' THEN (SELECT o.number FROM manufacturing_orders o
                                                    WHERE o.tenant_id=l.tenant_id AND o.id=l.source_id)
                         WHEN 'PURCHASE' THEN (SELECT po.number FROM receipts r
                                               JOIN purchase_orders po ON po.tenant_id=r.tenant_id
                                                                      AND po.id=r.purchase_order_id
                                               WHERE r.tenant_id=l.tenant_id AND r.lot_id=l.id LIMIT 1)
                         WHEN 'CUSTOMER_RETURN' THEN (SELECT so.number FROM customer_returns cr
                                                      JOIN sales_orders so ON so.tenant_id=cr.tenant_id
                                                                          AND so.id=cr.sales_order_id
                                                      WHERE cr.tenant_id=l.tenant_id AND cr.lot_id=l.id LIMIT 1)
                         WHEN 'REWORK' THEN (SELECT rw.number FROM rework_orders rw
                                             WHERE rw.tenant_id=l.tenant_id AND rw.id=l.source_id)
                       END AS source_number,
                       CASE WHEN q.status='PENDING' THEN applicable_inspection_plan(q.tenant_id,l.item_id,q.type)
                       END AS plan_id,
                       (SELECT n.id FROM ncrs n WHERE n.tenant_id=q.tenant_id AND n.inspection_id=q.id
                          AND n.source='INSPECTION') AS ncr_id,
                       (SELECT n.number FROM ncrs n WHERE n.tenant_id=q.tenant_id AND n.inspection_id=q.id
                          AND n.source='INSPECTION') AS ncr_number""" + FROM + """
                  AND (?::text IS NULL OR (q.created_at,q.id) <
                       (SELECT c.created_at,c.id FROM inspections c WHERE c.tenant_id=q.tenant_id AND c.id=?))
                ORDER BY q.created_at DESC,q.id DESC
                LIMIT ?""", args);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        long total = s.db().queryForObject("SELECT COUNT(*)" + FROM, Long.class, filter);
        var counts = new LinkedHashMap<String, Object>();
        for (String code : STATUSES) counts.put(code, 0L);
        for (var r :
                s.list("SELECT status,COUNT(*) AS n FROM inspections WHERE tenant_id=? GROUP BY status", a.tenant()))
            counts.put((String) r.get("status"), r.get("n"));
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        out.put("total", total);
        out.put("counts", counts);
        return out;
    }

    private static String code(String value, List<String> allowed, String message) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("all")) return null;
        String code = value.toUpperCase(Locale.ROOT);
        require(allowed.contains(code), message);
        return code;
    }
}
