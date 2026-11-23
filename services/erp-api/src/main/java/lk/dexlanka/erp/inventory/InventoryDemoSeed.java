package lk.dexlanka.erp.inventory;

import static lk.dexlanka.erp.inventory.InventoryRules.available;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.demo.DemoPersonas;
import lk.dexlanka.erp.demo.DemoSeed;
import lk.dexlanka.erp.platform.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo-only inventory operations, posted through the real commands as the demo personas: a "Packaging store"
 * warehouse, a posted transfer of labels and cartons into it, a posted damage adjustment, a resin adjustment above the
 * approval threshold awaiting a second person, and a cycle count in progress. Runs once (skipped when the packaging
 * store exists); every step checks current stock first so it also works on an older, already-used demo database.
 */
@Component
@Order(DemoSeed.ORDER + 50)
class InventoryDemoSeed implements ApplicationRunner {
    private static final String TENANT = DemoPersonas.TENANT;

    private final Store s;
    private final InventoryRules rules;
    private final WarehouseService warehouses;
    private final TransferService transfers;
    private final AdjustmentService adjustments;
    private final CountService counts;
    private final boolean demo;

    InventoryDemoSeed(
            Store s,
            InventoryRules rules,
            WarehouseService warehouses,
            TransferService transfers,
            AdjustmentService adjustments,
            CountService counts,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.rules = rules;
        this.warehouses = warehouses;
        this.transfers = transfers;
        this.adjustments = adjustments;
        this.counts = counts;
        this.demo = demo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!demo || s.list("SELECT 1 FROM tenants WHERE id=?", TENANT).isEmpty()) return;
        if (!s.list("SELECT 1 FROM warehouses WHERE tenant_id=? AND code='PKG'", TENANT)
                .isEmpty()) return;
        var warehouse = DemoPersonas.WAREHOUSE;
        String store = str(
                warehouses.create(
                        warehouse,
                        new InventoryCommands.Warehouse("Packaging store", "PKG", "RAW"),
                        "demo-v5-warehouse-pkg"),
                "id");

        // Posted transfer: labels and cartons move from the raw-material warehouse to the packaging store.
        var transferLines = new java.util.ArrayList<InventoryCommands.TransferLine>();
        String raw = "demo-raw";
        addTransferLine(transferLines, "opening-label", raw, "40000");
        addTransferLine(transferLines, "opening-carton", raw, "1200");
        if (!transferLines.isEmpty()) {
            String transfer = str(
                    transfers.create(
                            warehouse,
                            new InventoryCommands.Transfer(
                                    raw, store, "Line-side packaging replenishment · demonstration", transferLines),
                            "demo-v5-transfer"),
                    "id");
            transfers.post(warehouse, transfer, "demo-v5-transfer-post");
        }

        // Posted damage adjustment below the approval threshold.
        adjust("opening-carton", "180", "DAMAGE", "Water damage near the loading bay · demonstration", "damage");

        // Adjustment above the threshold: created and submitted by the warehouse persona, awaiting approval.
        adjust(
                "opening-hdpe",
                "150",
                "DAMAGE",
                "Resin spill during silo transfer; awaiting planner approval · demonstration",
                "spill");

        // Cycle count in progress in the packaging store, one lot already counted.
        var countable = s.list(
                "SELECT 1 FROM lots l JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id WHERE l.tenant_id=? AND l.warehouse_id=? AND b.quantity>0",
                TENANT,
                store);
        if (!countable.isEmpty()) {
            String count = str(
                    counts.start(
                            warehouse,
                            new InventoryCommands.Count(
                                    store, null, null, false, "Weekly packaging cycle count · demonstration"),
                            "demo-v5-count"),
                    "id");
            var first = s.list(
                    "SELECT id,snapshot_qty FROM stock_count_lines WHERE tenant_id=? AND count_id=? ORDER BY line_no LIMIT 1",
                    TENANT,
                    count);
            if (!first.isEmpty())
                counts.enter(
                        warehouse,
                        count,
                        new InventoryCommands.CountEntries(List.of(new InventoryCommands.CountEntry(
                                str(first.getFirst(), "id"),
                                Store.dec(first.getFirst().get("snapshot_qty"))
                                        .subtract(BigDecimal.valueOf(120))
                                        .max(BigDecimal.ZERO)))),
                        "demo-v5-count-entry");
        }
    }

    private void addTransferLine(
            List<InventoryCommands.TransferLine> lines, String lot, String warehouse, String quantity) {
        var qty = new BigDecimal(quantity);
        var found = movableLot(lot, warehouse);
        if (found != null && available(found).compareTo(qty) >= 0)
            lines.add(new InventoryCommands.TransferLine(lot, qty));
    }

    private void adjust(String lot, String quantity, String reason, String notes, String key) {
        var qty = new BigDecimal(quantity);
        var found = movableLot(lot, "demo-raw");
        if (found == null || available(found).compareTo(qty) < 0) return;
        String id = str(
                adjustments.create(
                        DemoPersonas.WAREHOUSE,
                        new InventoryCommands.Adjustment(
                                reason,
                                notes,
                                List.of(new InventoryCommands.AdjustmentLine(
                                        "DECREASE", lot, null, null, qty, null, null))),
                        "demo-v5-adjust-" + key),
                "id");
        adjustments.post(DemoPersonas.WAREHOUSE, id, "demo-v5-adjust-" + key + "-post");
    }

    private Map<String, Object> movableLot(String lot, String warehouse) {
        var rows = s.list("SELECT 1 FROM lots WHERE tenant_id=? AND id=? AND warehouse_id=?", TENANT, lot, warehouse);
        return rows.isEmpty() ? null : rules.lot(DemoPersonas.WAREHOUSE, lot);
    }
}
