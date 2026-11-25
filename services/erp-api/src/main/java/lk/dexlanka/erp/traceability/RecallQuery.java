package lk.dexlanka.erp.traceability;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Recall read model (V14): keyset-paged list, detail with the affected snapshot, notification CSV. Every role reads. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RecallQuery {
    static final List<String> STATUSES = List.of("OPEN", "CONTAINED", "CLOSED", "CANCELLED");
    static final List<String> SEVERITIES = List.of("MINOR", "MAJOR", "CRITICAL");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Store s;
    private final RecallService recalls;

    RecallQuery(Store s, RecallService recalls) {
        this.s = s;
        this.recalls = recalls;
    }

    /**
     * Recalls newest first. {@code status}: OPEN, CONTAINED, CLOSED, CANCELLED, {@code active} (open or contained) or
     * all; {@code severity}; {@code q} matches number, title, reason and suspect lot numbers.
     */
    public Map<String, Object> page(Actor a, String status, String severity, String q, int limit, String before) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        String st = status == null || status.isBlank() || status.equalsIgnoreCase("all")
                ? null
                : status.strip().toUpperCase(Locale.ROOT);
        require(st == null || st.equals("ACTIVE") || STATUSES.contains(st), "Unknown recall status");
        String sev = severity == null || severity.isBlank() || severity.equalsIgnoreCase("all")
                ? null
                : severity.strip().toUpperCase(Locale.ROOT);
        require(sev == null || SEVERITIES.contains(sev), "Unknown severity");
        String like = q == null || q.isBlank()
                ? null
                : "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String cursor = before == null || before.isBlank() ? null : before;
        // Leading and trailing spaces are explicit: text blocks strip incidental whitespace.
        String from = " "
                + """
                 FROM recalls r WHERE r.tenant_id=?
                  AND (?::text IS NULL OR (?='ACTIVE' AND r.status IN ('OPEN','CONTAINED')) OR r.status=?)
                  AND (?::text IS NULL OR r.severity=?)
                  AND (?::text IS NULL OR r.number ILIKE ? OR r.title ILIKE ? OR r.reason ILIKE ?
                       OR EXISTS (SELECT 1 FROM recall_suspects x JOIN lots l ON l.tenant_id=x.tenant_id AND l.id=x.lot_id
                                  WHERE x.tenant_id=r.tenant_id AND x.recall_id=r.id AND l.number ILIKE ?))""";
        var filter = new ArrayList<Object>(List.of(a.tenant()));
        filter.addAll(java.util.Arrays.asList(st, st, st, sev, sev, like, like, like, like, like));
        var args = new ArrayList<>(filter);
        args.add(cursor);
        args.add(cursor);
        args.add(limit + 1);
        var rows = s.list(
                """
                SELECT r.id,r.number,r.title,r.severity,r.status,r.scope,r.basis,r.affected_lots,r.in_stock_lots,
                       r.held_lots,r.shipments,r.customers,r.truncated,r.created_at,r.contained_at,r.closed_at,
                       r.override_reason IS NOT NULL AS overridden,
                       (SELECT name FROM users u WHERE u.tenant_id=r.tenant_id AND u.id=r.created_by) AS created_by_name,
                       (SELECT COUNT(*) FROM recall_customers c WHERE c.tenant_id=r.tenant_id AND c.recall_id=r.id
                          AND c.status='PENDING') AS pending_customers,
                       (SELECT COUNT(*) FROM recall_suspects x WHERE x.tenant_id=r.tenant_id AND x.recall_id=r.id)
                          AS suspect_lots"""
                        + from
                        + """
                         AND (?::text IS NULL OR (r.created_at,r.id) <
                              (SELECT c.created_at,c.id FROM recalls c WHERE c.tenant_id=r.tenant_id AND c.id=?))
                        ORDER BY r.created_at DESC,r.id DESC LIMIT ?""",
                args.toArray());
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        long total = s.db().queryForObject("SELECT COUNT(*)" + from, Long.class, filter.toArray());
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        out.put("total", total);
        return out;
    }

    /** One recall: header, suspects, affected lots (snapshot and live status), shipments, customers, close check. */
    public Map<String, Object> recall(Actor a, String id) {
        String t = a.tenant();
        var r = s.one(
                """
                SELECT r.*,d.name AS defect_name,p.name AS supplier,i.sku AS item_sku,i.name AS item,
                       (SELECT name FROM users u WHERE u.tenant_id=r.tenant_id AND u.id=r.created_by) AS created_by_name,
                       (SELECT name FROM users u WHERE u.tenant_id=r.tenant_id AND u.id=r.contained_by) AS contained_by_name,
                       (SELECT name FROM users u WHERE u.tenant_id=r.tenant_id AND u.id=r.closed_by) AS closed_by_name,
                       (SELECT name FROM users u WHERE u.tenant_id=r.tenant_id AND u.id=r.cancelled_by) AS cancelled_by_name
                FROM recalls r
                JOIN defect_codes d ON d.tenant_id=r.tenant_id AND d.code=r.defect_code
                LEFT JOIN partners p ON p.tenant_id=r.tenant_id AND p.id=r.supplier_id
                LEFT JOIN items i ON i.tenant_id=r.tenant_id AND i.id=r.item_id
                WHERE r.tenant_id=? AND r.id=?""",
                t,
                id);
        var out = new LinkedHashMap<String, Object>(r);
        out.put(
                "suspects",
                s.list(
                        """
                        SELECT l.id AS lot_id,l.number,l.status,i.sku,i.name AS item,i.uom,l.source_type,l.created_at
                        FROM recall_suspects x
                        JOIN lots l ON l.tenant_id=x.tenant_id AND l.id=x.lot_id
                        JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                        WHERE x.tenant_id=? AND x.recall_id=? ORDER BY l.number""",
                        t,
                        id));
        out.put(
                "lots",
                s.list(
                        """
                        SELECT x.lot_id,l.number,x.relation,x.depth,x.quantity,x.reserved,x.value_amount,x.uom,
                               x.lot_status,x.containment,x.ncr_id,n.number AS ncr,n.status AS ncr_status,
                               i.id AS item_id,i.sku,i.name AS item,i.type AS item_type,w.id AS warehouse_id,
                               w.name AS warehouse,w.code AS warehouse_code,l.status AS current_status,
                               COALESCE(b.quantity,0) AS current_quantity,l.source_type,l.created_at
                        FROM recall_lots x
                        JOIN lots l ON l.tenant_id=x.tenant_id AND l.id=x.lot_id
                        JOIN items i ON i.tenant_id=x.tenant_id AND i.id=x.item_id
                        JOIN warehouses w ON w.tenant_id=x.tenant_id AND w.id=x.warehouse_id
                        LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                        LEFT JOIN ncrs n ON n.tenant_id=x.tenant_id AND n.id=x.ncr_id
                        WHERE x.tenant_id=? AND x.recall_id=?
                        ORDER BY (x.quantity > 0) DESC,x.depth,l.number""",
                        t,
                        id));
        out.put(
                "shipments",
                s.list(
                        """
                        SELECT x.delivery_id,x.reference,x.quantity,x.returned,x.delivered_at,x.customer_id,
                               p.name AS customer,x.sales_order_id,so.number AS sales_order,x.lot_id,l.number AS lot,
                               i.sku,i.name AS item,i.uom
                        FROM recall_shipments x
                        JOIN partners p ON p.tenant_id=x.tenant_id AND p.id=x.customer_id
                        JOIN sales_orders so ON so.tenant_id=x.tenant_id AND so.id=x.sales_order_id
                        JOIN lots l ON l.tenant_id=x.tenant_id AND l.id=x.lot_id
                        JOIN items i ON i.tenant_id=x.tenant_id AND i.id=x.item_id
                        WHERE x.tenant_id=? AND x.recall_id=?
                        ORDER BY p.name,x.delivered_at,x.reference""",
                        t,
                        id));
        out.put(
                "customers",
                s.list(
                        """
                        SELECT c.customer_id,p.name AS customer,p.email,p.phone,c.status,c.notified_on,c.contact,c.notes,
                               c.recorded_at,(SELECT name FROM users u WHERE u.tenant_id=c.tenant_id AND u.id=c.notified_by)
                                 AS notified_by_name,
                               (SELECT COUNT(*) FROM recall_shipments x WHERE x.tenant_id=c.tenant_id
                                  AND x.recall_id=c.recall_id AND x.customer_id=c.customer_id) AS deliveries,
                               (SELECT string_agg(DISTINCT so.number, ', ') FROM recall_shipments x
                                JOIN sales_orders so ON so.tenant_id=x.tenant_id AND so.id=x.sales_order_id
                                WHERE x.tenant_id=c.tenant_id AND x.recall_id=c.recall_id AND x.customer_id=c.customer_id)
                                 AS sales_orders
                        FROM recall_customers c
                        JOIN partners p ON p.tenant_id=c.tenant_id AND p.id=c.customer_id
                        WHERE c.tenant_id=? AND c.recall_id=?
                        ORDER BY c.status DESC,p.name""",
                        t,
                        id));
        var check = new LinkedHashMap<String, Object>(recalls.outstanding(t, id));
        check.put(
                "releasedSinceContainment",
                s.db()
                        .queryForObject(
                                """
                                SELECT COUNT(*) FROM recall_lots x JOIN lots l ON l.tenant_id=x.tenant_id AND l.id=x.lot_id
                                WHERE x.tenant_id=? AND x.recall_id=? AND x.containment IN ('ALREADY_HELD','AWAITING_INSPECTION')
                                  AND l.status='RELEASED'""",
                                Long.class,
                                t,
                                id));
        out.put("closeCheck", check);
        return out;
    }

    /**
     * The notification list as CSV: one row per affected delivery with the customer's contact data and notification
     * status. Cells a spreadsheet would evaluate are prefixed with an apostrophe.
     */
    public void export(Actor a, String id, Writer out) {
        var r = s.one("SELECT number FROM recalls WHERE tenant_id=? AND id=?", a.tenant(), id);
        var rows = s.list(
                """
                SELECT p.name AS customer,p.email,p.phone,p.shipping_address,c.status,c.notified_on,c.contact,c.notes,
                       so.number AS sales_order,so.customer_reference,x.reference,x.delivered_at,l.number AS lot,
                       i.sku,i.name AS item,x.quantity,x.returned,i.uom
                FROM recall_customers c
                JOIN partners p ON p.tenant_id=c.tenant_id AND p.id=c.customer_id
                JOIN recall_shipments x ON x.tenant_id=c.tenant_id AND x.recall_id=c.recall_id AND x.customer_id=c.customer_id
                JOIN sales_orders so ON so.tenant_id=x.tenant_id AND so.id=x.sales_order_id
                JOIN lots l ON l.tenant_id=x.tenant_id AND l.id=x.lot_id
                JOIN items i ON i.tenant_id=x.tenant_id AND i.id=x.item_id
                WHERE c.tenant_id=? AND c.recall_id=?
                ORDER BY p.name,x.delivered_at,x.reference,l.number""",
                a.tenant(),
                id);
        var zone = RecallService.ZONE;
        try {
            out.write("﻿");
            out.write("Recall,Customer,Email,Phone,Shipping address,Notification status,Notified on,Contact,Notes,"
                    + "Sales order,Customer reference,Delivery reference,Delivered (" + zone + "),Lot,SKU,Item,"
                    + "Quantity,Returned,Unit\r\n");
            for (var row : rows) {
                var delivered = (java.sql.Timestamp) row.get("delivered_at");
                String at = delivered == null
                        ? ""
                        : OffsetDateTime.ofInstant(delivered.toInstant(), zone).format(TIMESTAMP);
                var cells = List.of(
                        str(r, "number"),
                        str(row, "customer"),
                        str(row, "email"),
                        str(row, "phone"),
                        str(row, "shipping_address"),
                        str(row, "status"),
                        str(row, "notified_on"),
                        str(row, "contact"),
                        str(row, "notes"),
                        str(row, "sales_order"),
                        str(row, "customer_reference"),
                        str(row, "reference"),
                        at,
                        str(row, "lot"),
                        str(row, "sku"),
                        str(row, "item"),
                        plain(row.get("quantity")),
                        plain(row.get("returned")),
                        str(row, "uom"));
                for (int i = 0; i < cells.size(); i++) {
                    if (i > 0) out.write(',');
                    out.write(cell(cells.get(i)));
                }
                out.write("\r\n");
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String plain(Object value) {
        return value == null ? "" : new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    }

    static String cell(String value) {
        String raw = value == null ? "" : value;
        if (!raw.isEmpty() && "=+-@\t\r".indexOf(raw.charAt(0)) >= 0) raw = "'" + raw;
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }
}
