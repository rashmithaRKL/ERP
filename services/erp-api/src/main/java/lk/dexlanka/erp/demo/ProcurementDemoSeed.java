package lk.dexlanka.erp.demo;

import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.procurement.ProcurementCommands;
import lk.dexlanka.erp.procurement.PurchaseOrderService;
import lk.dexlanka.erp.procurement.ReceivingService;
import lk.dexlanka.erp.procurement.SupplierInvoiceService;
import lk.dexlanka.erp.procurement.SupplierReturnService;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V6 demo data for procurement documents (fictional), posted through the real commands as the demo personas: a
 * multi-line draft, an order awaiting independent approval, a partially received order with one line still in
 * incoming QC, a fully received order whose supplier invoice carries a small price variance, and a supplier return of
 * released resin. Resin and labels are bought in bags and rolls when the item-master conversions exist. Runs once,
 * after the base, sales, item-master and inventory seeds; skipped when any {@code demo-v6-} key exists.
 */
@Component
@Order(DemoSeed.ORDER + 60)
class ProcurementDemoSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ProcurementDemoSeed.class);
    private static final String TENANT = DemoPersonas.TENANT;
    private static final Actor PLANNER = DemoPersonas.PLANNER;
    private static final Actor ADMIN = DemoPersonas.ADMIN;
    private static final Actor WAREHOUSE = DemoPersonas.WAREHOUSE;
    private static final Actor QUALITY = DemoPersonas.QUALITY;

    private final Store s;
    private final PurchaseOrderService orders;
    private final ReceivingService receiving;
    private final SupplierInvoiceService invoices;
    private final SupplierReturnService returns;
    private final QualityService quality;
    private final TransactionTemplate tx;
    private final boolean demo;

    ProcurementDemoSeed(
            Store s,
            PurchaseOrderService orders,
            ReceivingService receiving,
            SupplierInvoiceService invoices,
            SupplierReturnService returns,
            QualityService quality,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.orders = orders;
        this.receiving = receiving;
        this.invoices = invoices;
        this.returns = returns;
        this.quality = quality;
        this.tx = new TransactionTemplate(transactions);
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo || s.list("SELECT 1 FROM tenants WHERE id=?", TENANT).isEmpty()) return;
        if (seeded()) return;
        // An existing demo database may have drifted (items or suppliers changed): never block startup for it.
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.warn(
                    "Procurement demo data (V6) was not added; the demo tenant keeps its base data: {}",
                    e.getMessage());
        }
    }

    boolean seeded() {
        return !s.list(
                        "SELECT 1 FROM idempotency_keys WHERE tenant_id=? AND request_key LIKE 'demo-v6-%' LIMIT 1",
                        TENANT)
                .isEmpty();
    }

    /** All-or-nothing: one transaction, so a failure leaves no partial procurement demo data. */
    void seed() {
        if (seeded()) return;
        LocalDate today = LocalDate.now();

        // A: a two-line draft the planner is still working on (resin bought in 25 kg bags).
        orders.create(
                PLANNER,
                new ProcurementCommands.Order(
                        "resinco",
                        "RFQ-LPT-2611",
                        today.plusDays(12).toString(),
                        "Quarterly resin top-up; confirm bag weights before dispatch.",
                        List.of(resin("hdpe", "40", "12050"), resin("pet", "20", "12900"))),
                "demo-v6-po-a");

        // B: packaging drafted and submitted by the warehouse, waiting for a planner's independent approval.
        String b = id(orders.create(
                WAREHOUSE,
                new ProcurementCommands.Order(
                        "packco",
                        "EP-QT-0915",
                        today.plusDays(6).toString(),
                        "Cartons for the Ceylon Beverage bottle run.",
                        List.of(line("carton", "1500", null, "124"), roll("label", "40", "2450"))),
                "demo-v6-po-b"));
        orders.submit(WAREHOUSE, b, "demo-v6-submit-b");

        // C: approved, resin half delivered and released, the colour masterbatch still in incoming QC.
        String c = approved(
                "c",
                "resinco",
                "LPT-SO-55821",
                today.plusDays(3),
                List.of(resin("hdpe", "120", "12100"), line("color", "50", null, "1840")));
        var cReceipt = receiving.receive(
                WAREHOUSE,
                c,
                new ProcurementCommands.GoodsReceipt(
                        "demo-raw",
                        "LPT-DN-40417",
                        List.of(receiptLine(c, 1, half(c), "LPT-HD-261118"), receiptLine(c, 2, "50", "CT-261118-02"))),
                "demo-v6-receive-c");
        var cReceipts = receipts(cReceipt);
        pass(str(cReceipts.get(0), "lotId"), "Melt flow index and moisture within specification · demonstration");

        // D: fully received and released; the supplier billed PET slightly above the order price.
        String d = approved(
                "d",
                "resinco",
                "LPT-SO-55790",
                today.minusDays(4),
                List.of(resin("pet", "40", "12875"), resin("hdpe", "20", "12125")));
        var dReceipt = receiving.receive(
                WAREHOUSE,
                d,
                new ProcurementCommands.GoodsReceipt(
                        "demo-raw",
                        "LPT-DN-40388",
                        List.of(receiptLine(d, 1, null, "LPT-PT-261112"), receiptLine(d, 2, null, "LPT-HD-261112"))),
                "demo-v6-receive-d");
        var dReceipts = receipts(dReceipt);
        for (var r : dReceipts) pass(str(r, "lotId"), "Incoming resin accepted · demonstration");
        var petLine = line(d, 1);
        var hdpeLine = line(d, 2);
        invoices.match(
                ADMIN,
                d,
                new ProcurementCommands.SupplierInvoice(
                        "LPT-INV-88213",
                        today.minusDays(1).toString(),
                        "Supplier applied the November resin surcharge on PET.",
                        List.of(
                                new ProcurementCommands.InvoiceLine(
                                        str(dReceipts.get(0), "id"),
                                        new BigDecimal(str(dReceipts.get(0), "quantity")),
                                        new BigDecimal(str(petLine, "base_unit_cost")).add(new BigDecimal("1.2"))),
                                new ProcurementCommands.InvoiceLine(
                                        str(dReceipts.get(1), "id"),
                                        new BigDecimal(str(dReceipts.get(1), "quantity")),
                                        new BigDecimal(str(hdpeLine, "base_unit_cost"))))),
                "demo-v6-invoice-d");

        // E: two torn bags of the released order-C resin go back to the supplier against a debit note.
        returns.returnToSupplier(
                WAREHOUSE,
                str(cReceipts.get(0), "id"),
                new ProcurementCommands.SupplierReturn(
                        new BigDecimal("50"), "Two bags torn in transit; resin contaminated with dust", "DBN-2611-014"),
                "demo-v6-return-c");
    }

    // ------------------------------------------------------------------ helpers

    private String approved(
            String tag, String supplier, String reference, LocalDate due, List<ProcurementCommands.OrderLine> lines) {
        String id = id(orders.create(
                PLANNER,
                new ProcurementCommands.Order(supplier, reference, due.toString(), "", lines),
                "demo-v6-po-" + tag));
        orders.submit(PLANNER, id, "demo-v6-submit-" + tag);
        orders.approve(ADMIN, id, "demo-v6-approve-" + tag);
        return id;
    }

    private void pass(String lot, String notes) {
        var inspection = s.one("SELECT id FROM inspections WHERE tenant_id=? AND lot_id=?", TENANT, lot);
        quality.inspect(
                QUALITY,
                str(inspection, "id"),
                new QualityCommands.Inspect("PASSED", notes),
                "demo-v6-qc-" + lot.substring(0, 8));
    }

    /** Resin in 25 kg bags (price per bag) when the conversion exists, else the same quantity in kg. */
    private ProcurementCommands.OrderLine resin(String item, String bags, String perBag) {
        return packaged(item, "bag", bags, perBag);
    }

    /** Labels in rolls of 1,000 (price per roll) when the conversion exists, else pieces. */
    private ProcurementCommands.OrderLine roll(String item, String rolls, String perRoll) {
        return packaged(item, "roll", rolls, perRoll);
    }

    private ProcurementCommands.OrderLine packaged(String item, String unit, String quantity, String price) {
        var conversion = s.list(
                "SELECT factor FROM item_unit_conversions WHERE tenant_id=? AND item_id=? AND unit_code=? AND purchase",
                TENANT,
                item,
                unit);
        if (!conversion.isEmpty()) return line(item, quantity, unit, price);
        BigDecimal factor = new BigDecimal(unit.equals("bag") ? "25" : "1000");
        return line(
                item,
                new BigDecimal(quantity).multiply(factor).toPlainString(),
                null,
                new BigDecimal(price)
                        .divide(factor, 6, java.math.RoundingMode.HALF_UP)
                        .toPlainString());
    }

    private static ProcurementCommands.OrderLine line(String item, String quantity, String unit, String cost) {
        return new ProcurementCommands.OrderLine(
                null, item, new BigDecimal(quantity), unit, new BigDecimal(cost), null);
    }

    private Map<String, Object> line(String po, int number) {
        return s.one(
                "SELECT * FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=? AND line_no=?",
                TENANT,
                po,
                number);
    }

    /** Receives {@code quantity} in the line's unit (all of it when null). */
    private ProcurementCommands.ReceiptLine receiptLine(String po, int number, String quantity, String lot) {
        var l = line(po, number);
        return new ProcurementCommands.ReceiptLine(
                str(l, "id"), new BigDecimal(quantity == null ? str(l, "quantity") : quantity), str(l, "unit"), lot);
    }

    /** Half of line 1 of an order, in its unit. */
    private String half(String po) {
        return new BigDecimal(str(line(po, 1), "quantity"))
                .divide(new BigDecimal("2"), 0, java.math.RoundingMode.DOWN)
                .toPlainString();
    }

    private static String id(Map<String, Object> result) {
        return str(result, "id");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> receipts(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("receipts");
    }
}
