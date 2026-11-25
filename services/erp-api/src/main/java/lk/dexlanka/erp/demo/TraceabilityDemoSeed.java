package lk.dexlanka.erp.demo;

import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.catalog.CatalogService;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.procurement.ProcurementCommands;
import lk.dexlanka.erp.procurement.PurchaseOrderService;
import lk.dexlanka.erp.procurement.ReceivingService;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import lk.dexlanka.erp.sales.SalesCommands;
import lk.dexlanka.erp.sales.SalesService;
import lk.dexlanka.erp.traceability.RecallCommands;
import lk.dexlanka.erp.traceability.RecallService;
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
 * V14 demo data for genealogy and recalls (fictional), posted through the real commands as the demo personas. A
 * separate rPET product line keeps the walkthrough independent of the other seeds' FIFO stock:
 *
 * <ul>
 *   <li>items {@code RM-010} rPET resin (kg), {@code SF-010} rPET preform and {@code FG-010} 1.5 L rPET water bottle
 *       (preform + the existing printed label) with approved R01 BOMs; no routing and no inspection plan, so the
 *       inspections are plain decisions;
 *   <li>a resin purchase from Lanka Polymer Trading received as supplier lot {@code LPT-R2611-31} and released;
 *   <li>a preform order (IM-02) and a bottle order (BM-02) run to output and released by final inspection;
 *   <li>bottles shipped to Ceylon Beverage Industries ({@code DN-RCL-2611-01}) and Island Consumer Products
 *       ({@code DN-RCL-2611-02});
 *   <li>a CRITICAL recall of the resin lot (scope FORWARD), contained — the resin, preform and bottle lots still in
 *       stock are held by RECALL nonconformance reports — with Ceylon notified and Island still pending.
 * </ul>
 *
 * Runs once after the other seeds ({@code demo-v14-} keys) in its own transaction; a failure is logged and never
 * blocks startup.
 */
