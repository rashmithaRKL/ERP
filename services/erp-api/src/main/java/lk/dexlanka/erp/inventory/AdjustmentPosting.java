package lk.dexlanka.erp.inventory;

import static lk.dexlanka.erp.inventory.InventoryRules.available;
import static lk.dexlanka.erp.inventory.InventoryRules.blankToNull;
import static lk.dexlanka.erp.inventory.InventoryRules.estimate;
import static lk.dexlanka.erp.inventory.InventoryRules.inventoryAccount;
import static lk.dexlanka.erp.inventory.InventoryRules.quantityText;
import static lk.dexlanka.erp.inventory.InventoryRules.requireMovable;
import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * The single posting path for stock adjustments, used by manual adjustments and by cycle-count variances. Not
 * {@code @Transactional}: callers are {@code @Transactional} commands that already hold the tenant lock.
 *
 * <p>Lifecycle: {@code DRAFT} → {@link #submit} → {@code POSTED}, or {@code APPROVAL_REQUIRED} when the gross absolute
 * value (increases + decreases) exceeds the tenant threshold → {@link #approve} by a different person → {@code
 * POSTED}. Each line posts one ledger entry valued like {@code Store.ledger} (decreases at the balance's average value,
 * a full depletion at the exact remaining value), and the document posts one balanced journal entry per inventory
 * account and direction against {@value InventoryRules#ADJUSTMENT_ACCOUNT}.
 */
@Component
class AdjustmentPosting {
    private static final Map<String, Set<String>> KINDS_BY_REASON = Map.of(
            "DAMAGE", Set.of("DECREASE"),
            "EXPIRY", Set.of("DECREASE"),
            "SAMPLE", Set.of("DECREASE"),
            "FOUND", Set.of("INCREASE", "FOUND"),
            "COUNT_VARIANCE", Set.of("INCREASE", "DECREASE", "FOUND"),
            "OTHER", Set.of("INCREASE", "DECREASE", "FOUND"));

    private final Store s;
    private final InventoryRules rules;

    AdjustmentPosting(Store s, InventoryRules rules) {
        this.s = s;
        this.rules = rules;
    }

    /** Validates and stores a {@code DRAFT} adjustment; returns its id. */
    String insert(Actor a, String reason, String notes, List<InventoryCommands.AdjustmentLine> lines) {
        require(!lines.isEmpty(), "Add at least one adjustment line");
        String id = id();
        String number = rules.documentNumber(a, "stock_adjustments", "ADJ");
        s.db().update(
                        "INSERT INTO stock_adjustments(id,tenant_id,number,reason,notes,created_by) VALUES(?,?,?,?,?,?)",
                        id,
                        a.tenant(),
                        number,
                        reason,
                        notes == null ? "" : notes.trim(),
                        a.id());
        var lots = new HashSet<String>();
        var lotNumbers = new HashSet<String>();
        int lineNo = 0;
        for (var line : lines) {
            lineNo++;
            String prefix = "Line " + lineNo + ": ";
            require(
                    KINDS_BY_REASON.get(reason).contains(line.kind()),
                    prefix + reasonLabel(reason) + " adjustments cannot " + kindVerb(line.kind()));
            if (line.kind().equals("FOUND")) {
                require(blankToNull(line.itemId()) != null, prefix + "choose the item that was found");
                require(blankToNull(line.warehouseId()) != null, prefix + "choose the warehouse it was found in");
                require(line.unitCost() != null, prefix + "enter the unit cost of the found stock");
                var item = s.one("SELECT * FROM items WHERE tenant_id=? AND id=?", a.tenant(), line.itemId());
                require(Boolean.TRUE.equals(item.get("active")), prefix + "item " + item.get("sku") + " is inactive");
                var warehouse =
                        s.one("SELECT * FROM warehouses WHERE tenant_id=? AND id=?", a.tenant(), line.warehouseId());
                require(
                        Boolean.TRUE.equals(warehouse.get("active")),
                        prefix + "warehouse " + warehouse.get("name") + " is inactive");
                s.quantity(str(item, "uom"), line.quantity());
                String lotNumber = blankToNull(line.lotNumber());
                if (lotNumber != null) {
                    require(
                            lotNumbers.add(lotNumber) && !rules.lotNumberTaken(a, lotNumber),
                            prefix + "lot number " + lotNumber + " is already used");
                }
                s.db().update(
                                "INSERT INTO stock_adjustment_lines(id,tenant_id,adjustment_id,line_no,kind,item_id,warehouse_id,quantity,unit_cost,lot_number) VALUES(?,?,?,?,'FOUND',?,?,?,?,?)",
                                id(),
                                a.tenant(),
                                id,
                                lineNo,
                                line.itemId(),
                                line.warehouseId(),
                                line.quantity(),
                                line.unitCost(),
                                lotNumber);
            } else {
                require(blankToNull(line.lotId()) != null, prefix + "choose a lot");
                var lot = rules.lot(a, line.lotId());
                require(lots.add(line.lotId()), prefix + "lot " + lot.get("number") + " appears more than once");
                checkLot(lot, line.kind(), line.quantity(), prefix);
                s.db().update(
                                "INSERT INTO stock_adjustment_lines(id,tenant_id,adjustment_id,line_no,kind,lot_id,item_id,warehouse_id,quantity,unit_cost) VALUES(?,?,?,?,?,?,?,?,?,?)",
                                id(),
                                a.tenant(),
                                id,
                                lineNo,
                                line.kind(),
                                line.lotId(),
                                lot.get("item_id"),
                                lot.get("warehouse_id"),
                                line.quantity(),
                                lot.get("unit_cost"));
            }
        }
        var value = valuate(a, id);
        s.db().update(
                        "UPDATE stock_adjustments SET value_increase=?,value_decrease=? WHERE tenant_id=? AND id=?",
                        value.increase(),
                        value.decrease(),
                        a.tenant(),
                        id);
        s.audit(
                a,
                "ADJUSTMENT_CREATED",
                number,
                reasonLabel(reason) + " · " + lines.size() + (lines.size() == 1 ? " line" : " lines"));
        return id;
    }

    /**
     * Posts a draft, or parks it as {@code APPROVAL_REQUIRED} when its gross value exceeds the threshold. Returns
     * {@code {id, number, status, value_increase, value_decrease, approval_threshold}}.
     */
    Map<String, Object> submit(Actor a, String id) {
        s.openPeriod(a);
        var adjustment = header(a, id);
        require(adjustment.get("status").equals("DRAFT"), "Only draft adjustments can be posted");
        var value = valuate(a, id);
        BigDecimal threshold = rules.threshold(a);
        String number = str(adjustment, "number");
        if (value.gross().compareTo(threshold) > 0) {
            s.db().update(
                            "UPDATE stock_adjustments SET status='APPROVAL_REQUIRED',submitted_by=?,submitted_at=now(),approval_threshold=?,value_increase=?,value_decrease=? WHERE tenant_id=? AND id=?",
                            a.id(),
                            threshold,
                            value.increase(),
                            value.decrease(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "ADJUSTMENT_APPROVAL_REQUESTED",
                    number,
                    "Gross value Rs. " + money(value.gross()).toPlainString() + " exceeds the Rs. "
                            + money(threshold).toPlainString() + " approval threshold");
            return result(a, id);
        }
        s.db().update(
                        "UPDATE stock_adjustments SET submitted_by=?,submitted_at=now(),approval_threshold=? WHERE tenant_id=? AND id=?",
                        a.id(),
                        threshold,
                        a.tenant(),
                        id);
        post(a, adjustment, null);
        return result(a, id);
    }

    /** Posts an {@code APPROVAL_REQUIRED} adjustment; the approver must differ from its creator and submitter. */
    Map<String, Object> approve(Actor a, String id) {
        s.openPeriod(a);
        var adjustment = header(a, id);
        require(
                adjustment.get("status").equals("APPROVAL_REQUIRED"),
                "Only adjustments awaiting approval can be approved");
        require(
                !a.id().equals(adjustment.get("created_by")) && !a.id().equals(adjustment.get("submitted_by")),
                "A different authorized person must approve this adjustment");
        post(a, adjustment, a.id());
        return result(a, id);
    }

    Map<String, Object> cancel(Actor a, String id) {
        var adjustment = header(a, id);
        require(
                Set.of("DRAFT", "APPROVAL_REQUIRED").contains(adjustment.get("status")),
                "Only draft adjustments or adjustments awaiting approval can be cancelled");
        s.db().update(
                        "UPDATE stock_adjustments SET status='CANCELLED',cancelled_by=?,cancelled_at=now() WHERE tenant_id=? AND id=?",
                        a.id(),
                        a.tenant(),
                        id);
        s.audit(a, "ADJUSTMENT_CANCELLED", str(adjustment, "number"), "Was " + adjustment.get("status"));
        return result(a, id);
    }

    // ------------------------------------------------------------------ internals

    private Map<String, Object> header(Actor a, String id) {
        return s.one("SELECT * FROM stock_adjustments WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    private Map<String, Object> result(Actor a, String id) {
        var row = s.one(
                "SELECT id,number,status,value_increase,value_decrease,approval_threshold FROM stock_adjustments WHERE tenant_id=? AND id=?",
                a.tenant(),
                id);
        return new LinkedHashMap<>(row);
    }

    record Value(BigDecimal increase, BigDecimal decrease) {
        BigDecimal gross() {
            return increase.add(decrease);
        }
    }

    /** Re-checks every line against current stock and returns the values posting would produce now. */
    private Value valuate(Actor a, String id) {
        BigDecimal increase = BigDecimal.ZERO;
        BigDecimal decrease = BigDecimal.ZERO;
        for (var line : lines(a, id)) {
            String prefix = "Line " + line.get("line_no") + ": ";
            BigDecimal qty = dec(line.get("quantity"));
            if (line.get("kind").equals("FOUND")) {
                increase = increase.add(money(qty.multiply(dec(line.get("unit_cost")))));
                continue;
            }
            var lot = rules.lot(a, str(line, "lot_id"));
            checkLot(lot, str(line, "kind"), qty, prefix);
            BigDecimal value = estimate(lot, line.get("kind").equals("DECREASE") ? qty.negate() : qty);
            if (value.signum() < 0) decrease = decrease.add(value.negate());
            else increase = increase.add(value);
        }
        return new Value(increase, decrease);
    }

    private void checkLot(Map<String, Object> lot, String kind, BigDecimal qty, String prefix) {
        requireMovable(lot, prefix);
        s.quantity(str(lot, "uom"), qty);
        if (kind.equals("DECREASE"))
            require(
                    qty.compareTo(available(lot)) <= 0,
                    prefix + "lot " + lot.get("number") + " has only "
                            + quantityText(available(lot), lot.get("uom")) + " available ("
                            + quantityText(dec(lot.get("reserved")), lot.get("uom"))
                            + " reserved); reserved stock cannot be adjusted");
        else
            require(
                    Boolean.TRUE.equals(lot.get("warehouse_active")),
                    prefix + "warehouse " + lot.get("warehouse") + " is inactive");
    }

    private List<Map<String, Object>> lines(Actor a, String id) {
        return s.list(
                "SELECT * FROM stock_adjustment_lines WHERE tenant_id=? AND adjustment_id=? ORDER BY line_no",
                a.tenant(),
                id);
    }

    private void post(Actor a, Map<String, Object> adjustment, String approver) {
        String id = str(adjustment, "id");
        String number = str(adjustment, "number");
        String reason = str(adjustment, "reason");
        String ledgerReason = "ADJUSTMENT_" + reason;
        // account -> {gains, losses}
        var totals = new TreeMap<String, BigDecimal[]>();
        BigDecimal increase = BigDecimal.ZERO;
        BigDecimal decrease = BigDecimal.ZERO;
        for (var line : lines(a, id)) {
            String prefix = "Line " + line.get("line_no") + ": ";
            BigDecimal qty = dec(line.get("quantity"));
            String lotId;
            String itemType;
            BigDecimal value;
            if (line.get("kind").equals("FOUND")) {
                var item = s.one("SELECT * FROM items WHERE tenant_id=? AND id=?", a.tenant(), line.get("item_id"));
                var warehouse = s.one(
                        "SELECT * FROM warehouses WHERE tenant_id=? AND id=?", a.tenant(), line.get("warehouse_id"));
                require(
                        Boolean.TRUE.equals(warehouse.get("active")),
                        prefix + "warehouse " + warehouse.get("name") + " is inactive");
                String lotNumber =
                        line.get("lot_number") == null ? rules.generatedLotNumber(a, "FND") : str(line, "lot_number");
                require(!rules.lotNumberTaken(a, lotNumber), prefix + "lot number " + lotNumber + " is already used");
                lotId = id();
                s.db().update(
                                "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,'RELEASED','ADJUSTMENT',?,?)",
                                lotId,
                                a.tenant(),
                                lotNumber,
                                line.get("item_id"),
                                line.get("warehouse_id"),
                                id,
                                line.get("unit_cost"));
                value = s.ledger(a, lotId, qty, number, ledgerReason);
                itemType = str(item, "type");
            } else {
                lotId = str(line, "lot_id");
                var lot = rules.lot(a, lotId);
                checkLot(lot, str(line, "kind"), qty, prefix);
                value = s.ledger(
                        a, lotId, line.get("kind").equals("DECREASE") ? qty.negate() : qty, number, ledgerReason);
                itemType = str(lot, "item_type");
            }
            s.db().update(
                            "UPDATE stock_adjustment_lines SET lot_id=?,value_delta=? WHERE tenant_id=? AND id=?",
                            lotId,
                            value,
                            a.tenant(),
                            line.get("id"));
            var account = totals.computeIfAbsent(
                    inventoryAccount(itemType), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            if (value.signum() >= 0) {
                account[0] = account[0].add(value);
                increase = increase.add(value);
            } else {
                account[1] = account[1].add(value.negate());
                decrease = decrease.add(value.negate());
            }
        }
        String label = reasonLabel(reason);
        for (var entry : totals.entrySet()) {
            s.journal(
                    a,
                    number,
                    "Stock adjustment gain · " + label,
                    entry.getKey(),
                    InventoryRules.ADJUSTMENT_ACCOUNT,
                    entry.getValue()[0]);
            s.journal(
                    a,
                    number,
                    "Stock adjustment loss · " + label,
                    InventoryRules.ADJUSTMENT_ACCOUNT,
                    entry.getKey(),
                    entry.getValue()[1]);
        }
        s.db().update(
                        "UPDATE stock_adjustments SET status='POSTED',posted_at=now(),approved_by=?,value_increase=?,value_decrease=? WHERE tenant_id=? AND id=?",
                        approver,
                        increase,
                        decrease,
                        a.tenant(),
                        id);
        s.audit(
                a,
                approver == null ? "ADJUSTMENT_POSTED" : "ADJUSTMENT_APPROVED",
                number,
                label + " · +Rs. " + money(increase).toPlainString() + " / −Rs. "
                        + money(decrease).toPlainString());
    }

    static String reasonLabel(String reason) {
        return switch (reason) {
            case "DAMAGE" -> "Damage";
            case "EXPIRY" -> "Expiry";
            case "FOUND" -> "Found stock";
            case "COUNT_VARIANCE" -> "Count variance";
            case "SAMPLE" -> "Sample";
            default -> "Other";
        };
    }

    private static String kindVerb(String kind) {
        return switch (kind) {
            case "INCREASE" -> "increase a lot";
            case "DECREASE" -> "decrease a lot";
            default -> "add found stock";
        };
    }
}
