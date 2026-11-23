package lk.dexlanka.erp.inventory;

import static lk.dexlanka.erp.inventory.InventoryRules.blankToNull;
import static lk.dexlanka.erp.inventory.InventoryRules.estimate;
import static lk.dexlanka.erp.inventory.InventoryRules.quantityText;
import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cycle counts: COUNTING (system quantity snapshotted per lot at start; blind counts hide it) → REVIEW (quantity and
 * value variances visible) → POSTED, or CANCELLED. Posting compares the counted quantity with the system quantity
 * <em>at posting time</em>, flags lots that moved after the snapshot, and turns the variances into a {@code
 * COUNT_VARIANCE} adjustment submitted through the same posting path and approval threshold as manual adjustments. A
 * lot can be in at most one open count (unique partial index {@code stock_count_lines_one_open_count}).
 */
@Service
public class CountService {
    private final Store s;
    private final InventoryRules rules;
    private final AdjustmentPosting adjustments;

    CountService(Store s, InventoryRules rules, AdjustmentPosting adjustments) {
        this.s = s;
        this.rules = rules;
        this.adjustments = adjustments;
    }

    private static final String COUNTABLE = """
            FROM lots l
            JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
            JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
            WHERE l.tenant_id=? AND l.warehouse_id=? AND b.quantity>0
              AND NOT (l.status='QUARANTINE' AND l.source_type='PURCHASE')
              AND (?::text IS NULL OR l.item_id=?)
              AND (?::text IS NULL OR i.type=?)""";

