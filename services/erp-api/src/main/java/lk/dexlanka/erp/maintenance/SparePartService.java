package lk.dexlanka.erp.maintenance;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spare parts on maintenance work orders. Spare parts are {@code SPARE_PART} items held in ordinary lots.
 *
 * <p>An issue consumes released, unreserved stock oldest lot first through {@code Store.ledger} (reason {@value
 * #ISSUE}, valued like every issue at the lot's average balance value) and posts Dr {@value
 * MaintenanceRules#EXPENSE_ACCOUNT} / Cr the item's inventory account. A return of unused parts is the compensating
 * entry: the same lot receives the quantity back at the issue's unit value (reason {@value #RETURN}) and the journal is
 * reversed. Both refuse in a closed period. Parts are issued to approved, scheduled or in-progress orders and returned
 * before the order is completed or cancelled.
 */
@Service
public class SparePartService {
    static final String ISSUE = "MAINTENANCE_ISSUE";
    static final String RETURN = "MAINTENANCE_RETURN";
    private static final Set<String> ISSUABLE = Set.of("APPROVED", "SCHEDULED", "IN_PROGRESS");

    private final Store s;
    private final MaintenanceRules rules;

    SparePartService(Store s, MaintenanceRules rules) {
        this.s = s;
        this.rules = rules;
    }

    @Transactional
    public Map<String, Object> issue(Actor a, String id, MaintenanceCommands.IssuePart c, String key) {
        a.allow("MAINTENANCE");
        return s.once(a, key, "issue-parts:" + id, c, () -> {
            s.openPeriod(a);
            var wo = rules.workOrder(a, id);
            require(
                    ISSUABLE.contains(str(wo, "status")),
                    "Spare parts are issued to approved, scheduled or in-progress work orders");
            var item = s.one("SELECT * FROM items WHERE tenant_id=? AND id=?", a.tenant(), c.itemId());
            String sku = str(item, "sku");
            require(
                    item.get("type").equals("SPARE_PART"),
                    "SKU " + sku + " is not a spare part; maintenance orders consume spare-part items");
            require(Boolean.TRUE.equals(item.get("active")), "SKU " + sku + " is inactive");
            s.quantity(str(item, "uom"), c.quantity());
            String number = str(wo, "number");
            BigDecimal remaining = c.quantity();
            BigDecimal total = BigDecimal.ZERO;
            var movements = new ArrayList<Map<String, Object>>();
            for (var lot : s.list("""
                            SELECT l.id,l.number,b.quantity-b.reserved AS available
                            FROM lots l JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                            WHERE l.tenant_id=? AND l.item_id=? AND l.status='RELEASED' AND b.quantity>b.reserved
                            ORDER BY l.created_at,l.id
                            FOR UPDATE OF b""", a.tenant(), c.itemId())) {
                if (remaining.signum() == 0) break;
                BigDecimal qty = remaining.min(dec(lot.get("available")));
                if (qty.signum() <= 0) continue;
                BigDecimal value =
                        s.ledger(a, str(lot, "id"), qty.negate(), number, ISSUE).abs();
                String movement = id();
                s.db().update(
                                "INSERT INTO maintenance_part_movements(id,tenant_id,work_order_id,kind,lot_id,item_id,quantity,value_amount,notes,created_by) VALUES(?,?,?,'ISSUE',?,?,?,?,?,?)",
                                movement,
                                a.tenant(),
                                id,
                                lot.get("id"),
                                c.itemId(),
                                qty,
                                value,
                                c.notes() == null ? "" : c.notes().strip(),
                                a.id());
                var m = new LinkedHashMap<String, Object>();
                m.put("id", movement);
                m.put("lot", lot.get("number"));
                m.put("quantity", qty);
                m.put("value", value);
                movements.add(m);
                total = total.add(value);
                remaining = remaining.subtract(qty);
            }
            require(
                    remaining.signum() == 0,
                    "Only "
                            + c.quantity()
                                    .subtract(remaining)
                                    .stripTrailingZeros()
                                    .toPlainString() + " " + item.get("uom") + " of " + sku
                            + " is available in released stock");
            s.journal(
                    a,
                    number,
                    "Spare parts issued to maintenance",
                    MaintenanceRules.EXPENSE_ACCOUNT,
                    inventoryAccount(item.get("type")),
                    total);
            s.db().update(
                            "UPDATE maintenance_work_orders SET parts_cost=parts_cost+? WHERE tenant_id=? AND id=?",
                            total,
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "MWO_PARTS_ISSUED",
                    number,
                    c.quantity().stripTrailingZeros().toPlainString() + " " + item.get("uom") + " " + sku + " · Rs. "
                            + money(total).toPlainString());
            var result = new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("movements", movements);
            result.put("value", total);
            return result;
        });
    }

    /** Returns {@code quantity} of one issue movement to its lot at the issue's unit value. */
    @Transactional
    public Map<String, Object> returnPart(
            Actor a, String id, String movementId, MaintenanceCommands.ReturnPart c, String key) {
        a.allow("MAINTENANCE");
        return s.once(a, key, "return-parts:" + movementId, c, () -> {
            s.openPeriod(a);
            var wo = rules.workOrder(a, id);
            require(
                    ISSUABLE.contains(str(wo, "status")),
                    "Unused parts are returned before the work order is completed or cancelled");
            var issue = s.one(
                    "SELECT m.*,i.sku,i.uom,i.type FROM maintenance_part_movements m JOIN items i ON i.tenant_id=m.tenant_id AND i.id=m.item_id WHERE m.tenant_id=? AND m.id=? AND m.work_order_id=? AND m.kind='ISSUE'",
                    a.tenant(),
                    movementId,
                    id);
            var returned = s.one(
                    "SELECT COALESCE(SUM(quantity),0) AS quantity,COALESCE(SUM(value_amount),0) AS value FROM maintenance_part_movements WHERE tenant_id=? AND returns_id=?",
                    a.tenant(),
                    movementId);
            BigDecimal issued = dec(issue.get("quantity"));
            BigDecimal open = issued.subtract(dec(returned.get("quantity")));
            require(
                    c.quantity().compareTo(open) <= 0,
                    "Only " + open.stripTrailingZeros().toPlainString() + " " + issue.get("uom")
                            + " of this issue can still be returned");
            s.quantity(str(issue, "uom"), c.quantity());
            BigDecimal issuedValue = dec(issue.get("value_amount"));
            BigDecimal value = c.quantity().compareTo(open) == 0
                    ? issuedValue.subtract(dec(returned.get("value")))
                    : money(issuedValue.multiply(c.quantity()).divide(issued, 12, RoundingMode.HALF_UP));
            String number = str(wo, "number");
            s.ledger(a, str(issue, "lot_id"), c.quantity(), number, RETURN, value);
            s.db().update(
                            "INSERT INTO maintenance_part_movements(id,tenant_id,work_order_id,kind,lot_id,item_id,quantity,value_amount,returns_id,notes,created_by) VALUES(?,?,?,'RETURN',?,?,?,?,?,?,?)",
                            id(),
                            a.tenant(),
                            id,
                            issue.get("lot_id"),
                            issue.get("item_id"),
                            c.quantity(),
                            value,
                            movementId,
                            c.notes() == null ? "" : c.notes().strip(),
                            a.id());
            s.journal(
                    a,
                    number,
                    "Unused spare parts returned from maintenance",
                    inventoryAccount(issue.get("type")),
                    MaintenanceRules.EXPENSE_ACCOUNT,
                    value);
            s.db().update(
                            "UPDATE maintenance_work_orders SET parts_cost=GREATEST(parts_cost-?,0) WHERE tenant_id=? AND id=?",
                            value,
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "MWO_PARTS_RETURNED",
                    number,
                    c.quantity().stripTrailingZeros().toPlainString() + " " + issue.get("uom") + " " + issue.get("sku")
                            + " · Rs. " + money(value).toPlainString());
            var result = new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("quantity", c.quantity());
            result.put("value", value);
            return result;
        });
    }

    /** The GL inventory account an item is valued in (the same mapping as receipts, issues and adjustments). */
    static String inventoryAccount(Object itemType) {
        return "FINISHED_GOOD".equals(itemType) ? "FG_INVENTORY" : "RAW_INVENTORY";
    }
}