@Component
@Order(DemoSeed.ORDER + 140)
class TraceabilityDemoSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TraceabilityDemoSeed.class);
    private static final String TENANT = DemoPersonas.TENANT;
    private static final Actor ADMIN = DemoPersonas.ADMIN;
    private static final Actor PLANNER = DemoPersonas.PLANNER;
    private static final Actor WAREHOUSE = DemoPersonas.WAREHOUSE;
    private static final Actor QUALITY = DemoPersonas.QUALITY;
    private static final Actor OPERATOR = DemoPersonas.OPERATOR;

    private final Store s;
    private final CatalogService catalog;
    private final PurchaseOrderService orders;
    private final ReceivingService receiving;
    private final QualityService quality;
    private final ManufacturingService manufacturing;
    private final SalesService sales;
    private final RecallService recalls;
    private final TransactionTemplate tx;
    private final boolean demo;

    TraceabilityDemoSeed(
            Store s,
            CatalogService catalog,
            PurchaseOrderService orders,
            ReceivingService receiving,
            QualityService quality,
            ManufacturingService manufacturing,
            SalesService sales,
            RecallService recalls,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.catalog = catalog;
        this.orders = orders;
        this.receiving = receiving;
        this.quality = quality;
        this.manufacturing = manufacturing;
        this.sales = sales;
        this.recalls = recalls;
        this.tx = new TransactionTemplate(transactions);
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo || s.list("SELECT 1 FROM tenants WHERE id=?", TENANT).isEmpty()) return;
        if (seeded()) return;
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.warn(
                    "Traceability demo data (V14) was not added; the demo tenant keeps its other data: {}",
                    e.getMessage());
        }
    }

    boolean seeded() {
        return !s.list(
                        "SELECT 1 FROM idempotency_keys WHERE tenant_id=? AND request_key LIKE 'demo-v14-%' LIMIT 1",
                        TENANT)
                .isEmpty();
    }

    /** All-or-nothing: one transaction, so a failure leaves no partial traceability demo data. */
    void seed() {
        if (seeded()) return;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));

        // A separate rPET product line: resin → preform → bottle (+ the existing printed label).
        String resin = item("RM-010", "rPET resin · recycled food grade", "RAW_MATERIAL", "kg", "455", "resin");
        String preform = item("SF-010", "rPET preform · 1.5 L", "SEMI_FINISHED", "pcs", "12.5", "preform");
        String bottle = item("FG-010", "1.5 L rPET water bottle", "FINISHED_GOOD", "pcs", "34", "bottle");
        String preformBom = bom(preform, "preform", new CatalogCommands.BomLine(resin, new BigDecimal("24.5")));
        String bottleBom = bom(
                bottle,
                "bottle",
                new CatalogCommands.BomLine(preform, new BigDecimal("1000")),
                new CatalogCommands.BomLine("label", new BigDecimal("1000")));

        // The resin delivery: supplier lot LPT-R2611-31, released at incoming inspection.
        var po = orders.create(
                PLANNER,
                new ProcurementCommands.Order(
                        "resinco",
                        "LPT-SO-56231",
                        today.plusDays(1).toString(),
                        "Recycled resin for the 1.5 L rPET bottle launch.",
                        List.of(new ProcurementCommands.OrderLine(
                                null, resin, new BigDecimal("400"), null, new BigDecimal("455"), null))),
                "demo-v14-po");
        String poId = str(po, "id");
        orders.submit(PLANNER, poId, "demo-v14-po-submit");
        if ("SUBMITTED".equals(status("purchase_orders", poId))) orders.approve(ADMIN, poId, "demo-v14-po-approve");
        String poLine = str(
                s.one("SELECT id FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=?", TENANT, poId),
                "id");
        receiving.receive(
                WAREHOUSE,
                poId,
                new ProcurementCommands.GoodsReceipt(
                        "demo-raw",
                        "DN-LPT-261126",
                        List.of(new ProcurementCommands.ReceiptLine(
                                poLine, new BigDecimal("400"), null, "LPT-R2611-31"))),
                "demo-v14-receive");
        String resinLot = str(
                s.one("SELECT lot_id FROM receipts WHERE tenant_id=? AND purchase_order_id=?", TENANT, poId), "lot_id");
        pass(resinLot, "Certificate of analysis LPT-R2611-31 checked; flake colour and odour normal.", "resin");

        // Preforms on IM-02, bottles on BM-02.
        produce(preformBom, "im02", "10000", "9950", "50", "preform");
        produce(bottleBom, "bm02", "6000", "5980", "20", "bottle");
        String bottleLot = str(
                s.one(
                        "SELECT l.id FROM lots l JOIN manufacturing_orders m ON m.tenant_id=l.tenant_id AND m.id=l.source_id WHERE l.tenant_id=? AND l.source_type='MANUFACTURING' AND m.product_id=?",
                        TENANT,
                        bottle),
                "id");

        // Two customers received bottles from the lot.
        ship("ceylon", bottle, bottleLot, "2000", "DN-RCL-2611-01", "ceylon");
        ship("island", bottle, bottleLot, "1500", "DN-RCL-2611-02", "island");

        // The supplier's contamination notice: recall everything made from the resin lot, contain, notify Ceylon.
        var recall = recalls.open(
                QUALITY,
                new RecallCommands.Open(
                        "rPET resin LPT-R2611-31 · non-food-grade flake",
                        "Lanka Polymer Trading reported that recycled flake batch LPT-R2611-31 contained non-food-grade"
                                + " PET. Hold and recall everything made from the lot until the laboratory results are in.",
                        "CRITICAL",
                        "FORWARD",
                        "LOTS",
                        List.of(resinLot),
                        null,
                        null,
                        null,
                        null,
                        "CONT",
                        null),
                "demo-v14-recall-open");
        String recallId = str(recall, "id");
        recalls.contain(QUALITY, recallId, "demo-v14-recall-contain");
        recalls.notify(
                QUALITY,
                recallId,
                "ceylon",
                new RecallCommands.Notify(
                        today.toString(),
                        "Ayesha Fernando, QA manager (phone)",
                        "2,000 bottles quarantined at the Ekala distribution centre; collection booked for Friday."),
                "demo-v14-recall-notify-ceylon");
    }

    private String item(String sku, String name, String type, String uom, String cost, String key) {
        return str(
                catalog.item(
                        PLANNER,
                        new CatalogCommands.Item(
                                sku,
                                name,
                                type,
                                uom,
                                new BigDecimal(cost),
                                BigDecimal.ZERO,
                                "Fictional demonstration item (V14 recall walkthrough).",
                                null,
                                null,
                                null),
                        "demo-v14-item-" + key),
                "id");
    }

    private String bom(String product, String key, CatalogCommands.BomLine... lines) {
        String id = str(
                catalog.bom(
                        PLANNER,
                        new CatalogCommands.Bom(product, "R01", new BigDecimal("1000"), BigDecimal.ONE, List.of(lines)),
                        "demo-v14-bom-" + key),
                "id");
        catalog.approveBom(ADMIN, id, "demo-v14-bom-approve-" + key);
        return id;
    }

    /** Runs an order through approval, reservation, issue, output and final inspection; returns the output lot. */
    private String produce(String bom, String machine, String qty, String good, String scrap, String key) {
        String order = str(
                manufacturing.create(
                        PLANNER,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                null,
                                new BigDecimal(qty),
                                "HIGH",
                                LocalDate.now().plusDays(1).toString()),
                        "demo-v14-mo-" + key),
                "id");
        if ("DRAFT".equals(status("manufacturing_orders", order)))
            manufacturing.transition(ADMIN, order, "approve", "demo-v14-mo-approve-" + key);
        manufacturing.transition(WAREHOUSE, order, "reserve", "demo-v14-mo-reserve-" + key);
        manufacturing.transition(WAREHOUSE, order, "issue", "demo-v14-mo-issue-" + key);
        manufacturing.transition(OPERATOR, order, "start", "demo-v14-mo-start-" + key);
        manufacturing.complete(
                OPERATOR,
                order,
                new ManufacturingCommands.Complete(
                        new BigDecimal(good),
                        new BigDecimal(scrap),
                        new BigDecimal("9000"),
                        new BigDecimal("6000"),
                        "Launch run: start-up rejects"),
                "demo-v14-mo-complete-" + key);
        String lot = str(
                s.one(
                        "SELECT id FROM lots WHERE tenant_id=? AND source_type='MANUFACTURING' AND source_id=?",
                        TENANT,
                        order),
                "id");
        pass(lot, "Final release: dimensions, weight and visual checks passed.", key);
        return lot;
    }

    private void pass(String lot, String notes, String key) {
        String inspection = str(
                s.one("SELECT id FROM inspections WHERE tenant_id=? AND lot_id=? AND status='PENDING'", TENANT, lot),
                "id");
        quality.inspect(QUALITY, inspection, new QualityCommands.Inspect("PASSED", notes), "demo-v14-inspect-" + key);
    }

    private void ship(String customer, String bottle, String lot, String qty, String reference, String key) {
        var order = sales.create(
                PLANNER,
                new SalesCommands.Order(
                        customer,
                        "PO-" + reference.substring(reference.length() - 5),
                        LocalDate.now().plusDays(2).toString(),
                        "1.5 L rPET launch volumes.",
                        List.of(new SalesCommands.Line(null, bottle, new BigDecimal(qty), new BigDecimal("58"), null))),
                "demo-v14-so-" + key);
        String so = str(order, "id");
        sales.confirm(PLANNER, so, "demo-v14-so-confirm-" + key);
        String line =
                str(s.one("SELECT id FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=?", TENANT, so), "id");
        sales.dispatchLine(
                WAREHOUSE,
                so,
                line,
                new SalesCommands.DispatchLine(
                        reference, List.of(new SalesCommands.Allocation(lot, new BigDecimal(qty)))),
                "demo-v14-dispatch-" + key);
    }

    private String status(String table, String id) {
        return str(s.one("SELECT status FROM " + table + " WHERE tenant_id=? AND id=?", TENANT, id), "status");
    }
}
