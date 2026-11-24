package lk.dexlanka.erp.procurement;

import static lk.dexlanka.erp.platform.Store.require;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Procurement read model: keyset-paged purchase orders (search, status filter, counts), the purchase-order detail
 * (lines, receipts with QC status, invoices with variances, supplier returns, activity), paged goods receipts, supplier
 * invoices and supplier returns, and the preferred supplier of items for planning.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ProcurementQuery {
    static final List<String> STATUSES =
            List.of("DRAFT", "SUBMITTED", "APPROVED", "PARTIAL", "RECEIVED", "CLOSED", "CANCELLED");

    private static final String FROM = """
            FROM purchase_orders po
            LEFT JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id
            """;

    /** Parameters: tenant, then the LIKE pattern (or null) six times. */
    private static final String SEARCH = """
            WHERE po.tenant_id=?
              AND (?::text IS NULL OR po.number ILIKE ? OR p.name ILIKE ? OR po.supplier_reference ILIKE ?
                   OR EXISTS(SELECT 1 FROM purchase_order_lines sl
                             JOIN items si ON si.tenant_id=sl.tenant_id AND si.id=sl.item_id
                             WHERE sl.tenant_id=po.tenant_id AND sl.purchase_order_id=po.id
                               AND (si.name ILIKE ? OR si.sku ILIKE ?)))
            """;

    private static final String PAGE = """
            SELECT po.id,po.number,po.status,po.supplier_id,p.name AS supplier,po.supplier_reference,po.due_date,
                   po.total,po.status_reason,po.created_at,po.created_by,po.submitted_by,po.submitted_at,po.approved_at,
                   agg.line_count,agg.lines_received,agg.quantity,agg.received,agg.returned,agg.uom,agg.items,
                   agg.progress,
                   (SELECT COUNT(*) FROM receipts r JOIN lots lt ON lt.tenant_id=r.tenant_id AND lt.id=r.lot_id
                    WHERE r.tenant_id=po.tenant_id AND r.purchase_order_id=po.id AND lt.status='QUARANTINE') AS awaiting_qc,
                   (SELECT COUNT(*) FROM supplier_invoices si
                    WHERE si.tenant_id=po.tenant_id AND si.purchase_order_id=po.id AND si.status='VARIANCE') AS variances
            """ + FROM + """
            LEFT JOIN LATERAL (
                SELECT COUNT(*) AS line_count,
                       COUNT(*) FILTER (WHERE l.received>=l.base_quantity) AS lines_received,
                       COALESCE(SUM(l.base_quantity),0) AS quantity,
                       COALESCE(SUM(l.received),0) AS received,
                       COALESCE(SUM(l.returned),0) AS returned,
                       COALESCE(round(100*AVG(l.received/l.base_quantity),2),0) AS progress,
                       CASE WHEN COUNT(DISTINCT i.uom)=1 THEN MIN(i.uom) END AS uom,
                       string_agg(i.name, ', ' ORDER BY l.line_no) AS items
                FROM purchase_order_lines l
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                WHERE l.tenant_id=po.tenant_id AND l.purchase_order_id=po.id) agg ON true
            """ + SEARCH + """
              AND (?::text IS NULL OR po.status=ANY(string_to_array(?,',')))
              AND (?::text IS NULL OR (po.created_at,po.id) <
                   (SELECT c.created_at,c.id FROM purchase_orders c WHERE c.tenant_id=po.tenant_id AND c.id=?))
            ORDER BY po.created_at DESC,po.id DESC
            LIMIT ?""";

    private static final String COUNTS = "SELECT po.status,COUNT(*) AS n " + FROM + SEARCH + "GROUP BY po.status";

    /** Receipt rows (list and detail): base quantities, QC decision, what can still be invoiced and returned. */
    private static final String RECEIPT_COLUMNS = """
            SELECT r.id,r.number,r.purchase_order_id,r.purchase_order_line_id,r.lot_id,r.quantity,r.entered_quantity,
                   r.entered_unit,r.delivery_note,r.unit_cost,r.value_amount,r.invoiced,r.returned,r.credited,
                   r.invoice_reference,r.created_at,
                   po.number AS purchase_order,p.name AS supplier,pl.line_no,i.name AS item,i.sku,i.uom,
                   lt.number AS lot,lt.status,w.name AS warehouse,w.code AS warehouse_code,
                   q.id AS inspection_id,q.status AS inspection_status,q.notes AS inspection_notes,q.inspected_by,
                   q.inspected_at,u.name AS created_by_name,
                   COALESCE(b.quantity-b.reserved,0) AS available,
                   CASE WHEN lt.status='RELEASED' THEN r.quantity-r.invoiced-(r.returned-r.credited) ELSE 0 END AS invoiceable,
                   CASE WHEN lt.status='RELEASED'
                        THEN GREATEST(0,LEAST(r.quantity-r.returned,COALESCE(b.quantity-b.reserved,0))) ELSE 0 END AS returnable
            FROM receipts r
            JOIN purchase_orders po ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id
            LEFT JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id
            JOIN purchase_order_lines pl ON pl.tenant_id=r.tenant_id AND pl.id=r.purchase_order_line_id
            JOIN items i ON i.tenant_id=pl.tenant_id AND i.id=pl.item_id
            JOIN lots lt ON lt.tenant_id=r.tenant_id AND lt.id=r.lot_id
            JOIN warehouses w ON w.tenant_id=lt.tenant_id AND w.id=lt.warehouse_id
            LEFT JOIN stock_balances b ON b.tenant_id=lt.tenant_id AND b.lot_id=lt.id
            LEFT JOIN LATERAL (SELECT * FROM inspections x WHERE x.tenant_id=r.tenant_id AND x.lot_id=r.lot_id
                               ORDER BY x.created_at,x.id LIMIT 1) q ON true
            LEFT JOIN users u ON u.id=r.created_by
            """;

    private static final String INVOICE_COLUMNS = """
            SELECT si.*,po.number AS purchase_order,p.name AS supplier,u.name AS created_by_name,
                   (SELECT COUNT(*) FROM supplier_invoice_lines l WHERE l.tenant_id=si.tenant_id AND l.invoice_id=si.id) AS line_count,
                   (SELECT COALESCE(SUM(l.quantity_variance),0) FROM supplier_invoice_lines l
                    WHERE l.tenant_id=si.tenant_id AND l.invoice_id=si.id) AS quantity_variance
            FROM supplier_invoices si
            JOIN purchase_orders po ON po.tenant_id=si.tenant_id AND po.id=si.purchase_order_id
            JOIN partners p ON p.tenant_id=si.tenant_id AND p.id=si.supplier_id
            LEFT JOIN users u ON u.id=si.created_by
            """;

    private static final String RETURN_COLUMNS = """
            SELECT sr.*,po.number AS purchase_order,p.name AS supplier,r.number AS receipt,lt.number AS lot,
                   pl.line_no,i.name AS item,i.sku,i.uom,u.name AS created_by_name
            FROM supplier_returns sr
            JOIN purchase_orders po ON po.tenant_id=sr.tenant_id AND po.id=sr.purchase_order_id
            JOIN partners p ON p.tenant_id=sr.tenant_id AND p.id=sr.supplier_id
            JOIN receipts r ON r.tenant_id=sr.tenant_id AND r.id=sr.receipt_id
            JOIN lots lt ON lt.tenant_id=sr.tenant_id AND lt.id=sr.lot_id
            JOIN purchase_order_lines pl ON pl.tenant_id=sr.tenant_id AND pl.id=sr.purchase_order_line_id
            JOIN items i ON i.tenant_id=sr.tenant_id AND i.id=sr.item_id
            LEFT JOIN users u ON u.id=sr.created_by
            """;

    private final Store s;

    ProcurementQuery(Store s) {
        this.s = s;
    }

    // ------------------------------------------------------------------ purchase orders

    /**
     * One page of purchase orders, newest first.
     *
     * @param status {@code all} (or blank), {@code open} (approved and partially received), {@code action} (drafts and
     *     orders awaiting approval) or one status code
     * @param q free text matched against number, supplier, supplier reference and line items
     * @param before cursor: the {@code next} value of the previous page
     */
    public Map<String, Object> page(Actor a, String status, String q, int limit, String before) {
        limit(limit);
        String statuses = statuses(status);
        String like = like(q);
        Object[] filter = {a.tenant(), like, like, like, like, like, like};
        String cursor = blank(before);
        var rows = s.list(PAGE, concat(filter, statuses, statuses, cursor, cursor, limit + 1));
        var more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        var counts = new LinkedHashMap<String, Object>();
        for (String st : STATUSES) counts.put(st, 0L);
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
                SELECT po.*,p.name AS supplier,p.email AS supplier_email,p.phone AS supplier_phone,
                       p.payment_days,p.active AS supplier_active,
                       cu.name AS created_by_name,su.name AS submitted_by_name,au.name AS approved_by_name,
                       cl.name AS closed_by_name
                FROM purchase_orders po
                LEFT JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id
                LEFT JOIN users cu ON cu.id=po.created_by
                LEFT JOIN users su ON su.id=po.submitted_by
                LEFT JOIN users au ON au.id=po.approved_by
                LEFT JOIN users cl ON cl.id=po.closed_by
                WHERE po.tenant_id=? AND po.id=?""", t, id);
        var lines = s.list("""
                SELECT l.*,i.sku,i.name AS item,i.uom,i.type AS item_type,i.active AS item_active,
                       u.decimals AS unit_decimals,bu.decimals AS base_decimals,
                       COALESCE(l.expected_date,po.due_date) AS expected,
                       l.base_quantity-l.received AS outstanding,
                       round(l.received/l.factor,6) AS received_in_unit,
                       COALESCE((SELECT SUM(r.quantity) FROM receipts r JOIN lots lt ON lt.tenant_id=r.tenant_id AND lt.id=r.lot_id
                                 WHERE r.tenant_id=l.tenant_id AND r.purchase_order_line_id=l.id
                                   AND lt.status='QUARANTINE'),0) AS awaiting_qc,
                       COALESCE((SELECT SUM(r.invoiced) FROM receipts r
                                 WHERE r.tenant_id=l.tenant_id AND r.purchase_order_line_id=l.id),0) AS invoiced
                FROM purchase_order_lines l
                JOIN purchase_orders po ON po.tenant_id=l.tenant_id AND po.id=l.purchase_order_id
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                JOIN units u ON u.tenant_id=l.tenant_id AND u.code=l.unit
                JOIN units bu ON bu.tenant_id=i.tenant_id AND bu.code=i.uom
                WHERE l.tenant_id=? AND l.purchase_order_id=?
                ORDER BY l.line_no""", t, id);
        var receipts = s.list(
                RECEIPT_COLUMNS
                        + " WHERE r.tenant_id=? AND r.purchase_order_id=? ORDER BY r.created_at,pl.line_no,r.id",
                t,
                id);
        var invoices = s.list(
                INVOICE_COLUMNS + " WHERE si.tenant_id=? AND si.purchase_order_id=? ORDER BY si.created_at,si.id",
                t,
                id);
        var invoiceLines = s.list("""
                SELECT l.*,r.number AS receipt,lt.number AS lot,pl.line_no AS order_line_no,i.name AS item,i.sku,i.uom
                FROM supplier_invoice_lines l
                JOIN receipts r ON r.tenant_id=l.tenant_id AND r.id=l.receipt_id
                JOIN lots lt ON lt.tenant_id=r.tenant_id AND lt.id=r.lot_id
                JOIN purchase_order_lines pl ON pl.tenant_id=l.tenant_id AND pl.id=l.purchase_order_line_id
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                WHERE l.tenant_id=? AND l.purchase_order_id=?
                ORDER BY l.invoice_id,l.line_no""", t, id);
        var returns = s.list(
                RETURN_COLUMNS + " WHERE sr.tenant_id=? AND sr.purchase_order_id=? ORDER BY sr.created_at,sr.id",
                t,
                id);
        var activity = s.list("""
                SELECT e.* FROM audit_events e
                WHERE e.tenant_id=?
                  AND (e.reference=?
                       OR e.reference IN (SELECT r.lot_id FROM receipts r WHERE r.tenant_id=? AND r.purchase_order_id=?)
                       OR (e.action='INVOICE_MATCHED' AND e.reference IN
                           (SELECT si.supplier_reference FROM supplier_invoices si
                            WHERE si.tenant_id=? AND si.purchase_order_id=?)))
                ORDER BY e.created_at,e.id
                LIMIT 200""", t, order.get("number"), t, id, t, id);
        var warehouses = s.list(
                "SELECT id,code,name FROM warehouses WHERE tenant_id=? AND kind='RAW' AND active ORDER BY name,id", t);
        var out = new LinkedHashMap<String, Object>();
        out.put("order", order);
        out.put("lines", lines);
        out.put("receipts", receipts);
        out.put("invoices", invoices);
        out.put("invoiceLines", invoiceLines);
        out.put("returns", returns);
        out.put("activity", activity);
        out.put("warehouses", warehouses);
        return out;
    }

    // ------------------------------------------------------------------ receipts, invoices, returns

    /**
     * Goods receipts, newest first.
     *
     * @param status {@code all}, {@code QUARANTINE}, {@code RELEASED}, {@code REJECTED} (the lot's quality status) or
     *     {@code uninvoiced} (released with a quantity still to invoice)
     */
    public Map<String, Object> receipts(Actor a, String status, String q, int limit, String before) {
        limit(limit);
        String filter = status == null || status.isBlank() ? "all" : status;
        require(
                Set.of("all", "QUARANTINE", "RELEASED", "REJECTED", "uninvoiced")
                        .contains(filter),
                "Unknown receipt status filter");
        String lot = Set.of("QUARANTINE", "RELEASED", "REJECTED").contains(filter) ? filter : null;
        boolean uninvoiced = filter.equals("uninvoiced");
        String like = like(q);
        String cursor = blank(before);
        var rows = s.list(
                RECEIPT_COLUMNS + """
                        WHERE r.tenant_id=?
                          AND (?::text IS NULL OR r.number ILIKE ? OR r.delivery_note ILIKE ? OR po.number ILIKE ?
                               OR p.name ILIKE ? OR lt.number ILIKE ? OR i.name ILIKE ? OR i.sku ILIKE ?)
                          AND (?::text IS NULL OR lt.status=?)
                          AND (NOT ? OR (lt.status='RELEASED' AND r.quantity-r.invoiced-(r.returned-r.credited)>0))
                          AND (?::text IS NULL OR (r.created_at,r.id) <
                               (SELECT c.created_at,c.id FROM receipts c WHERE c.tenant_id=r.tenant_id AND c.id=?))
                        ORDER BY r.created_at DESC,r.id DESC
                        LIMIT ?""",
                a.tenant(),
                like,
                like,
                like,
                like,
                like,
                like,
                like,
                like,
                lot,
                lot,
                uninvoiced,
                cursor,
                cursor,
                limit + 1);
        return pageOf(rows, limit);
    }

    /** Supplier invoices, newest first; {@code status} is {@code all}, {@code MATCHED} or {@code VARIANCE}. */
    public Map<String, Object> invoices(Actor a, String status, String q, int limit, String before) {
        limit(limit);
        String filter = status == null || status.isBlank() || status.equalsIgnoreCase("all")
                ? null
                : status.toUpperCase(Locale.ROOT);
        require(filter == null || Set.of("MATCHED", "VARIANCE").contains(filter), "Unknown invoice status filter");
        String like = like(q);
        String cursor = blank(before);
        var rows = s.list(
                INVOICE_COLUMNS + """
                        WHERE si.tenant_id=?
                          AND (?::text IS NULL OR si.number ILIKE ? OR si.supplier_reference ILIKE ? OR po.number ILIKE ?
                               OR p.name ILIKE ?)
                          AND (?::text IS NULL OR si.status=?)
                          AND (?::text IS NULL OR (si.created_at,si.id) <
                               (SELECT c.created_at,c.id FROM supplier_invoices c WHERE c.tenant_id=si.tenant_id AND c.id=?))
                        ORDER BY si.created_at DESC,si.id DESC
                        LIMIT ?""",
                a.tenant(),
                like,
                like,
                like,
                like,
                like,
                filter,
                filter,
                cursor,
                cursor,
                limit + 1);
        return pageOf(rows, limit);
    }

    /** Supplier returns, newest first. */
    public Map<String, Object> returns(Actor a, String q, int limit, String before) {
        limit(limit);
        String like = like(q);
        String cursor = blank(before);
        var rows = s.list(
                RETURN_COLUMNS + """
                        WHERE sr.tenant_id=?
                          AND (?::text IS NULL OR sr.number ILIKE ? OR sr.debit_note ILIKE ? OR po.number ILIKE ?
                               OR p.name ILIKE ? OR lt.number ILIKE ? OR i.name ILIKE ? OR i.sku ILIKE ?)
                          AND (?::text IS NULL OR (sr.created_at,sr.id) <
                               (SELECT c.created_at,c.id FROM supplier_returns c WHERE c.tenant_id=sr.tenant_id AND c.id=?))
                        ORDER BY sr.created_at DESC,sr.id DESC
                        LIMIT ?""",
                a.tenant(),
                like,
                like,
                like,
                like,
                like,
                like,
                like,
                like,
                cursor,
                cursor,
                limit + 1);
        return pageOf(rows, limit);
    }

    /**
     * Where each item would be bought: its preferred supplier (the active supplier it was last ordered from, drafts and
     * cancelled orders ignored) with that order line's base unit cost, plus the item's standard cost and lead time.
     */
    public Map<String, Object> sources(Actor a, String items) {
        String ids = items == null ? "" : items.strip();
        require(!ids.isEmpty() && ids.split(",").length <= 200, "Name between 1 and 200 items");
        var rows = s.list("""
                SELECT i.id AS item_id,i.sku,i.name,i.uom,i.standard_cost,i.lead_time_days,
                       src.supplier_id,src.supplier,src.base_unit_cost,src.ordered_at
                FROM items i
                LEFT JOIN LATERAL (
                    SELECT po.supplier_id,p.name AS supplier,l.base_unit_cost,po.created_at AS ordered_at
                    FROM purchase_order_lines l
                    JOIN purchase_orders po ON po.tenant_id=l.tenant_id AND po.id=l.purchase_order_id
                    JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id
                    WHERE l.tenant_id=i.tenant_id AND l.item_id=i.id AND po.status NOT IN ('CANCELLED','DRAFT')
                      AND p.active
                    ORDER BY po.created_at DESC,po.id DESC,l.line_no
                    LIMIT 1) src ON true
                WHERE i.tenant_id=? AND i.id=ANY(string_to_array(?,','))
                ORDER BY i.name""", a.tenant(), ids);
        return Map.of("items", rows);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> pageOf(List<Map<String, Object>> rows, int limit) {
        var more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        return out;
    }

    private static void limit(int limit) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
    }

    private static String statuses(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("all")) return null;
        if (status.equalsIgnoreCase("open")) return "APPROVED,PARTIAL";
        if (status.equalsIgnoreCase("action")) return "DRAFT,SUBMITTED";
        String code = status.toUpperCase(Locale.ROOT);
        require(STATUSES.contains(code), "Unknown purchase order status filter");
        return code;
    }

    private static String like(String q) {
        return q == null || q.isBlank()
                ? null
                : "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Object[] concat(Object[] head, Object... tail) {
        var out = new Object[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
