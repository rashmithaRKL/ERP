package lk.dexlanka.erp.traceability;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.catalog.CatalogService;
import lk.dexlanka.erp.inventory.InventoryCommands;
import lk.dexlanka.erp.inventory.TransferService;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.procurement.ProcurementCommands;
import lk.dexlanka.erp.procurement.PurchaseOrderService;
import lk.dexlanka.erp.procurement.ReceivingService;
import lk.dexlanka.erp.quality.NcrService;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import lk.dexlanka.erp.sales.ReturnsService;
import lk.dexlanka.erp.sales.SalesCommands;
import lk.dexlanka.erp.sales.SalesService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A tenant with every role (two quality users for independent approval), a PET resin (kg) bought from a supplier, a
 * preform (semi-finished, 2 kg resin per 100) and a bottle (finished, one preform each) with approved BOMs, a work
 * center, two customers and helpers that drive the real commands: purchase + incoming inspection, manufacturing order
 * through output and final inspection, sales dispatch, customer return and warehouse transfer.
 */
abstract class TraceTestSupport extends IntegrationTestBase {
    @Autowired
    TraceService traces;

    @Autowired
    RecallService recalls;

    @Autowired
    RecallQuery recallQuery;

    @Autowired
    CatalogService catalog;

    @Autowired
    ManufacturingService mo;

    @Autowired
    PurchaseOrderService orders;

    @Autowired
    ReceivingService receiving;

    @Autowired
    QualityService quality;

    @Autowired
    NcrService ncrs;

    @Autowired
    SalesService sales;

    @Autowired
    ReturnsService returns;

    @Autowired
    TransferService transfers;

    TestTenant tenant;
    String t, raw, finished, resin, preform, bottle, preformBom, bottleBom, machine, supplier, customerA, customerB;
    Actor admin, qa, qa2, planner, planner2, warehouse, operator, salesRep, viewer;

