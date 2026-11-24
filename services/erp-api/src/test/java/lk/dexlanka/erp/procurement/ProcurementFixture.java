package lk.dexlanka.erp.procurement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A tenant with two planners (for independent approval), warehouse, quality, sales and viewer users; a resin (kg, with
 * a purchase conversion 1 bag = 25 kg), cartons (pcs, 1 box = 3 pcs for purchasing, 1 pallet = 100 pcs for sales only),
 * a finished good, two suppliers and a customer.
 */
abstract class ProcurementFixture extends IntegrationTestBase {
    @Autowired
    PurchaseOrderService orders;

    @Autowired
    ReceivingService receiving;

    @Autowired
    SupplierInvoiceService invoices;

    @Autowired
    SupplierReturnService returns;

    @Autowired
    ProcurementQuery query;

    @Autowired
    ProcurementSupply supply;

    @Autowired
    QualityService quality;

    String t, raw, finished, resin, carton, bottle, supplier, supplier2, customer;
    Actor admin, planner, planner2, warehouse, qa, salesRep, viewer;

    @BeforeEach
    void procurementFixture() {
        var tenant = createTenant("Procurement fixture");
        t = tenant.id();
        raw = tenant.rawWarehouse();
        finished = tenant.finishedWarehouse();
        admin = createUser(t, "ADMIN");
        planner = createUser(t, "PLANNER");
        planner2 = createUser(t, "PLANNER");
        warehouse = createUser(t, "WAREHOUSE");
        qa = createUser(t, "QUALITY");
        salesRep = createUser(t, "SALES");
        viewer = createUser(t, "VIEWER");
        resin = id();
        carton = id();
        bottle = id();
        supplier = id();
        supplier2 = id();
        customer = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level,lead_time_days) VALUES(?,?,'RM-1','HDPE resin','RAW_MATERIAL','kg',480,0,7),(?,?,'PK-1','Carton','PACKAGING','pcs',30,0,3),(?,?,'FG-1','Bottle','FINISHED_GOOD','pcs',10,0,0)",
                resin,
                t,
                carton,
                t,
                bottle,
                t);
        update(
                "INSERT INTO item_unit_conversions(id,tenant_id,item_id,unit_code,factor,purchase,sales) VALUES(?,?,?,'bag',25,true,false),(?,?,?,'box',3,true,false),(?,?,?,'pallet',100,false,true)",
                id(),
                t,
                resin,
                id(),
                t,
                carton,
                id(),
                t,
                carton);
        update(
                "INSERT INTO partners(id,tenant_id,name,type) VALUES(?,?,'Polymer Traders','SUPPLIER'),(?,?,'Pack Co','SUPPLIER'),(?,?,'Customer Co','CUSTOMER')",
                supplier,
                t,
                supplier2,
                t,
                customer,
                t);
    }

    // ------------------------------------------------------------------ builders

    static ProcurementCommands.OrderLine line(String item, String quantity, String unit, String cost) {
        return new ProcurementCommands.OrderLine(null, item, bd(quantity), unit, bd(cost), null);
    }

    ProcurementCommands.Order order(String supplierId, ProcurementCommands.OrderLine... lines) {
        return new ProcurementCommands.Order(
                supplierId, "Q-100", LocalDate.now().plusDays(7).toString(), "", List.of(lines));
    }

    String draft(ProcurementCommands.OrderLine... lines) {
        return str(orders.create(planner, order(supplier, lines), id()), "id");
    }

    String submitted(ProcurementCommands.OrderLine... lines) {
        String po = draft(lines);
        orders.submit(planner, po, id());
        return po;
    }

    String approved(ProcurementCommands.OrderLine... lines) {
        String po = submitted(lines);
        orders.approve(planner2, po, id());
        return po;
    }

    String lineId(String po, int number) {
        return str(
                row(
                        "SELECT id FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=? AND line_no=?",
                        t,
                        po,
                        number),
                "id");
    }

    Map<String, Object> poLine(String po, int number) {
        return row(
                "SELECT * FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=? AND line_no=?",
                t,
                po,
                number);
    }

    Map<String, Object> header(String po) {
        return row("SELECT * FROM purchase_orders WHERE tenant_id=? AND id=?", t, po);
    }

    static ProcurementCommands.ReceiptLine receiptLine(String lineId, String quantity, String unit, String lot) {
        return new ProcurementCommands.ReceiptLine(lineId, bd(quantity), unit, lot);
    }

    /** Receives into the RAW warehouse as the warehouse user; returns the command result. */
    Map<String, Object> receive(String po, ProcurementCommands.ReceiptLine... lines) {
        return receiving.receive(
                warehouse,
                po,
                new ProcurementCommands.GoodsReceipt(raw, "DN-" + id().substring(0, 6), List.of(lines)),
                id());
    }

    /** The receipt row of a lot. */
    Map<String, Object> receiptOfLot(String lot) {
        return row("SELECT * FROM receipts WHERE tenant_id=? AND lot_id=?", t, lot);
    }

    /** Ids of a decoded list of rows. */
    static List<Object> ids(Object rows) {
        return ((List<?>) rows)
                .stream().<Object>map(r -> ((Map<?, ?>) r).get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> receipts(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("receipts");
    }

    void inspect(String lot, String result) {
        var inspection = row("SELECT id FROM inspections WHERE tenant_id=? AND lot_id=?", t, lot);
        quality.inspect(qa, str(inspection, "id"), new QualityCommands.Inspect(result, "Incoming check"), id());
    }

    /** Receives {@code quantity} (in the item's base unit) of a line and passes QC; returns the receipt id. */
    String releasedReceipt(String po, int lineNo, String quantity, String lot) {
        String base = str(
                row(
                        "SELECT i.uom FROM purchase_order_lines l JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id WHERE l.tenant_id=? AND l.id=?",
                        t,
                        lineId(po, lineNo)),
                "uom");
        var result = receive(po, receiptLine(lineId(po, lineNo), quantity, base, lot));
        String lotId = str(receipts(result).getFirst(), "lotId");
        inspect(lotId, "PASSED");
        return str(receipts(result).getFirst(), "id");
    }

    static ProcurementCommands.SupplierInvoice invoice(String reference, ProcurementCommands.InvoiceLine... lines) {
        return new ProcurementCommands.SupplierInvoice(
                reference, LocalDate.now().toString(), null, List.of(lines));
    }

    static ProcurementCommands.InvoiceLine invoiceLine(String receipt, String quantity, String price) {
        return new ProcurementCommands.InvoiceLine(receipt, bd(quantity), bd(price));
    }

    // ------------------------------------------------------------------ accounting

    /** Net movement of one account for this tenant (debit positive), optionally limited to a journal reference. */
    BigDecimal account(String account, String reference) {
        return decimal(
                "SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id WHERE l.tenant_id=? AND l.account=? AND (?::text IS NULL OR e.reference=?)",
                t,
                account,
                reference,
                reference);
    }

    BigDecimal journalImbalance() {
        return decimal("SELECT COALESCE(SUM(debit-credit),0) FROM journal_lines WHERE tenant_id=?", t);
    }

    BigDecimal stock(String lot) {
        return decimal("SELECT quantity FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lot);
    }

    BigDecimal stockValue(String lot) {
        return decimal("SELECT value_amount FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lot);
    }

    /** Stock value per the immutable ledger equals the materialized balance for every lot of the tenant. */
    boolean ledgerReconciles() {
        return count(
                        "SELECT COUNT(*) FROM stock_balances b WHERE b.tenant_id=? AND b.value_amount<>(SELECT COALESCE(SUM(value_delta),0) FROM stock_ledger e WHERE e.tenant_id=b.tenant_id AND e.lot_id=b.lot_id)",
                        t)
                == 0;
    }
}
