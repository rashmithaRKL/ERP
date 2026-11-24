package lk.dexlanka.erp.procurement;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Lookups and rules shared by the procurement commands. Deliberately not {@code @Transactional}: every method runs
 * inside the calling command's transaction (after its tenant lock).
 *
 * <p>Lifecycle: {@code DRAFT} (editable, supplier optional) → submit → {@code SUBMITTED} → approve (a different
 * PLANNER/ADMIN) → {@code APPROVED} → {@code PARTIAL} → {@code RECEIVED}; {@code CANCELLED} while nothing is received;
 * {@code CLOSED} (short, with a reason) from {@code PARTIAL}. An incoming QC rejection takes its quantity back from the
 * line and reopens the order.
 */
@Component
class PurchaseRules {
    /** Orders that accept goods receipts. */
    static final Set<String> RECEIVABLE = Set.of("APPROVED", "PARTIAL");

    /** Orders whose receipts can be invoiced or returned. */
    static final Set<String> APPROVED_OR_LATER = Set.of("APPROVED", "PARTIAL", "RECEIVED", "CLOSED");

    private final Store s;

    PurchaseRules(Store s) {
        this.s = s;
    }

    /** The order header, locked for the rest of the command (404 when it is not ours). */
    Map<String, Object> locked(Actor a, String id) {
        return s.one("SELECT * FROM purchase_orders WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    Map<String, Object> header(Actor a, String id) {
        return s.one("SELECT * FROM purchase_orders WHERE tenant_id=? AND id=?", a.tenant(), id);
    }

    /** An active supplier of this tenant (404 when unknown, 409 when it is a customer or inactive). */
    Map<String, Object> activeSupplier(Actor a, String supplierId) {
        var p = s.one("SELECT * FROM partners WHERE tenant_id=? AND id=?", a.tenant(), supplierId);
        require(p.get("type").equals("SUPPLIER"), p.get("name") + " is a customer; choose a supplier");
        require(
                Boolean.TRUE.equals(p.get("active")),
                "Supplier " + p.get("name")
                        + " is inactive; inactive suppliers cannot be used on new purchase orders. Reactivate the"
                        + " supplier or choose another one");
        return p;
    }

    /** An active raw material, packaging or semi-finished item (finished goods are made, not purchased). */
    Map<String, Object> purchasable(Actor a, String itemId) {
        var item = s.one("SELECT * FROM items WHERE tenant_id=? AND id=?", a.tenant(), itemId);
        require(
                !item.get("type").equals("FINISHED_GOOD"),
                "SKU " + item.get("sku")
                        + " is a finished good; purchase orders buy raw materials, packaging and semi-finished items");
        require(
                Boolean.TRUE.equals(item.get("active")),
                "SKU " + item.get("sku") + " is inactive; inactive items cannot be used on new purchase orders");
        return item;
    }

    /** 409 unless the order has a supplier and lines, and every supplier and item on it is still active. */
    void requireOrderable(Actor a, Map<String, Object> po, String action) {
        require(po.get("supplier_id") != null, "Choose a supplier before the order is " + action);
        activeSupplier(a, str(po, "supplier_id"));
        var lines = s.list(
                "SELECT l.line_no,i.sku,i.active FROM purchase_order_lines l JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id WHERE l.tenant_id=? AND l.purchase_order_id=? ORDER BY l.line_no",
                a.tenant(),
                po.get("id"));
        require(!lines.isEmpty(), "Add at least one line before the order is " + action);
        for (var l : lines)
            require(
                    Boolean.TRUE.equals(l.get("active")),
                    "SKU " + l.get("sku") + " on line " + l.get("line_no")
                            + " is inactive; remove it or reactivate the item");
    }

    /** Net base quantity received (QC rejections excluded) over all lines of an order. */
    BigDecimal received(Actor a, String id) {
        return s.db().queryForObject(
                        "SELECT COALESCE(SUM(received),0) FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=?",
                        BigDecimal.class,
                        a.tenant(),
                        id);
    }

    /**
     * Recomputes the receiving status of an approved order from its lines: every line fully received → RECEIVED,
     * something received → PARTIAL, nothing (left) received → APPROVED. Closed and cancelled orders keep their status.
     */
    String refreshStatus(Actor a, String id) {
        s.db().update("""
                        UPDATE purchase_orders po
                        SET status=CASE WHEN t.complete THEN 'RECEIVED' WHEN t.started THEN 'PARTIAL' ELSE 'APPROVED' END,
                            updated_at=now()
                        FROM (SELECT bool_and(received>=base_quantity) AS complete,bool_or(received>0) AS started
                              FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=?) t
                        WHERE po.tenant_id=? AND po.id=? AND po.status IN ('APPROVED','PARTIAL','RECEIVED')""", a.tenant(), id, a.tenant(), id);
        return str(header(a, id), "status");
    }

    /** Sets the header total to the sum of its line totals. */
    void totals(Actor a, String id) {
        s.db().update("""
                        UPDATE purchase_orders po SET total=t.total
                        FROM (SELECT COALESCE(SUM(line_total),0) AS total
                              FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=?) t
                        WHERE po.tenant_id=? AND po.id=?""", a.tenant(), id, a.tenant(), id);
    }

    /** A document number such as {@code SINV-2026-1A2B3C}, unique in {@code table}. */
    String documentNumber(Actor a, String table, String prefix) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = s.number(a, prefix);
            if (s.list("SELECT 1 FROM " + table + " WHERE tenant_id=? AND number=?", a.tenant(), candidate)
                    .isEmpty()) return candidate;
        }
        throw new IllegalStateException("Could not allocate a unique document number");
    }

    boolean lotNumberTaken(Actor a, String number) {
        return !s.list("SELECT 1 FROM lots WHERE tenant_id=? AND number=?", a.tenant(), number)
                .isEmpty();
    }

    /** Base units per one {@code unit}: the base-unit cost of {@code unitCost} per {@code unit}, rounded at 6 dp. */
    static BigDecimal baseUnitCost(BigDecimal unitCost, BigDecimal factor) {
        return unitCost.divide(factor, 6, RoundingMode.HALF_UP);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String plain(Object value) {
        return dec(value).stripTrailingZeros().toPlainString();
    }

    static String amount(BigDecimal value) {
        return "Rs. " + String.format(Locale.ROOT, "%,.2f", value.setScale(2, RoundingMode.HALF_UP));
    }

    static String lines(int count) {
        return count + (count == 1 ? " line" : " lines");
    }
}
