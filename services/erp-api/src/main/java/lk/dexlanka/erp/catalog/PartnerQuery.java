package lk.dexlanka.erp.catalog;

import static lk.dexlanka.erp.platform.Store.require;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/partners} (alphabetical, keyset-paged directory with search, type and active filters) and {@code GET
 * /api/partners/{id}} (one partner with open documents, receivables and activity).
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PartnerQuery {
    /** Customer receivables: dispatch revenue less customer-return credits (no cash receipts are recorded yet). */
    private static final String RECEIVABLES = """
            COALESCE((SELECT SUM(d.revenue_amount) FROM deliveries d
                      JOIN sales_orders so ON so.tenant_id=d.tenant_id AND so.id=d.sales_order_id
                      WHERE d.tenant_id=p.tenant_id AND so.customer_id=p.id),0)
            - COALESCE((SELECT SUM(r.credit_amount) FROM customer_returns r
                        JOIN sales_orders so ON so.tenant_id=r.tenant_id AND so.id=r.sales_order_id
                        WHERE r.tenant_id=p.tenant_id AND so.customer_id=p.id),0)""";

    private static final String STATS = """
            p.*,
            CASE WHEN p.type='CUSTOMER' THEN
                (SELECT COUNT(*) FROM sales_orders so WHERE so.tenant_id=p.tenant_id AND so.customer_id=p.id
                   AND so.status IN ('DRAFT','CONFIRMED','PARTIAL'))
            ELSE
                (SELECT COUNT(*) FROM purchase_orders po WHERE po.tenant_id=p.tenant_id AND po.supplier_id=p.id
                   AND po.status IN ('DRAFT','APPROVED','PARTIAL'))
            END AS open_documents,
            CASE WHEN p.type='CUSTOMER' THEN""" + " " + RECEIVABLES + " END AS receivables";

    private final Store s;

    PartnerQuery(Store s) {
        this.s = s;
    }

    /**
     * @param type {@code CUSTOMER}, {@code SUPPLIER} or blank for both
     * @param status {@code active}, {@code inactive} or {@code all}
     * @param before cursor: the {@code next} value of the previous page
     */
    public Map<String, Object> page(Actor a, String type, String q, String status, int limit, String before) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        String kind = type == null || type.isBlank() ? null : type.toUpperCase(Locale.ROOT);
        require(kind == null || kind.equals("CUSTOMER") || kind.equals("SUPPLIER"), "Unknown partner type");
        String state = status == null || status.isBlank() ? "all" : status.toLowerCase(Locale.ROOT);
        require(state.equals("all") || state.equals("active") || state.equals("inactive"), "Unknown partner status");
        Boolean active = state.equals("all") ? null : state.equals("active");
        String like = q == null || q.isBlank()
                ? null
                : "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String cursor = before == null || before.isBlank() ? null : before;
        String where = """
                WHERE p.tenant_id=?
                  AND (?::text IS NULL OR p.type=?)
                  AND (?::boolean IS NULL OR p.active=?::boolean)
                  AND (?::text IS NULL OR p.name ILIKE ? OR p.email ILIKE ? OR p.phone ILIKE ? OR p.tax_id ILIKE ?)
                """;
        var rows = s.list(
                "SELECT " + STATS + " FROM partners p " + where + """
                  AND (?::text IS NULL OR (p.name,p.id) >
                       (SELECT c.name,c.id FROM partners c WHERE c.tenant_id=p.tenant_id AND c.id=?))
                ORDER BY p.name,p.id
                LIMIT ?""",
                a.tenant(),
                kind,
                kind,
                active,
                active,
                like,
                like,
                like,
                like,
                like,
                cursor,
                cursor,
                limit + 1);
        var more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        long total = s.db().queryForObject(
                        "SELECT COUNT(*) FROM partners p " + where,
                        Long.class,
                        a.tenant(),
                        kind,
                        kind,
                        active,
                        active,
                        like,
                        like,
                        like,
                        like,
                        like);
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        out.put("total", total);
        return out;
    }

    public Map<String, Object> detail(Actor a, String id) {
        var partner = s.one("SELECT " + STATS + " FROM partners p WHERE p.tenant_id=? AND p.id=?", a.tenant(), id);
        var documents = partner.get("type").equals("CUSTOMER")
                ? s.list(
                        "SELECT id,number,status,due_date,total,created_at FROM sales_orders WHERE tenant_id=? AND customer_id=? ORDER BY created_at DESC,id DESC LIMIT 10",
                        a.tenant(),
                        id)
                : s.list(
                        "SELECT id,number,status,due_date,quantity*unit_cost AS total,created_at FROM purchase_orders WHERE tenant_id=? AND supplier_id=? ORDER BY created_at DESC,id DESC LIMIT 10",
                        a.tenant(),
                        id);
        var activity = s.list(
                "SELECT * FROM audit_events WHERE tenant_id=? AND action LIKE 'PARTNER_%' AND reference=? ORDER BY created_at DESC,id LIMIT 50",
                a.tenant(), partner.get("name"));
        var out = new LinkedHashMap<String, Object>();
        out.put("partner", partner);
        out.put("documents", documents);
        out.put("activity", activity);
        return out;
    }
}
