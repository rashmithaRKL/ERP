package lk.dexlanka.erp.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.platform.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared fixture for the inventory-operations tests: a fresh tenant (RAW + FINISHED warehouse), users per role, three
 * items (kg resin, pcs carton, pcs finished bottle) and 100 kg of released resin at Rs. 10 in the RAW warehouse.
 */
abstract class InventoryTestSupport extends IntegrationTestBase {
    @Autowired
    AdjustmentService adjustments;

    @Autowired
    TransferService transfers;

    @Autowired
    CountService counts;

    @Autowired
    WarehouseService warehouses;

    @Autowired
    InventoryQuery inventory;

    TestTenant tenant;
    String t, raw, finished, resin, carton, bottle, resinLot;
    Actor admin, planner, warehouse, operator;

    @BeforeEach
    void inventoryFixture() {
        tenant = createTenant();
        t = tenant.id();
        raw = tenant.rawWarehouse();
        finished = tenant.finishedWarehouse();
        admin = createUser(t, "ADMIN");
        planner = createUser(t, "PLANNER");
        warehouse = createUser(t, "WAREHOUSE");
        operator = createUser(t, "OPERATOR");
        resin = id();
        carton = id();
        bottle = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'RM','Resin','RAW_MATERIAL','kg',10,0),(?,?,'PK','Carton','PACKAGING','pcs',2,0),(?,?,'FG','Bottle','FINISHED_GOOD','pcs',5,0)",
                resin,
                t,
                carton,
                t,
                bottle,
                t);
        resinLot = openingStock(admin, resin, raw, "100", "10");
    }

    // ------------------------------------------------------------------ builders

    static InventoryCommands.AdjustmentLine decrease(String lot, String qty) {
        return new InventoryCommands.AdjustmentLine("DECREASE", lot, null, null, bd(qty), null, null);
    }

    static InventoryCommands.AdjustmentLine increase(String lot, String qty) {
        return new InventoryCommands.AdjustmentLine("INCREASE", lot, null, null, bd(qty), null, null);
    }

    static InventoryCommands.AdjustmentLine found(
            String item, String warehouse, String qty, String unitCost, String lotNumber) {
        return new InventoryCommands.AdjustmentLine("FOUND", null, item, warehouse, bd(qty), bd(unitCost), lotNumber);
    }

    static InventoryCommands.Adjustment adjustment(String reason, InventoryCommands.AdjustmentLine... lines) {
        return new InventoryCommands.Adjustment(reason, "Test adjustment", List.of(lines));
    }

    String draft(Actor actor, String reason, InventoryCommands.AdjustmentLine... lines) {
        return str(adjustments.create(actor, adjustment(reason, lines), id()), "id");
    }

    Map<String, Object> post(Actor actor, String adjustment) {
        return adjustments.post(actor, adjustment, id());
    }

    String transferDraft(Actor actor, String from, String to, String lot, String qty) {
        return str(
                transfers.create(
                        actor,
                        new InventoryCommands.Transfer(
                                from, to, "Test transfer", List.of(new InventoryCommands.TransferLine(lot, bd(qty)))),
                        id()),
                "id");
    }

    String newWarehouse(String name, String code, String kind) {
        return str(warehouses.create(admin, new InventoryCommands.Warehouse(name, code, kind), id()), "id");
    }

    /** A lot in {@code status} posted through the real ledger (no journal). */
    String lot(String item, String warehouseId, String status, String sourceType, String qty, String unitCost) {
        String lot = id();
        update(
                "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,?,?,'TEST',?)",
                lot,
                t,
                "LOT-" + lot.substring(0, 8),
                item,
                warehouseId,
                status,
                sourceType,
                bd(unitCost));
        s.ledger(admin, lot, bd(qty), "TEST", "TEST_STOCK");
        return lot;
    }

    BigDecimal quantity(String lot) {
        return decimal("SELECT quantity FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lot);
    }

    BigDecimal value(String lot) {
        return decimal("SELECT value_amount FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lot);
    }

    void reserve(String lot, String qty) {
        update("UPDATE stock_balances SET reserved=? WHERE tenant_id=? AND lot_id=?", bd(qty), t, lot);
    }

    /** Sum of debits minus credits on {@code account} for journal entries with {@code reference}. */
    BigDecimal journal(String reference, String account) {
        return decimal(
                "SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id WHERE e.tenant_id=? AND e.reference=? AND l.account=?",
                t,
                reference,
                account);
    }

    String number(String table, String id) {
        return str(row("SELECT number FROM " + table + " WHERE tenant_id=? AND id=?", t, id), "number");
    }
}
