package lk.dexlanka.erp.quality;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.quality.QualityPaging.args;
import static lk.dexlanka.erp.quality.QualityPaging.blank;
import static lk.dexlanka.erp.quality.QualityPaging.code;
import static lk.dexlanka.erp.quality.QualityPaging.like;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read model of nonconformances, dispositions and rework (V12): paged NCR and rework lists, their details, the quality
 * side of a lot (open NCRs, rework origin), the quality summary and the people that work can be assigned to.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class NcrQuery {
    static final List<String> STATUSES = List.of("OPEN", "DISPOSITIONED", "CLOSED", "CANCELLED");
    static final List<String> SEVERITIES = List.of("MINOR", "MAJOR", "CRITICAL");
    static final List<String> SOURCES =
            List.of("INSPECTION", "PRODUCTION", "SUPPLIER", "CUSTOMER", "INTERNAL", "AUDIT", "RECALL");

    private static final String NCR_COLUMNS = """
            SELECT n.*,i.sku,i.name AS item,l.number AS lot,d.name AS defect_name,u.name AS raised_by,
                   COALESCE((SELECT SUM(x.quantity) FROM ncr_dispositions x
                             WHERE x.tenant_id=n.tenant_id AND x.ncr_id=n.id AND x.status='POSTED'),0) AS dispositioned,
                   COALESCE((SELECT SUM(x.quantity) FROM ncr_dispositions x
                             WHERE x.tenant_id=n.tenant_id AND x.ncr_id=n.id
                               AND x.status IN ('PENDING_APPROVAL','AWAITING_RETURN')),0) AS pending,
                   (SELECT COUNT(*) FROM ncr_dispositions x WHERE x.tenant_id=n.tenant_id AND x.ncr_id=n.id
                      AND x.status='PENDING_APPROVAL') AS pending_approvals,
                   (SELECT COUNT(*) FROM ncr_dispositions x WHERE x.tenant_id=n.tenant_id AND x.ncr_id=n.id
                      AND x.status='AWAITING_RETURN') AS awaiting_returns""";

    private final Store s;

    NcrQuery(Store s) {
        this.s = s;
    }

    /**
     * NCRs newest first: {@code {items, next, total, counts}} ({@code counts}: the tenant's NCRs per status, and open
     * ones per severity, unfiltered).
     *
     * @param status {@code all}/blank or OPEN, DISPOSITIONED, CLOSED, CANCELLED, or {@code active} (open or
     *     dispositioned)
     */
    public Map<String, Object> page(
            Actor a,
            String status,
            String severity,
            String defect,
            String item,
            String source,
            String q,
            int limit,
            String before) {
        boolean active = "active".equalsIgnoreCase(status);
        String st = active ? null : code(status, STATUSES, "Unknown NCR status filter");
        String sv = code(severity, SEVERITIES, "Unknown severity filter");
        String so = code(source, SOURCES, "Unknown NCR source filter");
        String df = blank(defect);
        String it = blank(item);
        String like = like(q);
        String from = """
                 FROM ncrs n
                LEFT JOIN items i ON i.tenant_id=n.tenant_id AND i.id=n.item_id
                LEFT JOIN lots l ON l.tenant_id=n.tenant_id AND l.id=n.lot_id
                LEFT JOIN defect_codes d ON d.tenant_id=n.tenant_id AND d.code=n.defect_code
                LEFT JOIN users u ON u.id=n.created_by
                WHERE n.tenant_id=?
                  AND (?::text IS NULL OR n.status=?)
                  AND (NOT ?::boolean OR n.status IN ('OPEN','DISPOSITIONED'))
                  AND (?::text IS NULL OR n.severity=?)
                  AND (?::text IS NULL OR n.source=?)
                  AND (?::text IS NULL OR n.defect_code=?)
                  AND (?::text IS NULL OR n.item_id=?)
                  AND (?::text IS NULL OR n.number ILIKE ? OR n.title ILIKE ? OR l.number ILIKE ? OR i.sku ILIKE ?
                       OR i.name ILIKE ?)""";
        var page = QualityPaging.page(
                s,
                NCR_COLUMNS,
                from,
                "n",
                "ncrs",
                args(a.tenant(), st, st, active, sv, sv, so, so, df, df, it, it, like, like, like, like, like, like),
                limit,
                before);
        var counts = new LinkedHashMap<String, Object>();
        for (String code : STATUSES) counts.put(code, 0L);
        for (var r : s.list("SELECT status,COUNT(*) AS n FROM ncrs WHERE tenant_id=? GROUP BY status", a.tenant()))
            counts.put((String) r.get("status"), r.get("n"));
        for (String code : SEVERITIES) counts.put("OPEN_" + code, 0L);
        for (var r : s.list(
                "SELECT severity,COUNT(*) AS n FROM ncrs WHERE tenant_id=? AND status='OPEN' GROUP BY severity",
                a.tenant())) counts.put("OPEN_" + r.get("severity"), r.get("n"));
        page.put("counts", counts);
        return page;
    }

    /** One NCR with its links, dispositions, rework orders, linked CAPAs and activity. */
    public Map<String, Object> ncr(Actor a, String id) {
        var ncr = s.one(NCR_COLUMNS + """
                        ,l.status AS lot_status,l.source_type AS lot_source_type,i.uom AS item_uom,i.type AS item_type,
                               COALESCE(b.quantity,0) AS lot_quantity,COALESCE(b.reserved,0) AS lot_reserved,
                               q.type AS inspection_type,q.status AS inspection_status,q.inspected_by,q.inspected_at,
                               mo.number AS mo_number,p.name AS supplier,cr.number AS customer_return,
                               rw.number AS rework_source,cb.name AS closed_by_name,xb.name AS cancelled_by_name,
                               (SELECT r.id FROM receipts r WHERE r.tenant_id=n.tenant_id AND r.lot_id=n.lot_id LIMIT 1)
                                 AS receipt_id
                        FROM ncrs n
                        LEFT JOIN items i ON i.tenant_id=n.tenant_id AND i.id=n.item_id
                        LEFT JOIN lots l ON l.tenant_id=n.tenant_id AND l.id=n.lot_id
                        LEFT JOIN stock_balances b ON b.tenant_id=n.tenant_id AND b.lot_id=n.lot_id
                        LEFT JOIN defect_codes d ON d.tenant_id=n.tenant_id AND d.code=n.defect_code
                        LEFT JOIN users u ON u.id=n.created_by
                        LEFT JOIN users cb ON cb.id=n.closed_by
                        LEFT JOIN users xb ON xb.id=n.cancelled_by
                        LEFT JOIN inspections q ON q.tenant_id=n.tenant_id AND q.id=n.inspection_id
                        LEFT JOIN manufacturing_orders mo ON mo.tenant_id=n.tenant_id AND mo.id=n.manufacturing_order_id
                        LEFT JOIN partners p ON p.tenant_id=n.tenant_id AND p.id=n.supplier_id
                        LEFT JOIN customer_returns cr ON cr.tenant_id=n.tenant_id AND cr.id=n.customer_return_id
                        LEFT JOIN rework_orders rw ON rw.tenant_id=n.tenant_id AND rw.id=n.rework_order_id
                        WHERE n.tenant_id=? AND n.id=?""", a.tenant(), id);
        var out = new LinkedHashMap<String, Object>();
        out.put("ncr", ncr);
        out.put("dispositions", s.list("""
                        SELECT x.*,u.name AS created_by_name,v.name AS decided_by_name,sr.number AS supplier_return,
                               rw.status AS rework_status,rw.output_lot_id,ol.number AS output_lot
                        FROM ncr_dispositions x
                        LEFT JOIN users u ON u.id=x.created_by
                        LEFT JOIN users v ON v.id=x.decided_by
                        LEFT JOIN supplier_returns sr ON sr.tenant_id=x.tenant_id AND sr.id=x.supplier_return_id
                        LEFT JOIN rework_orders rw ON rw.tenant_id=x.tenant_id AND rw.id=x.rework_order_id
                        LEFT JOIN lots ol ON ol.tenant_id=rw.tenant_id AND ol.id=rw.output_lot_id
                        WHERE x.tenant_id=? AND x.ncr_id=? ORDER BY x.line_no""", a.tenant(), id));
        out.put("capas", s.list("""
                        SELECT c.id,c.number,c.title,c.status FROM capa_ncrs x
                        JOIN capas c ON c.tenant_id=x.tenant_id AND c.id=x.capa_id
                        WHERE x.tenant_id=? AND x.ncr_id=? ORDER BY c.created_at""", a.tenant(), id));
        out.put(
                "activity",
                s.list(
                        "SELECT id,actor,action,detail,created_at FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at DESC,id DESC LIMIT 50",
                        a.tenant(),
                        ncr.get("number")));
        return out;
    }

    /** Rework orders newest first: {@code {items, next, total, counts}}; {@code status} {@code all}, OPEN, COMPLETED. */
    public Map<String, Object> reworkPage(Actor a, String status, String q, int limit, String before) {
        String st = code(status, List.of("OPEN", "COMPLETED"), "Unknown rework status filter");
        String like = like(q);
        String from = """
                 FROM rework_orders r
                JOIN items i ON i.tenant_id=r.tenant_id AND i.id=r.item_id
                JOIN lots il ON il.tenant_id=r.tenant_id AND il.id=r.input_lot_id
                JOIN ncrs n ON n.tenant_id=r.tenant_id AND n.id=r.ncr_id
                LEFT JOIN lots ol ON ol.tenant_id=r.tenant_id AND ol.id=r.output_lot_id
                WHERE r.tenant_id=?
                  AND (?::text IS NULL OR r.status=?)
                  AND (?::text IS NULL OR r.number ILIKE ? OR n.number ILIKE ? OR il.number ILIKE ? OR ol.number ILIKE ?
                       OR i.sku ILIKE ? OR i.name ILIKE ?)""";
        var page = QualityPaging.page(
                s,
                """
                SELECT r.*,i.sku,i.name AS item,i.uom,il.number AS input_lot,ol.number AS output_lot,
                       ol.status AS output_lot_status,n.number AS ncr,
                       (SELECT q.status FROM inspections q WHERE q.tenant_id=r.tenant_id AND q.lot_id=r.output_lot_id
                        ORDER BY q.created_at DESC LIMIT 1) AS inspection_status""",
                from,
                "r",
                "rework_orders",
                args(a.tenant(), st, st, like, like, like, like, like, like, like),
                limit,
                before);
        var counts = new LinkedHashMap<String, Object>();
        counts.put("OPEN", 0L);
        counts.put("COMPLETED", 0L);
        for (var r :
                s.list("SELECT status,COUNT(*) AS n FROM rework_orders WHERE tenant_id=? GROUP BY status", a.tenant()))
            counts.put((String) r.get("status"), r.get("n"));
        page.put("counts", counts);
        return page;
    }

    /** One rework order: costs, materials, lot links (genealogy) and the output inspection. */
    public Map<String, Object> rework(Actor a, String id) {
        var rw = s.one("""
                SELECT r.*,i.sku,i.name AS item,i.uom,i.type AS item_type,il.number AS input_lot,
                       ol.number AS output_lot,ol.status AS output_lot_status,n.number AS ncr,n.title AS ncr_title,
                       u.name AS created_by_name,v.name AS completed_by_name,
                       (SELECT q.id FROM inspections q WHERE q.tenant_id=r.tenant_id AND q.lot_id=r.output_lot_id
                        ORDER BY q.created_at DESC LIMIT 1) AS inspection_id,
                       (SELECT q.status FROM inspections q WHERE q.tenant_id=r.tenant_id AND q.lot_id=r.output_lot_id
                        ORDER BY q.created_at DESC LIMIT 1) AS inspection_status
                FROM rework_orders r
                JOIN items i ON i.tenant_id=r.tenant_id AND i.id=r.item_id
                JOIN lots il ON il.tenant_id=r.tenant_id AND il.id=r.input_lot_id
                JOIN ncrs n ON n.tenant_id=r.tenant_id AND n.id=r.ncr_id
                LEFT JOIN lots ol ON ol.tenant_id=r.tenant_id AND ol.id=r.output_lot_id
                LEFT JOIN users u ON u.id=r.created_by
                LEFT JOIN users v ON v.id=r.completed_by
                WHERE r.tenant_id=? AND r.id=?""", a.tenant(), id);
        var out = new LinkedHashMap<String, Object>();
        out.put("rework", rw);
        out.put("materials", s.list("""
                        SELECT m.*,l.number AS lot,i.sku,i.name AS item,i.uom FROM rework_materials m
                        JOIN lots l ON l.tenant_id=m.tenant_id AND l.id=m.lot_id
                        JOIN items i ON i.tenant_id=m.tenant_id AND i.id=m.item_id
                        WHERE m.tenant_id=? AND m.rework_order_id=? ORDER BY m.created_at,m.id""", a.tenant(), id));
        out.put("links", s.list("""
                        SELECT k.*,il.number AS input_lot,ol.number AS output_lot FROM rework_lot_links k
                        JOIN lots il ON il.tenant_id=k.tenant_id AND il.id=k.input_lot_id
                        JOIN lots ol ON ol.tenant_id=k.tenant_id AND ol.id=k.output_lot_id
                        WHERE k.tenant_id=? AND k.rework_order_id=? ORDER BY k.role DESC,il.number""", a.tenant(), id));
        out.put(
                "activity",
                s.list(
                        "SELECT id,actor,action,detail,created_at FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at DESC,id DESC LIMIT 50",
                        a.tenant(),
                        rw.get("number")));
        return out;
    }

    /** The quality side of a lot: its NCRs, the rework order that produced it and the rework orders that consumed it. */
    public Map<String, Object> lot(Actor a, String lotId) {
        s.one("SELECT id FROM lots WHERE tenant_id=? AND id=?", a.tenant(), lotId);
        var out = new LinkedHashMap<String, Object>();
        out.put(
                "ncrs",
                s.list(
                        "SELECT id,number,status,severity,defect_code,title,quantity,uom,created_at FROM ncrs WHERE tenant_id=? AND lot_id=? ORDER BY created_at DESC",
                        a.tenant(),
                        lotId));
        var origin = s.list("""
                SELECT r.id,r.number,r.status,r.input_quantity,r.output_quantity,r.output_value,n.number AS ncr,
                       il.id AS input_lot_id,il.number AS input_lot
                FROM rework_orders r
                JOIN ncrs n ON n.tenant_id=r.tenant_id AND n.id=r.ncr_id
                JOIN lots il ON il.tenant_id=r.tenant_id AND il.id=r.input_lot_id
                WHERE r.tenant_id=? AND r.output_lot_id=?""", a.tenant(), lotId);
        out.put("reworkOrigin", origin.isEmpty() ? null : origin.getFirst());
        out.put("reworkedInto", s.list("""
                        SELECT r.id,r.number,r.status,k.role,k.quantity,ol.id AS output_lot_id,ol.number AS output_lot
                        FROM rework_lot_links k
                        JOIN rework_orders r ON r.tenant_id=k.tenant_id AND r.id=k.rework_order_id
                        JOIN lots ol ON ol.tenant_id=k.tenant_id AND ol.id=k.output_lot_id
                        WHERE k.tenant_id=? AND k.input_lot_id=?
                        UNION ALL
                        SELECT r.id,r.number,r.status,'REWORKED',r.input_quantity,NULL,NULL FROM rework_orders r
                        WHERE r.tenant_id=? AND r.input_lot_id=? AND r.status='OPEN'
                        ORDER BY 2""", a.tenant(), lotId, a.tenant(), lotId));
        return out;
    }

    /**
     * Quality summary for {@code from}..{@code to} (inclusive calendar dates, default the last 30 days): open NCRs by
     * severity, NCRs raised, scrap value (posted SCRAP dispositions), rework cost (labour + material of rework orders
     * completed) and the top defect codes of NCRs raised in the range.
     */
    public Map<String, Object> summary(Actor a, String from, String to) {
        LocalDate end = date(to, LocalDate.now());
        LocalDate start = date(from, end.minusDays(29));
        require(!start.isAfter(end), "The start date is after the end date");
        require(start.plusYears(5).isAfter(end), "Choose a range of at most five years");
        var out = new LinkedHashMap<String, Object>();
        out.put("from", start.toString());
        out.put("to", end.toString());
        var open = new LinkedHashMap<String, Object>();
        for (String code : SEVERITIES) open.put(code, 0L);
        for (var r : s.list(
                "SELECT severity,COUNT(*) AS n FROM ncrs WHERE tenant_id=? AND status='OPEN' GROUP BY severity",
                a.tenant())) open.put((String) r.get("severity"), r.get("n"));
        out.put("openBySeverity", open);
        String range = "BETWEEN ?::date AND ?::date";
        out.put(
                "raised",
                s.one(
                                "SELECT COUNT(*) AS n FROM ncrs WHERE tenant_id=? AND (created_at AT TIME ZONE 'Asia/Colombo')::date "
                                        + range,
                                a.tenant(),
                                start.toString(),
                                end.toString())
                        .get("n"));
        var scrap = s.one(
                "SELECT COALESCE(SUM(value_amount),0) AS value,COALESCE(SUM(quantity),0) AS quantity,COUNT(*) AS n FROM ncr_dispositions WHERE tenant_id=? AND type='SCRAP' AND status='POSTED' AND (posted_at AT TIME ZONE 'Asia/Colombo')::date "
                        + range,
                a.tenant(),
                start.toString(),
                end.toString());
        out.put("scrapValue", scrap.get("value"));
        out.put("scrapDispositions", scrap.get("n"));
        var rework = s.one(
                "SELECT COALESCE(SUM(labour_cost+material_cost),0) AS cost,COALESCE(SUM(input_value),0) AS input,COUNT(*) AS n FROM rework_orders WHERE tenant_id=? AND status='COMPLETED' AND (completed_at AT TIME ZONE 'Asia/Colombo')::date "
                        + range,
                a.tenant(),
                start.toString(),
                end.toString());
        out.put("reworkCost", rework.get("cost"));
        out.put("reworkInputValue", rework.get("input"));
        out.put("reworkCompleted", rework.get("n"));
        out.put(
                "concessions",
                s.one(
                                "SELECT COUNT(*) AS n FROM ncr_dispositions WHERE tenant_id=? AND type='USE_AS_IS' AND status='POSTED' AND (posted_at AT TIME ZONE 'Asia/Colombo')::date "
                                        + range,
                                a.tenant(),
                                start.toString(),
                                end.toString())
                        .get("n"));
        out.put(
                "topDefects",
                s.list(
                        "SELECT n.defect_code AS code,d.name,COUNT(*) AS ncrs,COALESCE(SUM(n.quantity),0) AS quantity FROM ncrs n JOIN defect_codes d ON d.tenant_id=n.tenant_id AND d.code=n.defect_code WHERE n.tenant_id=? AND n.status<>'CANCELLED' AND (n.created_at AT TIME ZONE 'Asia/Colombo')::date "
                                + range + " GROUP BY n.defect_code,d.name ORDER BY COUNT(*) DESC,n.defect_code LIMIT 5",
                        a.tenant(),
                        start.toString(),
                        end.toString()));
        return out;
    }

    /** Active users (id, name, role) work can be assigned to: CAPA owners and action owners. */
    public Map<String, Object> people(Actor a) {
        return Map.of(
                "items",
                s.list(
                        "SELECT id,name,role FROM users WHERE tenant_id=? AND active ORDER BY lower(name),id",
                        a.tenant()));
    }

    private static LocalDate date(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value);
        } catch (java.time.DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use valid calendar dates (yyyy-MM-dd)");
        }
    }
}
