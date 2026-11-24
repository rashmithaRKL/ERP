package lk.dexlanka.erp.reporting;

import static lk.dexlanka.erp.platform.Store.require;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Costs & reports read model (V9), for ADMIN and PLANNER: the manufacturing cost register ({@code GET
 * /api/reports/costs}) with tenant-wide cost totals, and the posted journal lines ({@code GET /api/reports/journal}).
 * Both are keyset-paged, newest first. Journal date filters are calendar days in {@value #ZONE}, inclusive.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReportQuery {
    static final String ZONE = "Asia/Colombo";
    static final List<String> ORDER_STATUSES = List.of(
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

    private static final String COST_FROM = """
             FROM manufacturing_orders o
            JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id
            WHERE o.tenant_id=?
              AND (?::text IS NULL OR o.status=ANY(string_to_array(?,',')))
              AND (?::text IS NULL OR o.number ILIKE ? OR i.name ILIKE ? OR i.sku ILIKE ?)
            """;

    private static final String JOURNAL_FROM = """
             FROM journal_lines l
            JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id
            WHERE l.tenant_id=?
              AND (?::text IS NULL OR l.account=?)
              AND (?::text IS NULL OR e.reference ILIKE ? OR e.description ILIKE ?)
              AND (?::date IS NULL OR e.created_at >= (?::date)::timestamp AT TIME ZONE '%1$s')
              AND (?::date IS NULL OR e.created_at < ((?::date) + 1)::timestamp AT TIME ZONE '%1$s')
            """.formatted(ZONE);

    private final Store s;

    ReportQuery(Store s) {
        this.s = s;
    }

    /**
     * Cost register: one row per manufacturing order with its estimated and posted costs. {@code {items, next, total,
     * summary}}; {@code summary} totals every order of the tenant (unfiltered) for the composition and reconciliation.
     *
     * @param status {@code all} (or blank), {@code active} (not completed, closed or cancelled) or one status code
     * @param q matched against order number and product name/SKU
     */
    public Map<String, Object> costs(Actor a, String status, String q, int limit, String before) {
        a.allow("PLANNER");
        limit(limit);
        String statuses = statuses(status);
        String like = like(q);
        String cursor = blankToNull(before);
        Object[] filter = {a.tenant(), statuses, statuses, like, like, like, like};
        var rows = s.list("""
                SELECT o.id,o.number,o.status,o.created_at,o.due_date,o.product_id,i.name AS product,i.sku,i.uom,
                       o.quantity,o.good_qty,o.scrap_qty,o.estimated_cost,o.material_cost,o.conversion_cost,
                       o.material_cost+o.conversion_cost AS actual_cost,o.wip_cost,o.finished_cost,o.scrap_cost""" + COST_FROM + """
                  AND (?::text IS NULL OR (o.created_at,o.id) <
                       (SELECT c.created_at,c.id FROM manufacturing_orders c WHERE c.tenant_id=o.tenant_id AND c.id=?))
                ORDER BY o.created_at DESC,o.id DESC
                LIMIT ?""", concat(filter, cursor, cursor, limit + 1));
        var out = page(rows, limit);
        out.put("total", s.db().queryForObject("SELECT COUNT(*)" + COST_FROM, Long.class, filter));
        out.put("summary", s.one("""
                SELECT COUNT(*) AS orders,
                       COALESCE(SUM(estimated_cost),0) AS estimated_cost,
                       COALESCE(SUM(material_cost),0) AS material_cost,
                       COALESCE(SUM(conversion_cost),0) AS conversion_cost,
                       COALESCE(SUM(wip_cost),0) AS wip_cost,
                       COALESCE(SUM(finished_cost),0) AS finished_cost,
                       COALESCE(SUM(scrap_cost),0) AS scrap_cost
                FROM manufacturing_orders WHERE tenant_id=?""", a.tenant()));
        return out;
    }

    /**
     * Posted journal lines, newest entry first and debits before credits within an entry. {@code {items, next, totals,
     * accounts}}: {@code totals} sums debits and credits of the filter; {@code accounts} lists the tenant's posted
     * accounts (first page only) for the filter picker.
     *
     * @param account exact account code, or blank
     * @param q matched against the entry reference and description
     * @param from first posting day (yyyy-MM-dd), inclusive
     * @param to last posting day (yyyy-MM-dd), inclusive
     */
    public Map<String, Object> journal(
            Actor a, String account, String q, String from, String to, int limit, String before) {
        a.allow("PLANNER");
        limit(limit);
        String f = blankToNull(from);
        String t = blankToNull(to);
        LocalDate fromDate = date(f, "from");
        LocalDate toDate = date(t, "to");
        require(
                fromDate == null || toDate == null || !toDate.isBefore(fromDate),
                "The end date must be on or after the start date");
        String acc = blankToNull(account);
        String like = like(q);
        String cursor = blankToNull(before);
        Object[] filter = {a.tenant(), acc, acc, like, like, like, f, f, t, t};
        var rows = s.list(
                "SELECT l.id,l.entry_id,e.reference,e.description,e.actor,e.created_at,l.account,l.debit,l.credit"
                        + JOURNAL_FROM
                        + """
                  AND (?::text IS NULL OR (e.created_at,e.id,l.debit,l.id) <
                       (SELECT ce.created_at,ce.id,cl.debit,cl.id FROM journal_lines cl
                        JOIN journal_entries ce ON ce.tenant_id=cl.tenant_id AND ce.id=cl.entry_id
                        WHERE cl.tenant_id=l.tenant_id AND cl.id=?))
                ORDER BY e.created_at DESC,e.id DESC,l.debit DESC,l.id DESC
                LIMIT ?""",
                concat(filter, cursor, cursor, limit + 1));
        var out = page(rows, limit);
        out.put(
                "totals",
                s.one(
                        "SELECT COALESCE(SUM(l.debit),0) AS debit,COALESCE(SUM(l.credit),0) AS credit" + JOURNAL_FROM,
                        filter));
        if (cursor == null)
            out.put(
                    "accounts",
                    s
                            .list(
                                    "SELECT DISTINCT account FROM journal_lines WHERE tenant_id=? ORDER BY account",
                                    a.tenant())
                            .stream()
                            .map(r -> r.get("account"))
                            .toList());
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static void limit(int limit) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
    }

    private static String statuses(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("all")) return null;
        if (status.equalsIgnoreCase("active"))
            return String.join(
                    ",",
                    ORDER_STATUSES.stream()
                            .filter(st ->
                                    !List.of("COMPLETED", "CLOSED", "CANCELLED").contains(st))
                            .toList());
        String code = status.toUpperCase(Locale.ROOT);
        require(ORDER_STATUSES.contains(code), "Unknown manufacturing order status filter");
        return code;
    }

    private static LocalDate date(String value, String name) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a yyyy-MM-dd date for '" + name + "'");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String like(String q) {
        String text = blankToNull(q);
        return text == null
                ? null
                : "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static LinkedHashMap<String, Object> page(List<Map<String, Object>> rows, int limit) {
        boolean more = rows.size() > limit;
        var items = new ArrayList<>(more ? rows.subList(0, limit) : rows);
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        return out;
    }

    private static Object[] concat(Object[] head, Object... tail) {
        var out = new Object[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
