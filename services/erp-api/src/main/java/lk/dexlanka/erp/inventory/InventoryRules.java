package lk.dexlanka.erp.inventory;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Lookups and rules shared by the inventory commands. Deliberately not {@code @Transactional}: every method runs inside
 * the calling command's transaction (after its tenant lock).
 *
 * <p>GL mapping: inventory accounts follow the item type, exactly like receipts, issues, production output and dispatch
 * ({@code FINISHED_GOOD} → {@code FG_INVENTORY}, everything else → {@code RAW_INVENTORY}), not the warehouse. A transfer
 * therefore never changes the account and posts no journal; adjustments post against {@value #ADJUSTMENT_ACCOUNT}.
 */
@Component
class InventoryRules {
    static final String ADJUSTMENT_ACCOUNT = "INVENTORY_ADJUSTMENT";

    private final Store s;

    InventoryRules(Store s) {
        this.s = s;
    }

    /** A tenant-owned lot with its item, warehouse and current balance (404 when it is not ours). */
    Map<String, Object> lot(Actor a, String lotId) {
        return s.one("""
                SELECT l.*,i.name AS item,i.sku,i.type AS item_type,i.uom,i.active AS item_active,
                       w.name AS warehouse,w.code AS warehouse_code,w.active AS warehouse_active,
                       COALESCE(b.quantity,0) AS quantity,COALESCE(b.reserved,0) AS reserved,
                       COALESCE(b.value_amount,0) AS value_amount
                FROM lots l
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                JOIN warehouses w ON w.tenant_id=l.tenant_id AND w.id=l.warehouse_id
                LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                WHERE l.tenant_id=? AND l.id=?""", a.tenant(), lotId);
    }

    static BigDecimal available(Map<String, Object> lot) {
        return dec(lot.get("quantity")).subtract(dec(lot.get("reserved")));
    }

    /**
     * Goods receipts wait in quarantine for their incoming inspection, whose rejection returns the receiving lot's whole
     * quantity to the supplier. Such lots cannot be adjusted, transferred or counted until the decision is recorded.
     */
    static boolean awaitingIncomingInspection(Map<String, Object> lot) {
        return "QUARANTINE".equals(lot.get("status")) && "PURCHASE".equals(lot.get("source_type"));
    }

    static void requireMovable(Map<String, Object> lot, String prefix) {
        require(
                !awaitingIncomingInspection(lot),
                prefix + "lot " + str(lot, "number")
                        + " is awaiting its incoming inspection; record the quality decision first");
    }

    /**
     * The value a stock movement of {@code delta} would post, computed exactly like {@code Store.ledger}: an increase at
     * the lot unit cost, a decrease at the balance's average value, and a full depletion at the exact remaining value.
     */
    static BigDecimal estimate(Map<String, Object> lot, BigDecimal delta) {
        if (delta.signum() >= 0) return money(delta.multiply(dec(lot.get("unit_cost"))));
        BigDecimal quantity = dec(lot.get("quantity"));
        if (quantity.signum() == 0) return BigDecimal.ZERO;
        if (delta.abs().compareTo(quantity) == 0)
            return dec(lot.get("value_amount")).negate();
        return money(dec(lot.get("value_amount")).multiply(delta).divide(quantity, 12, RoundingMode.HALF_UP));
    }

    static String inventoryAccount(Object itemType) {
        return "FINISHED_GOOD".equals(itemType) ? "FG_INVENTORY" : "RAW_INVENTORY";
    }

    /**
     * The warehouse manufacturing posts finished output into: the first {@code FINISHED} warehouse by id (the same query
     * as {@code ManufacturingService.complete}). Inventory commands never change which warehouse that is.
     */
    String productionWarehouse(Actor a) {
        var rows = s.list(
                "SELECT id FROM warehouses WHERE tenant_id=? AND kind='FINISHED' ORDER BY id LIMIT 1", a.tenant());
        return rows.isEmpty() ? null : str(rows.getFirst(), "id");
    }

    boolean lotNumberTaken(Actor a, String number) {
        return !s.list("SELECT 1 FROM lots WHERE tenant_id=? AND number=?", a.tenant(), number)
                .isEmpty();
    }

    /** {@code base-T1A2B}: a new lot number derived from the source lot, unique in the tenant. */
    String derivedLotNumber(Actor a, String base) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate =
                    base + "-T" + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT);
            if (!lotNumberTaken(a, candidate)) return candidate;
        }
        throw new IllegalStateException("Could not allocate a unique lot number");
    }

    /** A generated lot number such as {@code FND-2026-1A2B3C}, unique in the tenant. */
    String generatedLotNumber(Actor a, String prefix) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = s.number(a, prefix);
            if (!lotNumberTaken(a, candidate)) return candidate;
        }
        throw new IllegalStateException("Could not allocate a unique lot number");
    }

    /** A document number such as {@code ADJ-2026-1A2B3C}, unique in {@code table}. */
    String documentNumber(Actor a, String table, String prefix) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = s.number(a, prefix);
            if (s.list("SELECT 1 FROM " + table + " WHERE tenant_id=? AND number=?", a.tenant(), candidate)
                    .isEmpty()) return candidate;
        }
        throw new IllegalStateException("Could not allocate a unique document number");
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String quantityText(BigDecimal quantity, Object uom) {
        return quantity.stripTrailingZeros().toPlainString() + " " + uom;
    }
}
