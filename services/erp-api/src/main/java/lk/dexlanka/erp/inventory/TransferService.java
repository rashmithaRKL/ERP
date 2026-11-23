package lk.dexlanka.erp.inventory;

import static lk.dexlanka.erp.inventory.InventoryRules.available;
import static lk.dexlanka.erp.inventory.InventoryRules.quantityText;
import static lk.dexlanka.erp.inventory.InventoryRules.requireMovable;
import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warehouse transfers (DRAFT → POSTED, or CANCELLED). A lot belongs to one warehouse, so posting depletes the source
 * lot and creates a destination lot with the same item, quality status, unit cost and FIFO age ({@code created_at}),
 * linked by {@code source_type='TRANSFER'}, {@code source_id=<source lot>}. The ledger pair is value-neutral (the
 * destination receives exactly the value removed from the source). No journal is posted: inventory accounts follow the
 * item type, not the warehouse (see {@link InventoryRules}). A quarantined destination lot follows the source lot's
 * pending inspection (database trigger {@code transferred_lots_follow_quality}). Reserved stock cannot be transferred.
 */
@Service
public class TransferService {
    private final Store s;
    private final InventoryRules rules;

    TransferService(Store s, InventoryRules rules) {
        this.s = s;
        this.rules = rules;
    }

    @Transactional
    public Map<String, Object> create(Actor a, InventoryCommands.Transfer c, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "transfer", c, () -> {
            var from = s.one("SELECT * FROM warehouses WHERE tenant_id=? AND id=?", a.tenant(), c.fromWarehouseId());
            var to = s.one("SELECT * FROM warehouses WHERE tenant_id=? AND id=?", a.tenant(), c.toWarehouseId());
            require(!c.fromWarehouseId().equals(c.toWarehouseId()), "Choose two different warehouses");
            require(
                    Boolean.TRUE.equals(to.get("active")),
                    "The destination warehouse " + to.get("name") + " is inactive");
            String id = id();
            String number = rules.documentNumber(a, "stock_transfers", "TRF");
            s.db().update(
                            "INSERT INTO stock_transfers(id,tenant_id,number,from_warehouse_id,to_warehouse_id,notes,created_by) VALUES(?,?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            number,
                            c.fromWarehouseId(),
                            c.toWarehouseId(),
                            c.notes() == null ? "" : c.notes().trim(),
                            a.id());
            var seen = new HashSet<String>();
            int lineNo = 0;
            for (var line : c.lines()) {
                lineNo++;
                String prefix = "Line " + lineNo + ": ";
                var lot = rules.lot(a, line.lotId());
                require(seen.add(line.lotId()), prefix + "lot " + lot.get("number") + " appears more than once");
                check(lot, from, line.quantity(), prefix);
                s.db().update(
                                "INSERT INTO stock_transfer_lines(id,tenant_id,transfer_id,line_no,source_lot_id,item_id,quantity) VALUES(?,?,?,?,?,?,?)",
                                id(),
                                a.tenant(),
                                id,
                                lineNo,
                                line.lotId(),
                                lot.get("item_id"),
                                line.quantity());
            }
            s.audit(
                    a,
                    "TRANSFER_CREATED",
                    number,
                    from.get("name") + " → " + to.get("name") + " · "
                            + c.lines().size() + (c.lines().size() == 1 ? " lot" : " lots"));
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> post(Actor a, String id, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "post-transfer:" + id, Map.of(), () -> {
            s.openPeriod(a);
            var t = s.one("SELECT * FROM stock_transfers WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
            require(t.get("status").equals("DRAFT"), "Only draft transfers can be posted");
            var from = s.one(
                    "SELECT * FROM warehouses WHERE tenant_id=? AND id=?", a.tenant(), t.get("from_warehouse_id"));
            var to = s.one("SELECT * FROM warehouses WHERE tenant_id=? AND id=?", a.tenant(), t.get("to_warehouse_id"));
            require(
                    Boolean.TRUE.equals(to.get("active")),
                    "The destination warehouse " + to.get("name") + " is inactive");
            String number = str(t, "number");
            BigDecimal total = BigDecimal.ZERO;
            for (var line : s.list(
                    "SELECT * FROM stock_transfer_lines WHERE tenant_id=? AND transfer_id=? ORDER BY line_no",
                    a.tenant(),
                    id)) {
                String prefix = "Line " + line.get("line_no") + ": ";
                BigDecimal qty = dec(line.get("quantity"));
                String sourceLot = str(line, "source_lot_id");
                var lot = rules.lot(a, sourceLot);
                check(lot, from, qty, prefix);
                BigDecimal value = s.ledger(a, sourceLot, qty.negate(), number, "TRANSFER_OUT")
                        .abs();
                String destination = id();
                s.db().update(
                                "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost,created_at) VALUES(?,?,?,?,?,?,'TRANSFER',?,?,?)",
                                destination,
                                a.tenant(),
                                rules.derivedLotNumber(a, str(lot, "number")),
                                lot.get("item_id"),
                                t.get("to_warehouse_id"),
                                lot.get("status"),
                                sourceLot,
                                lot.get("unit_cost"),
                                lot.get("created_at"));
                s.ledger(a, destination, qty, number, "TRANSFER_IN", value);
                s.db().update(
                                "UPDATE stock_transfer_lines SET destination_lot_id=?,value_amount=? WHERE tenant_id=? AND id=?",
                                destination,
                                value,
                                a.tenant(),
                                line.get("id"));
                total = total.add(value);
            }
            s.db().update(
                            "UPDATE stock_transfers SET status='POSTED',posted_by=?,posted_at=now(),total_value=? WHERE tenant_id=? AND id=?",
                            a.id(),
                            total,
                            a.tenant(),
                            id);
            s.audit(a, "TRANSFER_POSTED", number, from.get("name") + " → " + to.get("name") + " · value-neutral");
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> cancel(Actor a, String id, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "cancel-transfer:" + id, Map.of(), () -> {
            var t = s.one("SELECT * FROM stock_transfers WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
            require(Set.of("DRAFT").contains(t.get("status")), "Only draft transfers can be cancelled");
            s.db().update(
                            "UPDATE stock_transfers SET status='CANCELLED',cancelled_by=?,cancelled_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(a, "TRANSFER_CANCELLED", str(t, "number"), "Draft cancelled");
            return result(a, id);
        });
    }

    private void check(Map<String, Object> lot, Map<String, Object> from, BigDecimal qty, String prefix) {
        require(
                lot.get("warehouse_id").equals(from.get("id")),
                prefix + "lot " + lot.get("number") + " is not in " + from.get("name"));
        requireMovable(lot, prefix);
        s.quantity(str(lot, "uom"), qty);
        require(
                qty.compareTo(available(lot)) <= 0,
                prefix + "lot " + lot.get("number") + " has only " + quantityText(available(lot), lot.get("uom"))
                        + " available (" + quantityText(dec(lot.get("reserved")), lot.get("uom"))
                        + " reserved); reserved stock cannot be transferred");
    }

    private Map<String, Object> result(Actor a, String id) {
        return new LinkedHashMap<>(s.one(
                "SELECT id,number,status,total_value FROM stock_transfers WHERE tenant_id=? AND id=?", a.tenant(), id));
    }
}
