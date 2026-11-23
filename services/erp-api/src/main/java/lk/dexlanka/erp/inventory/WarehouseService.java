package lk.dexlanka.erp.inventory;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warehouses of the tenant's plant: create and edit (name, code, kind, active).
 *
 * <p>Compatibility with the default warehouses: manufacturing posts finished output into the first {@code FINISHED}
 * warehouse by id and goods receipts need a {@code RAW} warehouse. So a new {@code FINISHED} warehouse gets an id that
 * sorts after the current production-output warehouse, no edit may change which warehouse that is, the production
 * output warehouse stays active and {@code FINISHED}, and at least one active {@code RAW} warehouse remains. A warehouse
 * that holds stock (or has open inventory documents) cannot be deactivated, and its kind changes only while empty.
 */
@Service
public class WarehouseService {
    private final Store s;
    private final InventoryRules rules;

    WarehouseService(Store s, InventoryRules rules) {
        this.s = s;
        this.rules = rules;
    }

    @Transactional
    public Map<String, Object> create(Actor a, InventoryCommands.Warehouse c, String key) {
        a.allow("PLANNER", "WAREHOUSE");
        return s.once(a, key, "inventory-warehouse", c, () -> {
            String code = c.code().toUpperCase(Locale.ROOT);
            requireFreeCode(a, code, null);
            var plant = s.one("SELECT id FROM factories WHERE tenant_id=? ORDER BY id LIMIT 1", a.tenant());
            String production = rules.productionWarehouse(a);
            String id =
                    c.kind().equals("FINISHED") && production != null ? production + "-" + id().substring(0, 8) : id();
            s.db().update(
                            "INSERT INTO warehouses(id,tenant_id,factory_id,name,kind,code) VALUES(?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            plant.get("id"),
                            c.name().trim(),
                            c.kind(),
                            code);
            if (production != null)
                require(
                        production.equals(rules.productionWarehouse(a)),
                        "The new warehouse would replace the production output warehouse");
            s.audit(a, "WAREHOUSE_CREATED", code, c.name().trim() + " · " + c.kind());
            return Map.of("id", id, "code", code, "active", true);
        });
    }

    @Transactional
    public Map<String, Object> update(Actor a, String id, InventoryCommands.WarehouseUpdate c, String key) {
        a.allow("PLANNER", "WAREHOUSE");
        return s.once(a, key, "inventory-warehouse:" + id, c, () -> {
            var w = s.one("SELECT * FROM warehouses WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
            String code = c.code().toUpperCase(Locale.ROOT);
            requireFreeCode(a, code, id);
            boolean kindChanged = !c.kind().equals(w.get("kind"));
            boolean deactivating = Boolean.TRUE.equals(w.get("active")) && !c.active();
            String production = rules.productionWarehouse(a);
            if (kindChanged || deactivating) {
                require(
                        !id.equals(production),
                        str(w, "name")
                                + " receives production output; it must stay an active finished-goods warehouse");
                var stock = s.one(
                        "SELECT COUNT(*) AS lots FROM lots l JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id WHERE l.tenant_id=? AND l.warehouse_id=? AND b.quantity>0",
                        a.tenant(),
                        id);
                require(
                        dec(stock.get("lots")).signum() == 0,
                        deactivating
                                ? "A warehouse that holds stock can't be deactivated; transfer or adjust its stock first"
                                : "Empty the warehouse before changing its kind");
                if ("RAW".equals(w.get("kind")))
                    require(
                            !s.list(
                                            "SELECT 1 FROM warehouses WHERE tenant_id=? AND kind='RAW' AND active AND id<>?",
                                            a.tenant(),
                                            id)
                                    .isEmpty(),
                            "Keep at least one active raw-material warehouse for goods receipts");
            }
            if (deactivating)
                require(
                        openDocuments(a, id) == 0,
                        "Post or cancel the open adjustments, transfers and counts of this warehouse first");
            s.db().update(
                            "UPDATE warehouses SET name=?,code=?,kind=?,active=? WHERE tenant_id=? AND id=?",
                            c.name().trim(),
                            code,
                            c.kind(),
                            c.active(),
                            a.tenant(),
                            id);
            require(
                    Objects.equals(production, rules.productionWarehouse(a)),
                    "This change would make " + c.name().trim()
                            + " the production output warehouse; create a new finished-goods warehouse instead");
            var changes = new ArrayList<String>();
            if (!c.name().trim().equals(w.get("name")))
                changes.add("name " + c.name().trim());
            if (!code.equals(w.get("code"))) changes.add("code " + w.get("code") + " → " + code);
            if (kindChanged) changes.add("kind " + w.get("kind") + " → " + c.kind());
            if (deactivating) changes.add("deactivated");
            if (!Boolean.TRUE.equals(w.get("active")) && c.active()) changes.add("reactivated");
            s.audit(a, "WAREHOUSE_UPDATED", code, changes.isEmpty() ? "No changes" : String.join("; ", changes));
            return Map.of("id", id, "code", code, "active", c.active());
        });
    }

    private void requireFreeCode(Actor a, String code, String self) {
        require(
                s.list(
                                "SELECT 1 FROM warehouses WHERE tenant_id=? AND code=? AND (?::text IS NULL OR id<>?)",
                                a.tenant(),
                                code,
                                self,
                                self)
                        .isEmpty(),
                "Warehouse code " + code + " is already used");
    }

    private long openDocuments(Actor a, String warehouse) {
        return s.db().queryForObject(
                        """
                        SELECT (SELECT COUNT(*) FROM stock_adjustments d
                                WHERE d.tenant_id=? AND d.status IN ('DRAFT','APPROVAL_REQUIRED')
                                  AND EXISTS (SELECT 1 FROM stock_adjustment_lines x
                                              WHERE x.tenant_id=d.tenant_id AND x.adjustment_id=d.id AND x.warehouse_id=?))
                             + (SELECT COUNT(*) FROM stock_transfers
                                WHERE tenant_id=? AND status='DRAFT' AND (from_warehouse_id=? OR to_warehouse_id=?))
                             + (SELECT COUNT(*) FROM stock_counts
                                WHERE tenant_id=? AND status IN ('COUNTING','REVIEW') AND warehouse_id=?)""",
                        Long.class,
                        a.tenant(),
                        warehouse,
                        a.tenant(),
                        warehouse,
                        warehouse,
                        a.tenant(),
                        warehouse);
    }
}