    @BeforeEach
    void traceFixture() {
        tenant = createTenant("Trace fixture");
        t = tenant.id();
        raw = tenant.rawWarehouse();
        finished = tenant.finishedWarehouse();
        admin = createUser(t, "ADMIN");
        qa = createUser(t, "QUALITY");
        qa2 = createUser(t, "QUALITY");
        planner = createUser(t, "PLANNER");
        planner2 = createUser(t, "PLANNER");
        warehouse = createUser(t, "WAREHOUSE");
        operator = createUser(t, "OPERATOR");
        salesRep = createUser(t, "SALES");
        viewer = createUser(t, "VIEWER");
        resin = id();
        preform = id();
        bottle = id();
        machine = id();
        supplier = id();
        customerA = id();
        customerB = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'RM-PET','PET resin','RAW_MATERIAL','kg',400,0),(?,?,'SF-PRE','Preform','SEMI_FINISHED','pcs',9,0),(?,?,'FG-BOT','Bottle','FINISHED_GOOD','pcs',12,0)",
                resin,
                t,
                preform,
                t,
                bottle,
                t);
        update(
                "INSERT INTO partners(id,tenant_id,name,type) VALUES(?,?,'Resin Supplier','SUPPLIER'),(?,?,'Alpha Beverages','CUSTOMER'),(?,?,'Beta Foods','CUSTOMER')",
                supplier,
                t,
                customerA,
                t,
                customerB,
                t);
        update("UPDATE partners SET email='buyer@alpha.test',phone='+94 11 000 0001' WHERE tenant_id=? AND id=?", t, customerA);
        update(
                "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,'BM-T','Blow moulder','Moulding','IDLE',10)",
                machine,
                t,
                tenant.plant());
        preformBom = approvedBom(preform, "100", resin, "2");
        bottleBom = approvedBom(bottle, "1", preform, "1");
    }

    private String approvedBom(String product, String output, String component, String quantity) {
        String bom = str(
                catalog.bom(
                        planner,
                        new CatalogCommands.Bom(
                                product,
                                "R1",
                                bd(output),
                                bd("1"),
                                List.of(new CatalogCommands.BomLine(component, bd(quantity)))),
                        id()),
                "id");
        catalog.approveBom(admin, bom, id());
        return bom;
    }

    /** Purchases and receives {@code qty} kg of resin, passes incoming inspection: {po, lot, receipt, grn}. */
    String[] purchasedResin(String qty) {
        var order = orders.create(
                planner,
                new ProcurementCommands.Order(
                        supplier,
                        "T-1",
                        LocalDate.now().plusDays(5).toString(),
                        "",
                        List.of(new ProcurementCommands.OrderLine(null, resin, bd(qty), null, bd("400"), null))),
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
        passPending(lot);
        return new String[] {po, lot, str(receipt, "id"), str(receipt, "number")};
    }

    /** Passes the pending inspection of a lot (releases it). */
    void passPending(String lot) {
        String inspection = str(
                row("SELECT id FROM inspections WHERE tenant_id=? AND lot_id=? AND status='PENDING'", t, lot), "id");
        quality.inspect(qa, inspection, new QualityCommands.Inspect("PASSED", "ok"), id());
    }

    /** Runs an order of {@code qty} through issue, output and final inspection; returns {order, output lot}. */
    String[] produce(String bom, String qty) {
        String order = str(
                mo.create(
                        planner,
                        new ManufacturingCommands.Manufacture(
                                bom, machine, null, bd(qty), "NORMAL", LocalDate.now().plusDays(3).toString()),
                        id()),
                "id");
        mo.transition(planner2, order, "approve", id());
        mo.transition(warehouse, order, "reserve", id());
        mo.transition(warehouse, order, "issue", id());
        mo.transition(operator, order, "start", id());
        mo.complete(operator, order, new ManufacturingCommands.Complete(bd(qty), bd("0"), bd("0"), bd("0"), "Run"), id());
        String lot = str(
                row("SELECT id FROM lots WHERE tenant_id=? AND source_type='MANUFACTURING' AND source_id=?", t, order),
                "id");
        passPending(lot);
        return new String[] {order, lot};
    }

    /** A confirmed bottle sales order for {@code customer}; dispatches {@code qty} from {@code lot}: {so, delivery}. */
    String[] sell(String customer, String lot, String qty, String reference) {
        String so = str(
                sales.create(
                        salesRep,
                        new SalesCommands.Order(
                                customer,
                                "CR-" + reference,
                                LocalDate.now().plusDays(5).toString(),
                                "",
                                List.of(new SalesCommands.Line(null, bottle, bd(qty), bd("20"), null))),
                        id()),
                "id");
        sales.confirm(salesRep, so, id());
        String line = str(row("SELECT id FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=?", t, so), "id");
        sales.dispatchLine(
                warehouse,
                so,
                line,
                new SalesCommands.DispatchLine(reference, List.of(new SalesCommands.Allocation(lot, bd(qty)))),
                id());
        String delivery =
                str(row("SELECT id FROM deliveries WHERE tenant_id=? AND sales_order_id=? AND lot_id=?", t, so, lot), "id");
        return new String[] {so, delivery};
    }

    /** A customer return of {@code qty} from a delivery; returns the quarantine lot. */
    String customerReturn(String delivery, String qty) {
        var result = returns.customerReturn(salesRep, delivery, new SalesCommands.Return(bd(qty), "Leaking caps"), id());
        return str(result, "lotId");
    }

    /** Posts a transfer of {@code qty} of {@code lot} to {@code toWarehouse}; returns the destination lot. */
    String transfer(String lot, String fromWarehouse, String toWarehouse, String qty) {
        var created = transfers.create(
                warehouse,
                new InventoryCommands.Transfer(
                        fromWarehouse, toWarehouse, "", List.of(new InventoryCommands.TransferLine(lot, bd(qty)))),
                id());
        transfers.post(warehouse, str(created, "id"), id());
        return str(
                row(
                        "SELECT destination_lot_id FROM stock_transfer_lines WHERE tenant_id=? AND transfer_id=?",
                        t,
                        created.get("id")),
                "destination_lot_id");
    }

    /** A second finished-goods warehouse (transfer destination). */
    String secondFinishedWarehouse() {
        String id = id();
        update(
                "INSERT INTO warehouses(id,tenant_id,factory_id,name,kind) VALUES(?,?,?,'Dispatch store','FINISHED')",
                id,
                t,
                tenant.plant());
        return id;
    }

    String lotNumber(String lot) {
        return str(row("SELECT number FROM lots WHERE tenant_id=? AND id=?", t, lot), "number");
    }

    java.math.BigDecimal onHand(String lot) {
        return decimal("SELECT COALESCE(SUM(quantity),0) FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lot);
    }

    String lotStatus(String lot) {
        return str(row("SELECT status FROM lots WHERE tenant_id=? AND id=?", t, lot), "status");
    }

    // ------------------------------------------------------------------ graph helpers

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    static Map<String, Object> node(Map<String, Object> trace, String id) {
        return list(trace.get("nodes")).stream()
                .filter(n -> id.equals(n.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No node " + id + " in " + trace.get("nodes")));
    }

    static boolean hasNode(Map<String, Object> trace, String id) {
        return list(trace.get("nodes")).stream().anyMatch(n -> id.equals(n.get("id")));
    }

    static Map<String, Object> edge(Map<String, Object> trace, String from, String to, String kind) {
        return list(trace.get("edges")).stream()
                .filter(e -> from.equals(e.get("from")) && to.equals(e.get("to")) && kind.equals(e.get("kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No " + kind + " edge " + from + " -> " + to + " in " + trace.get("edges")));
    }

    static List<Map<String, Object>> edges(Map<String, Object> trace, String kind) {
        return list(trace.get("edges")).stream()
                .filter(e -> kind.equals(e.get("kind")))
                .toList();
    }

    static java.math.BigDecimal num(Object value) {
        return new java.math.BigDecimal(String.valueOf(value));
    }
}