    @Transactional
    public Map<String, Object> start(Actor a, InventoryCommands.Count c, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "count", c, () -> {
            var warehouse = s.one("SELECT * FROM warehouses WHERE tenant_id=? AND id=?", a.tenant(), c.warehouseId());
            require(
                    Boolean.TRUE.equals(warehouse.get("active")),
                    "Warehouse " + warehouse.get("name") + " is inactive");
            String item = blankToNull(c.itemId());
            String type = blankToNull(c.itemType());
            if (item != null) s.one("SELECT id FROM items WHERE tenant_id=? AND id=?", a.tenant(), item);
            Object[] filter = {a.tenant(), c.warehouseId(), item, item, type, type};
            var busy = s.list("""
                    SELECT lot.number,c.number AS count_number
                    FROM stock_count_lines cl
                    JOIN stock_counts c ON c.tenant_id=cl.tenant_id AND c.id=cl.count_id
                    JOIN lots lot ON lot.tenant_id=cl.tenant_id AND lot.id=cl.lot_id
                    WHERE cl.open AND cl.lot_id IN """ + "(SELECT l.id " + COUNTABLE + ") ORDER BY lot.number LIMIT 10", filter);
            require(
                    busy.isEmpty(),
                    "Lots already in an open count: "
                            + String.join(
                                    ", ",
                                    busy.stream()
                                            .map(r -> r.get("number") + " (" + r.get("count_number") + ")")
                                            .toList()));
            var lots = s.list(
                    "SELECT l.id,l.item_id,l.unit_cost,b.quantity " + COUNTABLE + " ORDER BY i.name,l.created_at,l.id",
                    filter);
            require(!lots.isEmpty(), "There is no countable stock in " + warehouse.get("name") + " for this selection");
            String id = id();
            String number = rules.documentNumber(a, "stock_counts", "CNT");
            s.db().update(
                            "INSERT INTO stock_counts(id,tenant_id,number,warehouse_id,item_id,item_type,blind,notes,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            number,
                            c.warehouseId(),
                            item,
                            type,
                            c.blind(),
                            c.notes() == null ? "" : c.notes().trim(),
                            a.id());
            int lineNo = 0;
            for (var lot : lots)
                s.db().update(
                                "INSERT INTO stock_count_lines(id,tenant_id,count_id,line_no,lot_id,item_id,snapshot_qty,unit_cost) VALUES(?,?,?,?,?,?,?,?)",
                                id(),
                                a.tenant(),
                                id,
                                ++lineNo,
                                lot.get("id"),
                                lot.get("item_id"),
                                lot.get("quantity"),
                                lot.get("unit_cost"));
            s.audit(
                    a,
                    "COUNT_STARTED",
                    number,
                    warehouse.get("name") + " · " + lots.size() + (lots.size() == 1 ? " lot" : " lots")
                            + (c.blind() ? " · blind count" : ""));
            return result(a, id);
        });
    }

    /** Records counted quantities (any subset of lines, repeatable) while the count is COUNTING. */
    @Transactional
    public Map<String, Object> enter(Actor a, String id, InventoryCommands.CountEntries c, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "count-entries:" + id, c, () -> {
            var count = header(a, id);
            require(
                    count.get("status").equals("COUNTING"),
                    "Counting is closed for " + count.get("number") + "; reopen it to change quantities");
            var seen = new HashSet<String>();
            for (var entry : c.entries()) {
                require(seen.add(entry.lineId()), "Each lot can be entered only once per save");
                var line = s.one(
                        "SELECT cl.*,i.uom FROM stock_count_lines cl JOIN items i ON i.tenant_id=cl.tenant_id AND i.id=cl.item_id WHERE cl.tenant_id=? AND cl.count_id=? AND cl.id=?",
                        a.tenant(),
                        id,
                        entry.lineId());
                s.quantity(str(line, "uom"), entry.countedQty());
                s.db().update(
                                "UPDATE stock_count_lines SET counted_qty=?,counted_by=?,counted_at=now() WHERE tenant_id=? AND id=?",
                                entry.countedQty(),
                                a.id(),
                                a.tenant(),
                                entry.lineId());
            }
            s.audit(
                    a,
                    "COUNT_QUANTITIES_RECORDED",
                    str(count, "number"),
                    c.entries().size() + (c.entries().size() == 1 ? " lot" : " lots") + " counted");
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> submit(Actor a, String id, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "submit-count:" + id, Map.of(), () -> {
            var count = header(a, id);
            require(count.get("status").equals("COUNTING"), "Only a count in progress can be submitted for review");
            long remaining = s.db().queryForObject(
                            "SELECT COUNT(*) FROM stock_count_lines WHERE tenant_id=? AND count_id=? AND counted_qty IS NULL",
                            Long.class,
                            a.tenant(),
                            id);
            require(
                    remaining == 0,
                    "Enter a counted quantity for every lot (0 when nothing is found); " + remaining
                            + (remaining == 1 ? " lot is" : " lots are") + " still uncounted");
            s.db().update(
                            "UPDATE stock_counts SET status='REVIEW',submitted_by=?,submitted_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(a, "COUNT_SUBMITTED", str(count, "number"), "Ready for variance review");
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> reopen(Actor a, String id, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "reopen-count:" + id, Map.of(), () -> {
            var count = header(a, id);
            require(count.get("status").equals("REVIEW"), "Only a count under review can be reopened");
            s.db().update(
                            "UPDATE stock_counts SET status='COUNTING',submitted_by=NULL,submitted_at=NULL WHERE tenant_id=? AND id=?",
                            a.tenant(),
                            id);
            s.audit(a, "COUNT_REOPENED", str(count, "number"), "Returned for recount");
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> post(Actor a, String id, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "post-count:" + id, Map.of(), () -> {
            s.openPeriod(a);
            var count = header(a, id);
            require(count.get("status").equals("REVIEW"), "Submit the count for review before posting it");
            String number = str(count, "number");
            var lines = new ArrayList<InventoryCommands.AdjustmentLine>();
            var moved = new ArrayList<String>();
            for (var line : s.list("""
                    SELECT cl.*,EXISTS (SELECT 1 FROM stock_ledger e
                                        WHERE e.tenant_id=cl.tenant_id AND e.lot_id=cl.lot_id AND e.created_at>=c.created_at) AS activity
                    FROM stock_count_lines cl
                    JOIN stock_counts c ON c.tenant_id=cl.tenant_id AND c.id=cl.count_id
                    WHERE cl.tenant_id=? AND cl.count_id=?
                    ORDER BY cl.line_no""", a.tenant(), id)) {
                var lot = rules.lot(a, str(line, "lot_id"));
                BigDecimal system = dec(lot.get("quantity"));
                BigDecimal counted = dec(line.get("counted_qty"));
                BigDecimal variance = counted.subtract(system);
                boolean lotMoved = system.compareTo(dec(line.get("snapshot_qty"))) != 0
                        || Boolean.TRUE.equals(line.get("activity"));
                if (lotMoved) moved.add(str(lot, "number"));
                if (variance.signum() < 0)
                    require(
                            counted.compareTo(dec(lot.get("reserved"))) >= 0,
                            "Lot " + lot.get("number") + ": the counted " + quantityText(counted, lot.get("uom"))
                                    + " is below the reserved " + quantityText(dec(lot.get("reserved")), lot.get("uom"))
                                    + "; release the reservation before posting");
                s.db().update(
                                "UPDATE stock_count_lines SET system_qty=?,variance_qty=?,variance_value=?,moved=?,open=false WHERE tenant_id=? AND id=?",
                                system,
                                variance,
                                estimate(lot, variance),
                                lotMoved,
                                a.tenant(),
                                line.get("id"));
                if (variance.signum() != 0)
                    lines.add(new InventoryCommands.AdjustmentLine(
                            variance.signum() > 0 ? "INCREASE" : "DECREASE",
                            str(line, "lot_id"),
                            null,
                            null,
                            variance.abs(),
                            null,
                            null));
            }
            Map<String, Object> adjustment = null;
            if (!lines.isEmpty()) {
                String adjustmentId = adjustments.insert(
                        a,
                        "COUNT_VARIANCE",
                        "Cycle count " + number
                                + (moved.isEmpty()
                                        ? ""
                                        : "; stock moved after the snapshot: " + String.join(", ", moved)),
                        lines);
                adjustment = adjustments.submit(a, adjustmentId);
            }
            s.db().update(
                            "UPDATE stock_counts SET status='POSTED',posted_by=?,posted_at=now(),adjustment_id=? WHERE tenant_id=? AND id=?",
                            a.id(),
                            adjustment == null ? null : adjustment.get("id"),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "COUNT_POSTED",
                    number,
                    lines.isEmpty()
                            ? "No variances"
                            : lines.size() + (lines.size() == 1 ? " variance → " : " variances → ")
                                    + adjustment.get("number") + " (" + adjustment.get("status") + ")");
            var result = result(a, id);
            result.put("adjustment", adjustment);
            result.put("moved", moved);
            return result;
        });
    }

    @Transactional
    public Map<String, Object> cancel(Actor a, String id, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "cancel-count:" + id, Map.of(), () -> {
            var count = header(a, id);
            require(Set.of("COUNTING", "REVIEW").contains(count.get("status")), "Only an open count can be cancelled");
            s.db().update("UPDATE stock_count_lines SET open=false WHERE tenant_id=? AND count_id=?", a.tenant(), id);
            s.db().update(
                            "UPDATE stock_counts SET status='CANCELLED',cancelled_by=?,cancelled_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(a, "COUNT_CANCELLED", str(count, "number"), "No stock was changed");
            return result(a, id);
        });
    }

    private Map<String, Object> header(Actor a, String id) {
        return s.one("SELECT * FROM stock_counts WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    private Map<String, Object> result(Actor a, String id) {
        var row = new LinkedHashMap<String, Object>(s.one(
                "SELECT c.id,c.number,c.status,(SELECT COUNT(*) FROM stock_count_lines l WHERE l.tenant_id=c.tenant_id AND l.count_id=c.id) AS lines,(SELECT COUNT(*) FROM stock_count_lines l WHERE l.tenant_id=c.tenant_id AND l.count_id=c.id AND l.counted_qty IS NOT NULL) AS counted FROM stock_counts c WHERE c.tenant_id=? AND c.id=?",
                a.tenant(),
                id));
        return row;
    }
}
