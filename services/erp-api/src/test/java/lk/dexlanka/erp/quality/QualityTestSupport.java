package lk.dexlanka.erp.quality;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.procurement.ProcurementCommands;
import lk.dexlanka.erp.procurement.PurchaseOrderService;
import lk.dexlanka.erp.procurement.ReceivingService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A tenant with two quality users (independent approval/verification), warehouse, planners, operator and viewer; a
 * resin (kg), a printed label (pcs) and a bottle (finished good, pcs); a supplier. Helpers create held lots with a
 * pending inspection, released lots, purchased lots (real purchase order and receipt) and inspection plans.
 */
abstract class QualityTestSupport extends IntegrationTestBase {
    @Autowired
    QualityService quality;

    @Autowired
    InspectionPlanService plans;

    @Autowired
    NcrService ncrs;

    @Autowired
    ReworkService rework;

    @Autowired
    CapaService capas;

    @Autowired
    PurchaseOrderService orders;

    @Autowired
    ReceivingService receiving;

    String t, raw, finished, resin, label, bottle, supplier;
    Actor admin, qa, qa2, warehouse, planner, planner2, operator, viewer;

    @BeforeEach
    void qualityFixture() {
        var tenant = createTenant("Quality fixture");
        t = tenant.id();
        raw = tenant.rawWarehouse();
        finished = tenant.finishedWarehouse();
        admin = createUser(t, "ADMIN");
        qa = createUser(t, "QUALITY");
        qa2 = createUser(t, "QUALITY");
        warehouse = createUser(t, "WAREHOUSE");
        planner = createUser(t, "PLANNER");
        planner2 = createUser(t, "PLANNER");
        operator = createUser(t, "OPERATOR");
        viewer = createUser(t, "VIEWER");
        resin = id();
        label = id();
        bottle = id();
        supplier = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'RM-Q1','HDPE resin','RAW_MATERIAL','kg',480,0),(?,?,'PK-Q1','Label','PACKAGING','pcs',2,0),(?,?,'FG-Q1','Bottle','FINISHED_GOOD','pcs',10,0)",
                resin,
                t,
                label,
                t,
                bottle,
                t);
        update("INSERT INTO partners(id,tenant_id,name,type) VALUES(?,?,'Polymer Traders','SUPPLIER')", supplier, t);
    }

    // ------------------------------------------------------------------ lots

    /** A lot of {@code item} with stock posted through the ledger (FG_INVENTORY or RAW_INVENTORY vs OPENING_EQUITY). */
    String lot(String item, String warehouseId, String status, String source, String qty, String unitCost) {
        String lot = id();
        update(
                "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,?,?,'TEST',?)",
                lot,
                t,
                "L-" + lot.substring(0, 8),
                item,
                warehouseId,
                status,
                source,
                bd(unitCost));
        BigDecimal value = s.ledger(admin, lot, bd(qty), "OPENING", "TEST_OPENING");
        boolean fg = item.equals(bottle);
        s.journal(admin, "OPENING", "Test opening", fg ? "FG_INVENTORY" : "RAW_INVENTORY", "OPENING_EQUITY", value);
        return lot;
    }

    /** A quarantined bottle lot with a pending inspection of {@code type}; returns {lot, inspection}. */
    String[] heldBottles(String qty, String type) {
        String lot = lot(bottle, finished, "QUARANTINE", "OPENING", qty, "10");
        String inspection = id();
        update("INSERT INTO inspections(id,tenant_id,lot_id,type) VALUES(?,?,?,?)", inspection, t, lot, type);
        return new String[] {lot, inspection};
    }

    /** A bottle lot rejected by a failed (planless) final inspection; returns {lot, inspection, ncr}. */
    String[] rejectedBottles(String qty) {
        var held = heldBottles(qty, "FINAL");
        var result = quality.inspect(qa, held[1], new QualityCommands.Inspect("FAILED", "Short shots"), id());
        return new String[] {held[0], held[1], str(result, "ncrId")};
    }

    /** A purchase order for {@code qty} kg of resin, approved and received; returns {po, lot, inspection, receipt}. */
    String[] purchasedResin(String qty) {
        var order = orders.create(
                planner,
                new ProcurementCommands.Order(
                        supplier,
                        "Q-1",
                        LocalDate.now().plusDays(5).toString(),
                        "",
                        List.of(new ProcurementCommands.OrderLine(null, resin, bd(qty), null, bd("500"), null))),
                id());
        String po = str(order, "id");
        orders.submit(planner, po, id());
        orders.approve(planner2, po, id());
        String line =
                str(row("SELECT id FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=?", t, po), "id");
        receiving.receive(
                warehouse,
                po,
                new ProcurementCommands.GoodsReceipt(
                        raw,
                        "DN-" + id().substring(0, 6),
                        List.of(new ProcurementCommands.ReceiptLine(line, bd(qty), null, null))),
                id());
        var receipt = row("SELECT * FROM receipts WHERE tenant_id=? AND purchase_order_id=?", t, po);
        String lot = str(receipt, "lot_id");
        String inspection = str(row("SELECT id FROM inspections WHERE tenant_id=? AND lot_id=?", t, lot), "id");
        return new String[] {po, lot, inspection, str(receipt, "id")};
    }

    Map<String, Object> lotRow(String lot) {
        return row(
                "SELECT l.*,COALESCE(b.quantity,0) AS quantity,COALESCE(b.value_amount,0) AS value_amount FROM lots l LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id WHERE l.tenant_id=? AND l.id=?",
                t,
                lot);
    }

    String lotStatus(String lot) {
        return str(lotRow(lot), "status");
    }

    Map<String, Object> ncr(String id) {
        return row("SELECT * FROM ncrs WHERE tenant_id=? AND id=?", t, id);
    }

    /** Debits minus credits of {@code account} over the tenant's journal (optionally one reference). */
    BigDecimal balance(String account, String reference) {
        return decimal(
                "SELECT COALESCE(SUM(jl.debit-jl.credit),0) FROM journal_lines jl JOIN journal_entries je ON je.tenant_id=jl.tenant_id AND je.id=jl.entry_id WHERE jl.tenant_id=? AND jl.account=? AND (?::text IS NULL OR je.reference=?)",
                t,
                account,
                reference,
                reference);
    }

    BigDecimal tenantJournalImbalance() {
        return decimal("SELECT COALESCE(SUM(debit-credit),0) FROM journal_lines WHERE tenant_id=?", t);
    }

    // ------------------------------------------------------------------ plans and measurements

    static InspectionPlanCommands.Characteristic variable(
            String name, String nominal, String lower, String upper, int sample, boolean critical, String defect) {
        return new InspectionPlanCommands.Characteristic(
                name,
                "VARIABLE",
                "mm",
                nominal == null ? null : bd(nominal),
                lower == null ? null : bd(lower),
                upper == null ? null : bd(upper),
                sample,
                critical,
                defect,
                null);
    }

    static InspectionPlanCommands.Characteristic attribute(String name, int sample, boolean critical, String defect) {
        return new InspectionPlanCommands.Characteristic(
                name, "ATTRIBUTE", null, null, null, null, sample, critical, defect, null);
    }

    /** Bottle plan: critical wall thickness (0.40–0.52, 3 readings), non-critical weight (≤ 22.6, 2), critical leak. */
    String bottlePlan(String type) {
        return str(
                plans.create(
                        qa,
                        new InspectionPlanCommands.Plan(
                                "Bottle " + type,
                                bottle,
                                null,
                                type,
                                null,
                                List.of(
                                        variable("Wall thickness", "0.45", "0.40", "0.52", 3, true, "DIM"),
                                        variable("Weight", null, null, "22.6", 2, false, null),
                                        attribute("Leak test", 10, true, "LEAK"))),
                        id()),
                "id");
    }

    List<String> characteristics(String plan) {
        return rows(
                        "SELECT id FROM inspection_characteristics WHERE tenant_id=? AND plan_id=? AND active ORDER BY seq",
                        t,
                        plan)
                .stream()
                .map(r -> str(r, "id"))
                .toList();
    }

    static QualityCommands.Measurement readings(String characteristic, String note, String... values) {
        var list = new ArrayList<BigDecimal>();
        for (String v : values) list.add(bd(v));
        return new QualityCommands.Measurement(characteristic, list, null, note);
    }

    static QualityCommands.Measurement count(String characteristic, int nonconforming) {
        return new QualityCommands.Measurement(characteristic, null, nonconforming, null);
    }

    /** All-in-spec measurements for {@link #bottlePlan}. */
    List<QualityCommands.Measurement> goodBottle(String plan) {
        var c = characteristics(plan);
        return List.of(
                readings(c.get(0), null, "0.45", "0.44", "0.46"),
                readings(c.get(1), null, "22.0", "22.1"),
                count(c.get(2), 0));
    }
}
