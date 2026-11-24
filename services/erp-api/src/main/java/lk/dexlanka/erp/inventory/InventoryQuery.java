package lk.dexlanka.erp.inventory;

import static lk.dexlanka.erp.inventory.InventoryRules.blankToNull;
import static lk.dexlanka.erp.inventory.InventoryRules.estimate;
import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.admin.ApprovalPolicyService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Inventory read model: server-paged, filtered lists (keyset pagination on {@code created_at, id}) and document
 * details. Every list returns {@code {items, next}}; pass {@code next} as {@code before} for the following page.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InventoryQuery {
    private static final Set<String> VALUATION_ROLES = Set.of("ADMIN", "PLANNER", "WAREHOUSE");
    private static final Set<String> FINANCE_ROLES = Set.of("ADMIN", "PLANNER");

    private final Store s;
    private final InventoryRules rules;
    private final ApprovalPolicyService policies;

    InventoryQuery(Store s, InventoryRules rules, ApprovalPolicyService policies) {
        this.s = s;
        this.rules = rules;
        this.policies = policies;
    }

    // ------------------------------------------------------------------ warehouses and settings

    public Map<String, Object> warehouses(Actor a) {
        String production = rules.productionWarehouse(a);
        var rows = s.list("""
                SELECT w.id,w.code,w.name,w.kind,w.active,w.created_at,f.name AS factory,
                       COUNT(l.id) FILTER (WHERE b.quantity>0) AS lots,
                       COUNT(l.id) FILTER (WHERE b.quantity>0 AND l.status='QUARANTINE') AS quarantined_lots,
                       (SELECT COUNT(*) FROM stock_counts c
                        WHERE c.tenant_id=w.tenant_id AND c.warehouse_id=w.id AND c.status IN ('COUNTING','REVIEW')) AS open_counts
                FROM warehouses w
                JOIN factories f ON f.tenant_id=w.tenant_id AND f.id=w.factory_id
                LEFT JOIN lots l ON l.tenant_id=w.tenant_id AND l.warehouse_id=w.id
                LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                WHERE w.tenant_id=?
                GROUP BY w.id,f.name
                ORDER BY w.active DESC,w.name""", a.tenant());
        var items = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var item = new LinkedHashMap<>(row);
            item.put("production_output", row.get("id").equals(production));
            items.add(item);
        }
        return Map.of("items", items);
    }

    /**
     * The adjustment approval rule, read from the tenant's {@code STOCK_ADJUSTMENT} approval policy. {@code
     * approvalThreshold} is 0 when the policy has no threshold (every adjustment with a value needs approval); {@code
     * approvalRequired} and {@code approverRoles} (V8) describe the rest of the policy.
     */
    public Map<String, Object> settings(Actor a) {
        var policy = policies.policy(a.tenant(), ApprovalPolicyService.STOCK_ADJUSTMENT);
        var result = new LinkedHashMap<String, Object>();
        result.put("approvalThreshold", policy.amountThreshold() == null ? BigDecimal.ZERO : policy.amountThreshold());
        result.put("updatedAt", policy.updatedAt());
        result.put("updatedBy", policy.updatedBy());
        result.put("approvalRequired", policy.approvalRequired());
        result.put("approverRoles", policy.approverRoles());
        return result;
    }

    /** Stock value per warehouse and quality status, plus the inventory GL account split (ADMIN/PLANNER/WAREHOUSE). */
    public Map<String, Object> valuation(Actor a) {
        if (!VALUATION_ROLES.contains(a.role()))
            throw new AccessDeniedException("Your role cannot perform this action");
        var warehouses = s.list("""
                SELECT w.id,w.code,w.name,w.kind,w.active,
                       COUNT(l.id) FILTER (WHERE b.quantity>0) AS lots,
                       COALESCE(SUM(b.value_amount),0) AS value,
                       COALESCE(SUM(b.value_amount) FILTER (WHERE l.status='RELEASED'),0) AS released_value,
                       COALESCE(SUM(b.value_amount) FILTER (WHERE l.status='QUARANTINE'),0) AS quarantine_value,
                       COALESCE(SUM(b.value_amount) FILTER (WHERE l.status='REJECTED'),0) AS rejected_value,
                       COALESCE(SUM(CASE WHEN b.quantity>0 THEN b.value_amount*b.reserved/b.quantity ELSE 0 END),0) AS reserved_value
                FROM warehouses w
                LEFT JOIN lots l ON l.tenant_id=w.tenant_id AND l.warehouse_id=w.id
                LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                WHERE w.tenant_id=?
                GROUP BY w.id
                ORDER BY w.active DESC,w.name""", a.tenant());
        var accounts = s.list("""
                SELECT CASE WHEN i.type='FINISHED_GOOD' THEN 'FG_INVENTORY' ELSE 'RAW_INVENTORY' END AS account,
                       COALESCE(SUM(b.value_amount),0) AS value
                FROM lots l
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                WHERE l.tenant_id=?
                GROUP BY 1
                ORDER BY 1""", a.tenant());
        BigDecimal total = warehouses.stream().map(w -> dec(w.get("value"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("warehouses", warehouses, "accounts", accounts, "total", total);
    }

    // ------------------------------------------------------------------ lots

    private static final String LOT_COLUMNS = """
            SELECT l.id,l.number,l.status,l.source_type,l.source_id,l.unit_cost,l.created_at,l.item_id,l.warehouse_id,
                   i.sku,i.name AS item,i.type AS item_type,i.uom,
                   w.name AS warehouse,w.code AS warehouse_code,w.kind AS warehouse_kind,w.active AS warehouse_active,
                   COALESCE(b.quantity,0) AS quantity,COALESCE(b.reserved,0) AS reserved,
                   COALESCE(b.quantity,0)-COALESCE(b.reserved,0) AS available,COALESCE(b.value_amount,0) AS value_amount,
                   (l.status='QUARANTINE' AND l.source_type='PURCHASE') AS awaiting_inspection,
                   src.number AS source_lot,
                   (SELECT c.number FROM stock_count_lines cl JOIN stock_counts c ON c.tenant_id=cl.tenant_id AND c.id=cl.count_id
                    WHERE cl.tenant_id=l.tenant_id AND cl.lot_id=l.id AND cl.open LIMIT 1) AS open_count
            FROM lots l
            JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
            JOIN warehouses w ON w.tenant_id=l.tenant_id AND w.id=l.warehouse_id
            LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
            LEFT JOIN lots src ON l.source_type='TRANSFER' AND src.tenant_id=l.tenant_id AND src.id=l.source_id
            """;

    /**
     * Lots with their balances. By default only lots holding stock; {@code includeEmpty} adds depleted lots and {@code
     * movable} keeps only lots with unreserved stock that may be adjusted down or transferred.
     */
    public Map<String, Object> lots(
            Actor a,
            String warehouseId,
            String itemId,
            String status,
            String q,
            boolean includeEmpty,
            boolean movable,
            int limit,
            String before) {
        limit(limit);
        String w = blankToNull(warehouseId);
        String i = blankToNull(itemId);
        String st = blankToNull(status);
        String like = like(q);
        String cursor = blankToNull(before);
        var rows = s.list(
                LOT_COLUMNS + """
                WHERE l.tenant_id=?
                  AND (?::text IS NULL OR l.warehouse_id=?)
                  AND (?::text IS NULL OR l.item_id=?)
                  AND (?::text IS NULL OR l.status=?)
                  AND (?::boolean OR COALESCE(b.quantity,0)>0)
                  AND (NOT ?::boolean OR (COALESCE(b.quantity,0)>COALESCE(b.reserved,0)
                                          AND NOT (l.status='QUARANTINE' AND l.source_type='PURCHASE')))
                  AND (?::text IS NULL OR l.number ILIKE ? OR i.name ILIKE ? OR i.sku ILIKE ?)
                  AND (?::text IS NULL OR (l.created_at,l.id) <
                       (SELECT c.created_at,c.id FROM lots c WHERE c.tenant_id=l.tenant_id AND c.id=?))
                ORDER BY l.created_at DESC,l.id DESC
                LIMIT ?""",
                a.tenant(),
                w,
                w,
                i,
                i,
                st,
                st,
                includeEmpty,
                movable,
                like,
                like,
                like,
                like,
                cursor,
                cursor,
                limit + 1);
        return page(rows, limit);
    }

    /** One lot with its movements, the lots transferred from it and its inspections. */
    public Map<String, Object> lot(Actor a, String id) {
        var lot = s.one(LOT_COLUMNS + " WHERE l.tenant_id=? AND l.id=?", a.tenant(), id);
        return Map.of(
                "lot",
                lot,
                "movements",
                s.list("""
                        SELECT e.id,e.delta,e.value_delta,e.unit_cost,e.reference,e.reason,e.actor,e.created_at,e.uom
                        FROM stock_ledger e
                        WHERE e.tenant_id=? AND e.lot_id=?
                        ORDER BY e.created_at DESC,e.id DESC
                        LIMIT 100""", a.tenant(), id),
                "transferredTo",
                s.list("""
                        SELECT d.id,d.number,d.status,w.name AS warehouse,COALESCE(b.quantity,0) AS quantity
                        FROM lots d
                        JOIN warehouses w ON w.tenant_id=d.tenant_id AND w.id=d.warehouse_id
                        LEFT JOIN stock_balances b ON b.tenant_id=d.tenant_id AND b.lot_id=d.id
                        WHERE d.tenant_id=? AND d.source_type='TRANSFER' AND d.source_id=?
                        ORDER BY d.number""", a.tenant(), id),
                "inspections",
                s.list(
                        "SELECT id,type,status,notes,inspected_by,inspected_at,created_at FROM inspections WHERE tenant_id=? AND lot_id=? ORDER BY created_at",
                        a.tenant(),
                        id));
    }

    // ------------------------------------------------------------------ ledger

    public Map<String, Object> ledger(
            Actor a,
            String warehouseId,
            String itemId,
            String lotId,
            String reference,
            String q,
            String from,
            String to,
            int limit,
            String before) {
        limit(limit);
        String w = blankToNull(warehouseId);
        String i = blankToNull(itemId);
        String l = blankToNull(lotId);
        String ref = like(reference);
        String like = like(q);
        String fromDate = date(from);
        String toDate = date(to);
        String cursor = blankToNull(before);
        var rows = s.list(
                """
                SELECT e.id,e.lot_id,e.item_id,e.warehouse_id,e.uom,e.delta,e.value_delta,e.unit_cost,e.reference,e.reason,
                       e.actor,e.created_at,i.name AS item,i.sku,l.number AS lot,w.name AS warehouse,w.code AS warehouse_code
                FROM stock_ledger e
                JOIN items i ON i.tenant_id=e.tenant_id AND i.id=e.item_id
                JOIN lots l ON l.tenant_id=e.tenant_id AND l.id=e.lot_id
                JOIN warehouses w ON w.tenant_id=e.tenant_id AND w.id=e.warehouse_id
                WHERE e.tenant_id=?
                  AND (?::text IS NULL OR e.warehouse_id=?)
                  AND (?::text IS NULL OR e.item_id=?)
                  AND (?::text IS NULL OR e.lot_id=?)
                  AND (?::text IS NULL OR e.reference ILIKE ?)
                  AND (?::text IS NULL OR l.number ILIKE ? OR i.name ILIKE ? OR i.sku ILIKE ? OR e.reason ILIKE ?)
                  AND (?::date IS NULL OR e.created_at >= (?::date)::timestamp AT TIME ZONE 'Asia/Colombo')
                  AND (?::date IS NULL OR e.created_at < (?::date + 1)::timestamp AT TIME ZONE 'Asia/Colombo')
                  AND (?::text IS NULL OR (e.created_at,e.id) <
                       (SELECT c.created_at,c.id FROM stock_ledger c WHERE c.tenant_id=e.tenant_id AND c.id=?))
                ORDER BY e.created_at DESC,e.id DESC
                LIMIT ?""",
                a.tenant(),
                w,
                w,
                i,
                i,
                l,
                l,
                ref,
                ref,
                like,
                like,
                like,
                like,
                like,
                fromDate,
                fromDate,
                toDate,
                toDate,
                cursor,
                cursor,
                limit + 1);
        return page(rows, limit);
    }

    // ------------------------------------------------------------------ adjustments

    private static final String ADJUSTMENT_COLUMNS = """
            SELECT d.id,d.number,d.reason,d.notes,d.status,d.value_increase,d.value_decrease,
                   d.value_increase+d.value_decrease AS gross_value,d.value_increase-d.value_decrease AS net_value,
                   d.approval_threshold,d.created_at,d.submitted_at,d.posted_at,d.cancelled_at,
                   d.created_by,d.submitted_by,d.approved_by,d.cancelled_by,
                   cu.name AS created_by_name,su.name AS submitted_by_name,au.name AS approved_by_name,
                   xu.name AS cancelled_by_name,
                   (SELECT COUNT(*) FROM stock_adjustment_lines x WHERE x.tenant_id=d.tenant_id AND x.adjustment_id=d.id) AS line_count,
                   c.id AS count_id,c.number AS count_number
            FROM stock_adjustments d
            JOIN users cu ON cu.id=d.created_by
            LEFT JOIN users su ON su.id=d.submitted_by
            LEFT JOIN users au ON au.id=d.approved_by
            LEFT JOIN users xu ON xu.id=d.cancelled_by
            LEFT JOIN stock_counts c ON c.tenant_id=d.tenant_id AND c.adjustment_id=d.id
            """;

    public Map<String, Object> adjustments(Actor a, String status, String reason, String q, int limit, String before) {
        limit(limit);
        String st = blankToNull(status);
        String r = blankToNull(reason);
        String like = like(q);
        String cursor = blankToNull(before);
        var rows = s.list(
                ADJUSTMENT_COLUMNS + """
                WHERE d.tenant_id=?
                  AND (?::text IS NULL OR d.status=?)
                  AND (?::text IS NULL OR d.reason=?)
                  AND (?::text IS NULL OR d.number ILIKE ? OR d.notes ILIKE ? OR cu.name ILIKE ?)
                  AND (?::text IS NULL OR (d.created_at,d.id) <
                       (SELECT p.created_at,p.id FROM stock_adjustments p WHERE p.tenant_id=d.tenant_id AND p.id=?))
                ORDER BY d.created_at DESC,d.id DESC
                LIMIT ?""", a.tenant(), st, st, r, r, like, like, like, like, cursor, cursor, limit + 1);
        return page(rows, limit);
    }

    public Map<String, Object> adjustment(Actor a, String id) {
        var header = s.one(ADJUSTMENT_COLUMNS + " WHERE d.tenant_id=? AND d.id=?", a.tenant(), id);
        String number = str(header, "number");
        var result = new LinkedHashMap<String, Object>();
        result.put("adjustment", header);
        result.put("lines", s.list("""
                        SELECT x.id,x.line_no,x.kind,x.lot_id,x.item_id,x.warehouse_id,x.quantity,x.unit_cost,x.lot_number,
                               x.value_delta,l.number AS lot,l.status AS lot_status,i.name AS item,i.sku,i.uom,
                               w.name AS warehouse,COALESCE(b.quantity,0) AS on_hand,COALESCE(b.reserved,0) AS reserved
                        FROM stock_adjustment_lines x
                        JOIN items i ON i.tenant_id=x.tenant_id AND i.id=x.item_id
                        JOIN warehouses w ON w.tenant_id=x.tenant_id AND w.id=x.warehouse_id
                        LEFT JOIN lots l ON l.tenant_id=x.tenant_id AND l.id=x.lot_id
                        LEFT JOIN stock_balances b ON b.tenant_id=x.tenant_id AND b.lot_id=x.lot_id
                        WHERE x.tenant_id=? AND x.adjustment_id=?
                        ORDER BY x.line_no""", a.tenant(), id));
        result.put("ledger", s.list("""
                        SELECT e.id,e.delta,e.value_delta,e.uom,e.reason,e.created_at,l.number AS lot,w.name AS warehouse
                        FROM stock_ledger e
                        JOIN lots l ON l.tenant_id=e.tenant_id AND l.id=e.lot_id
                        JOIN warehouses w ON w.tenant_id=e.tenant_id AND w.id=e.warehouse_id
                        WHERE e.tenant_id=? AND e.reference=? AND e.reason LIKE 'ADJUSTMENT\\_%'
                        ORDER BY e.created_at,e.id""", a.tenant(), number));
        result.put("journal", journal(a, number, "Stock adjustment %"));
        result.put("events", events(a, number));
        return result;
    }

    // ------------------------------------------------------------------ transfers

    private static final String TRANSFER_COLUMNS = """
            SELECT t.id,t.number,t.status,t.notes,t.total_value,t.created_at,t.posted_at,t.cancelled_at,
                   t.from_warehouse_id,t.to_warehouse_id,fw.name AS from_warehouse,tw.name AS to_warehouse,
                   fw.code AS from_code,tw.code AS to_code,
                   t.created_by,cu.name AS created_by_name,pu.name AS posted_by_name,xu.name AS cancelled_by_name,
                   (SELECT COUNT(*) FROM stock_transfer_lines x WHERE x.tenant_id=t.tenant_id AND x.transfer_id=t.id) AS line_count
            FROM stock_transfers t
            JOIN warehouses fw ON fw.tenant_id=t.tenant_id AND fw.id=t.from_warehouse_id
            JOIN warehouses tw ON tw.tenant_id=t.tenant_id AND tw.id=t.to_warehouse_id
            JOIN users cu ON cu.id=t.created_by
            LEFT JOIN users pu ON pu.id=t.posted_by
            LEFT JOIN users xu ON xu.id=t.cancelled_by
            """;

    public Map<String, Object> transfers(
            Actor a, String status, String warehouseId, String q, int limit, String before) {
        limit(limit);
        String st = blankToNull(status);
        String w = blankToNull(warehouseId);
        String like = like(q);
        String cursor = blankToNull(before);
        var rows = s.list(
                TRANSFER_COLUMNS + """
                WHERE t.tenant_id=?
                  AND (?::text IS NULL OR t.status=?)
                  AND (?::text IS NULL OR t.from_warehouse_id=? OR t.to_warehouse_id=?)
                  AND (?::text IS NULL OR t.number ILIKE ? OR t.notes ILIKE ? OR fw.name ILIKE ? OR tw.name ILIKE ?)
                  AND (?::text IS NULL OR (t.created_at,t.id) <
                       (SELECT p.created_at,p.id FROM stock_transfers p WHERE p.tenant_id=t.tenant_id AND p.id=?))
                ORDER BY t.created_at DESC,t.id DESC
                LIMIT ?""",
                a.tenant(),
                st,
                st,
                w,
                w,
                w,
                like,
                like,
                like,
                like,
                like,
                cursor,
                cursor,
                limit + 1);
        return page(rows, limit);
    }

    public Map<String, Object> transfer(Actor a, String id) {
        var header = s.one(TRANSFER_COLUMNS + " WHERE t.tenant_id=? AND t.id=?", a.tenant(), id);
        String number = str(header, "number");
        var result = new LinkedHashMap<String, Object>();
        result.put("transfer", header);
        result.put("lines", s.list("""
                        SELECT x.id,x.line_no,x.quantity,x.value_amount,x.source_lot_id,x.destination_lot_id,
                               sl.number AS source_lot,sl.status AS source_status,dl.number AS destination_lot,
                               dl.status AS destination_status,i.name AS item,i.sku,i.uom,sl.unit_cost,
                               COALESCE(b.quantity,0) AS on_hand,COALESCE(b.reserved,0) AS reserved
                        FROM stock_transfer_lines x
                        JOIN lots sl ON sl.tenant_id=x.tenant_id AND sl.id=x.source_lot_id
                        JOIN items i ON i.tenant_id=x.tenant_id AND i.id=x.item_id
                        LEFT JOIN lots dl ON dl.tenant_id=x.tenant_id AND dl.id=x.destination_lot_id
                        LEFT JOIN stock_balances b ON b.tenant_id=x.tenant_id AND b.lot_id=x.source_lot_id
                        WHERE x.tenant_id=? AND x.transfer_id=?
                        ORDER BY x.line_no""", a.tenant(), id));
        result.put("ledger", s.list("""
                        SELECT e.id,e.delta,e.value_delta,e.uom,e.reason,e.created_at,l.number AS lot,w.name AS warehouse
                        FROM stock_ledger e
                        JOIN lots l ON l.tenant_id=e.tenant_id AND l.id=e.lot_id
                        JOIN warehouses w ON w.tenant_id=e.tenant_id AND w.id=e.warehouse_id
                        WHERE e.tenant_id=? AND e.reference=? AND e.reason IN ('TRANSFER_OUT','TRANSFER_IN')
                        ORDER BY e.created_at,e.id""", a.tenant(), number));
        result.put("events", events(a, number));
        return result;
    }

    // ------------------------------------------------------------------ counts

    private static final String COUNT_COLUMNS = """
            SELECT c.id,c.number,c.status,c.blind,c.notes,c.item_type,c.created_at,c.submitted_at,c.posted_at,c.cancelled_at,
                   c.warehouse_id,w.name AS warehouse,w.code AS warehouse_code,c.item_id,i.name AS item,
                   c.category_id,cat.name AS category,
                   c.created_by,cu.name AS created_by_name,su.name AS submitted_by_name,pu.name AS posted_by_name,
                   c.adjustment_id,d.number AS adjustment_number,d.status AS adjustment_status,
                   (SELECT COUNT(*) FROM stock_count_lines x WHERE x.tenant_id=c.tenant_id AND x.count_id=c.id) AS line_count,
                   (SELECT COUNT(*) FROM stock_count_lines x
                    WHERE x.tenant_id=c.tenant_id AND x.count_id=c.id AND x.counted_qty IS NOT NULL) AS counted_count
            FROM stock_counts c
            JOIN warehouses w ON w.tenant_id=c.tenant_id AND w.id=c.warehouse_id
            JOIN users cu ON cu.id=c.created_by
            LEFT JOIN users su ON su.id=c.submitted_by
            LEFT JOIN users pu ON pu.id=c.posted_by
            LEFT JOIN items i ON i.tenant_id=c.tenant_id AND i.id=c.item_id
            LEFT JOIN item_categories cat ON cat.tenant_id=c.tenant_id AND cat.id=c.category_id
            LEFT JOIN stock_adjustments d ON d.tenant_id=c.tenant_id AND d.id=c.adjustment_id
            """;

    public Map<String, Object> counts(Actor a, String status, String warehouseId, String q, int limit, String before) {
        limit(limit);
        String st = blankToNull(status);
        String w = blankToNull(warehouseId);
        String like = like(q);
        String cursor = blankToNull(before);
        var rows = s.list(
                COUNT_COLUMNS + """
                WHERE c.tenant_id=?
                  AND (?::text IS NULL OR c.status=? OR (?::text='OPEN' AND c.status IN ('COUNTING','REVIEW')))
                  AND (?::text IS NULL OR c.warehouse_id=?)
                  AND (?::text IS NULL OR c.number ILIKE ? OR c.notes ILIKE ? OR w.name ILIKE ?)
                  AND (?::text IS NULL OR (c.created_at,c.id) <
                       (SELECT p.created_at,p.id FROM stock_counts p WHERE p.tenant_id=c.tenant_id AND p.id=?))
                ORDER BY c.created_at DESC,c.id DESC
                LIMIT ?""", a.tenant(), st, st, st, w, w, like, like, like, like, cursor, cursor, limit + 1);
        return page(rows, limit);
    }

    /**
     * A count with its lines. While a blind count is COUNTING, system quantities, variances and movement flags are
     * withheld ({@code hidden=true}). Open counts compute variances against the live balance (as posting will); posted
     * counts show the stored posting-time figures.
     */
    public Map<String, Object> count(Actor a, String id) {
        var header = s.one(COUNT_COLUMNS + " WHERE c.tenant_id=? AND c.id=?", a.tenant(), id);
        boolean open = Set.of("COUNTING", "REVIEW").contains(header.get("status"));
        boolean hidden = Boolean.TRUE.equals(header.get("blind")) && "COUNTING".equals(header.get("status"));
        var rows = s.list("""
                SELECT x.id,x.line_no,x.lot_id,x.item_id,x.snapshot_qty,x.unit_cost,x.counted_qty,x.counted_at,
                       x.system_qty,x.variance_qty,x.variance_value,x.moved,
                       l.number AS lot,l.status AS lot_status,l.unit_cost AS lot_unit_cost,i.name AS item,i.sku,i.uom,
                       u.name AS counted_by,
                       COALESCE(b.quantity,0) AS quantity,COALESCE(b.reserved,0) AS reserved,
                       COALESCE(b.value_amount,0) AS value_amount,
                       EXISTS (SELECT 1 FROM stock_ledger e
                               WHERE e.tenant_id=x.tenant_id AND e.lot_id=x.lot_id AND e.created_at>=c.created_at) AS activity
                FROM stock_count_lines x
                JOIN stock_counts c ON c.tenant_id=x.tenant_id AND c.id=x.count_id
                JOIN lots l ON l.tenant_id=x.tenant_id AND l.id=x.lot_id
                JOIN items i ON i.tenant_id=x.tenant_id AND i.id=x.item_id
                LEFT JOIN users u ON u.id=x.counted_by
                LEFT JOIN stock_balances b ON b.tenant_id=x.tenant_id AND b.lot_id=x.lot_id
                WHERE x.tenant_id=? AND x.count_id=?
                ORDER BY x.line_no""", a.tenant(), id);
        var lines = new ArrayList<Map<String, Object>>();
        var moved = new ArrayList<String>();
        BigDecimal increase = BigDecimal.ZERO;
        BigDecimal decrease = BigDecimal.ZERO;
        int variances = 0;
        for (var row : rows) {
            var line = new LinkedHashMap<String, Object>();
            for (String k : List.of(
                    "id",
                    "line_no",
                    "lot_id",
                    "item_id",
                    "lot",
                    "lot_status",
                    "item",
                    "sku",
                    "uom",
                    "unit_cost",
                    "counted_qty",
                    "counted_by",
                    "counted_at")) line.put(k, row.get(k));
            BigDecimal system;
            BigDecimal variance = null;
            BigDecimal value = null;
            boolean lotMoved;
            if (open) {
                system = dec(row.get("quantity"));
                lotMoved =
                        system.compareTo(dec(row.get("snapshot_qty"))) != 0 || Boolean.TRUE.equals(row.get("activity"));
                if (row.get("counted_qty") != null) {
                    variance = dec(row.get("counted_qty")).subtract(system);
                    var lot = new LinkedHashMap<String, Object>();
                    lot.put("quantity", row.get("quantity"));
                    lot.put("value_amount", row.get("value_amount"));
                    lot.put("unit_cost", row.get("lot_unit_cost"));
                    value = estimate(lot, variance);
                }
            } else {
                system = row.get("system_qty") == null ? null : dec(row.get("system_qty"));
                lotMoved = Boolean.TRUE.equals(row.get("moved"));
                variance = row.get("variance_qty") == null ? null : dec(row.get("variance_qty"));
                value = row.get("variance_value") == null ? null : dec(row.get("variance_value"));
            }
            if (lotMoved && !hidden) moved.add(str(row, "lot"));
            if (variance != null && variance.signum() != 0) variances++;
            if (value != null) {
                if (value.signum() > 0) increase = increase.add(value);
                else decrease = decrease.add(value.negate());
            }
            line.put("snapshot_qty", hidden ? null : row.get("snapshot_qty"));
            line.put("system_qty", hidden ? null : system);
            line.put("reserved", hidden || !open ? null : row.get("reserved"));
            line.put("variance_qty", hidden ? null : variance);
            line.put("variance_value", hidden ? null : value);
            line.put("moved", !hidden && lotMoved);
            lines.add(line);
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("lines", rows.size());
        summary.put(
                "counted",
                rows.stream().filter(r -> r.get("counted_qty") != null).count());
        summary.put("variances", hidden ? null : variances);
        summary.put("value_increase", hidden ? null : increase);
        summary.put("value_decrease", hidden ? null : decrease);
        summary.put("net_value", hidden ? null : increase.subtract(decrease));
        var result = new LinkedHashMap<String, Object>();
        result.put("count", header);
        result.put("hidden", hidden);
        result.put("lines", lines);
        result.put("moved", moved);
        result.put("summary", summary);
        result.put("events", events(a, str(header, "number")));
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> events(Actor a, String reference) {
        return s.list(
                "SELECT id,actor,action,detail,created_at FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at,id",
                a.tenant(),
                reference);
    }

    /** Journal lines of a document; financial detail is limited to ADMIN and PLANNER, like {@code /api/state}. */
    private List<Map<String, Object>> journal(Actor a, String reference, String description) {
        if (!FINANCE_ROLES.contains(a.role())) return List.of();
        return s.list("""
                SELECT e.id AS entry_id,e.description,e.created_at,l.id,l.account,l.debit,l.credit
                FROM journal_entries e
                JOIN journal_lines l ON l.tenant_id=e.tenant_id AND l.entry_id=e.id
                WHERE e.tenant_id=? AND e.reference=? AND e.description LIKE ?
                ORDER BY e.created_at,e.id,l.debit DESC""", a.tenant(), reference, description);
    }

    private static void limit(int limit) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
    }

    private static Map<String, Object> page(List<Map<String, Object>> rows, int limit) {
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        return Map.of("items", items, "next", more ? items.getLast().get("id") : "");
    }

    /** {@code %q%} with LIKE wildcards escaped, or null for no filter. */
    private static String like(String q) {
        String value = blankToNull(q);
        if (value == null) return null;
        return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String date(String value) {
        String v = blankToNull(value);
        if (v == null) return null;
        try {
            return LocalDate.parse(v).toString();
        } catch (java.time.DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use calendar dates in yyyy-MM-dd format");
        }
    }
}
