package lk.dexlanka.erp.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.catalog.CatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spare parts: FIFO issue from released lots through the ledger with a balanced expense journal, compensating returns,
 * closed-period refusal, item-type rules, and spare parts in BOMs and material planning.
 */
class SparePartTest extends MaintenanceTestSupport {
    @Autowired
    CatalogService catalog;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> movements(Map<String, Object> issue) {
        return (List<Map<String, Object>>) issue.get("movements");
    }

    private long ledgerRows() {
        return count("SELECT COUNT(*) FROM stock_ledger WHERE tenant_id=?", t);
    }

    @Test
    void issueConsumesOldestReleasedLotsWithABalancedJournal() throws Exception {
        String id = approvedOrder(m1, false);
        String number = str(workOrderRow(id), "number");
        var issue = json(command(
                        technician,
                        "/api/maintenance/work-orders/" + id + "/parts",
                        new MaintenanceCommands.IssuePart(spare, new BigDecimal("7"), "Bearings"))
                .andExpect(status().isOk()));
        assertThat(dec(issue.get("value"))).isEqualByComparingTo("740"); // 5 x 100 + 2 x 120
        assertThat(movements(issue))
                .extracting(m -> dec(m.get("quantity")).intValue())
                .containsExactly(5, 2);
        assertThat(quantity(lotA)).isEqualByComparingTo("0");
        assertThat(quantity(lotB)).isEqualByComparingTo("8");
        var ledger = rows(
                "SELECT * FROM stock_ledger WHERE tenant_id=? AND reference=? ORDER BY created_at,delta", t, number);
        assertThat(ledger).extracting(r -> r.get("reason")).containsOnly("MAINTENANCE_ISSUE");
        assertThat(ledger.stream().map(r -> dec(r.get("value_delta"))).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("-740");
        assertThat(journal(number, "MAINTENANCE_EXPENSE")).isEqualByComparingTo("740");
        assertThat(journal(number, "RAW_INVENTORY")).isEqualByComparingTo("-740");
        assertThat(dec(workOrderRow(id).get("parts_cost"))).isEqualByComparingTo("740");

        // Returns put unused parts back into the issuing lot at the issue's unit value.
        String fromB = str(movements(issue).get(1), "id");
        var back =
                parts.returnPart(technician, id, fromB, new MaintenanceCommands.ReturnPart(BigDecimal.ONE, null), id());
        assertThat(dec(back.get("value"))).isEqualByComparingTo("120");
        assertThat(quantity(lotB)).isEqualByComparingTo("9");
        assertThat(decimal(
                        "SELECT value_delta FROM stock_ledger WHERE tenant_id=? AND reference=? AND reason='MAINTENANCE_RETURN'",
                        t,
                        number))
                .isEqualByComparingTo("120");
        assertThat(journal(number, "MAINTENANCE_EXPENSE")).isEqualByComparingTo("620");
        assertThat(journal(number, "RAW_INVENTORY")).isEqualByComparingTo("-620");
        assertThat(dec(workOrderRow(id).get("parts_cost"))).isEqualByComparingTo("620");
        assertThatThrownBy(() -> parts.returnPart(
                        technician, id, fromB, new MaintenanceCommands.ReturnPart(new BigDecimal("2"), null), id()))
                .hasMessageContaining("Only 1 pcs of this issue can still be returned");
        parts.returnPart(technician, id, fromB, new MaintenanceCommands.ReturnPart(BigDecimal.ONE, null), id());
        assertThat(quantity(lotB)).isEqualByComparingTo("10");
        assertThat(decimal("SELECT value_amount FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lotB))
                .isEqualByComparingTo("1200");
        assertThat(unbalancedEntries()).isZero();
        assertThat(actions(number)).contains("MWO_PARTS_ISSUED", "MWO_PARTS_RETURNED");

        // Not enough released stock: nothing is posted.
        long before = ledgerRows();
        assertThatThrownBy(() -> parts.issue(
                        technician, id, new MaintenanceCommands.IssuePart(spare, new BigDecimal("50"), null), id()))
                .hasMessageContaining("Only 10 pcs of SP is available in released stock");
        assertThat(ledgerRows()).isEqualTo(before);
        // Quarantined stock is not issued.
        String held = openingStock(admin, spare, tenant.rawWarehouse(), "4", "90");
        update("UPDATE lots SET status='QUARANTINE' WHERE id=?", held);
        assertThatThrownBy(() -> parts.issue(
                        technician, id, new MaintenanceCommands.IssuePart(spare, new BigDecimal("11"), null), id()))
                .hasMessageContaining("available in released stock");

        // Item and quantity rules.
        String resin = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'RM','Resin','RAW_MATERIAL','kg',10,0)",
                resin,
                t);
        assertThatThrownBy(() -> parts.issue(
                        technician, id, new MaintenanceCommands.IssuePart(resin, BigDecimal.ONE, null), id()))
                .hasMessageContaining("is not a spare part");
        assertThatThrownBy(() -> parts.issue(
                        technician, id, new MaintenanceCommands.IssuePart(spare, new BigDecimal("1.5"), null), id()))
                .hasMessageContaining("whole numbers");
        command(
                        warehouse,
                        "/api/maintenance/work-orders/" + id + "/parts",
                        new MaintenanceCommands.IssuePart(spare, BigDecimal.ONE, null))
                .andExpect(status().isForbidden());
        command(
                        operator,
                        "/api/maintenance/work-orders/" + id + "/parts",
                        new MaintenanceCommands.IssuePart(spare, BigDecimal.ONE, null))
                .andExpect(status().isForbidden());

        // Requested (unapproved) and completed orders take no parts.
        String requested = str(orders.request(operator, corrective(m2, false), id()), "id");
        assertThatThrownBy(() -> parts.issue(
                        technician, requested, new MaintenanceCommands.IssuePart(spare, BigDecimal.ONE, null), id()))
                .hasMessageContaining("approved, scheduled or in-progress");
        var kept = parts.issue(technician, id, new MaintenanceCommands.IssuePart(spare, BigDecimal.ONE, null), id());
        orders.start(technician, id, id());
        orders.complete(technician, id, new MaintenanceCommands.Complete("Done", null, null, null), id());
        assertThatThrownBy(() -> parts.returnPart(
                        technician,
                        id,
                        str(movements(kept).getFirst(), "id"),
                        new MaintenanceCommands.ReturnPart(BigDecimal.ONE, null),
                        id()))
                .hasMessageContaining("before the work order is completed");
        var detail = query.workOrder(viewer, id);
        assertThat((List<?>) detail.get("parts")).hasSize(5);
    }

    @Test
    void closedPeriodsRefuseIssuesReturnsAndLabour() {
        String id = startedOrder(m1);
        var issue = parts.issue(technician, id, new MaintenanceCommands.IssuePart(spare, BigDecimal.ONE, null), id());
        update("INSERT INTO financial_periods(tenant_id,end_date,closed) VALUES(?,CURRENT_DATE+1,true)", t);
        long before = ledgerRows();
        assertThatThrownBy(() -> parts.issue(
                        technician, id, new MaintenanceCommands.IssuePart(spare, BigDecimal.ONE, null), id()))
                .hasMessageContaining("financial period is closed");
        assertThatThrownBy(() -> parts.returnPart(
                        technician,
                        id,
                        str(movements(issue).getFirst(), "id"),
                        new MaintenanceCommands.ReturnPart(BigDecimal.ONE, null),
                        id()))
                .hasMessageContaining("financial period is closed");
        assertThatThrownBy(() ->
                        orders.labour(technician, id, new MaintenanceCommands.Labour(BigDecimal.ONE, null, null), id()))
                .hasMessageContaining("financial period is closed");
        assertThat(ledgerRows()).isEqualTo(before);
    }

    @Test
    void sparePartsStayOutOfBomsAndArePlannedToTheirReorderLevel() throws Exception {
        // Spare parts are a real item type (creatable through the item master) but never BOM components.
        var created = json(command(
                        planner,
                        "/api/items",
                        new CatalogCommands.Item(
                                "SP-BELT", "V-belt", "SPARE_PART", "pcs", new BigDecimal("50"), new BigDecimal("20")))
                .andExpect(status().isOk()));
        String product = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'FG','Bottle','FINISHED_GOOD','pcs',5,0)",
                product,
                t);
        assertThatThrownBy(() -> catalog.bom(
                        planner,
                        new CatalogCommands.Bom(
                                product,
                                "R1",
                                new BigDecimal("100"),
                                BigDecimal.ONE,
                                List.of(new CatalogCommands.BomLine(spare, BigDecimal.ONE))),
                        id()))
                .hasMessageContaining("cannot be a BOM component");

        // Material planning replenishes a spare part to its reorder level (purchase), and nothing else drives it.
        openingStock(admin, str(created, "id"), tenant.rawWarehouse(), "15", "50");
        var mrp = json(query(planner, "/api/planning/mrp").andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) mrp.get("rows");
        var belt = rows.stream()
                .filter(r -> created.get("id").equals(r.get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(dec(belt.get("shortage"))).isEqualByComparingTo("5");
        assertThat(belt.get("action")).isEqualTo("PURCHASE");
        assertThat(dec(belt.get("gross"))).isZero();
        var bearing =
                rows.stream().filter(r -> spare.equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(bearing.get("action")).isEqualTo("NONE");

        // The picker lists spare parts with their released stock.
        @SuppressWarnings("unchecked")
        var picker = (List<Map<String, Object>>) query.spareParts(viewer).get("items");
        assertThat(picker).extracting(r -> r.get("sku")).containsExactly("SP", "SP-BELT");
        assertThat(dec(picker.getFirst().get("available"))).isEqualByComparingTo("15");
    }
}
