package lk.dexlanka.erp.demo;

import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.catalog.CatalogService;
import lk.dexlanka.erp.catalog.PartnerCommands;
import lk.dexlanka.erp.catalog.PartnerService;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import lk.dexlanka.erp.sales.ReturnsService;
import lk.dexlanka.erp.sales.SalesCommands;
import lk.dexlanka.erp.sales.SalesService;
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
 * V3 demo data for sales documents (fictional): partner contact details and credit limits, an inactive customer,
 * multi-line orders in different states (partially delivered, delivered, confirmed with planned production, draft,
 * cancelled) and a customer return awaiting quality control. Runs once, after the base seed, through the real
 * commands as the demo personas; skipped when any {@code demo-v3-} key exists.
 */
@Component
@Order(DemoSeed.ORDER + 30)
class SalesDemoSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SalesDemoSeed.class);
    private static final Actor PLANNER = DemoPersonas.PLANNER;
    private static final Actor ADMIN = DemoPersonas.ADMIN;
    private static final Actor WAREHOUSE = DemoPersonas.WAREHOUSE;
    private static final Actor OPERATOR = DemoPersonas.OPERATOR;
    private static final Actor QUALITY = DemoPersonas.QUALITY;

    private final Store s;
    private final SalesService sales;
    private final ReturnsService returns;
    private final ManufacturingService mo;
    private final QualityService quality;
    private final CatalogService catalog;
    private final PartnerService partners;
    private final TransactionTemplate tx;
    private final boolean demo;

    SalesDemoSeed(
            Store s,
            SalesService sales,
            ReturnsService returns,
            ManufacturingService mo,
            QualityService quality,
            CatalogService catalog,
            PartnerService partners,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.sales = sales;
        this.returns = returns;
        this.mo = mo;
        this.quality = quality;
        this.catalog = catalog;
        this.partners = partners;
        this.tx = new TransactionTemplate(transactions);
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo
                || s.list("SELECT 1 FROM tenants WHERE id=?", DemoPersonas.TENANT)
                        .isEmpty()) return;
        if (!s.list(
                        "SELECT 1 FROM idempotency_keys WHERE tenant_id=? AND request_key LIKE 'demo-v3-%' LIMIT 1",
                        DemoPersonas.TENANT)
                .isEmpty()) return;
        // An existing demo database may have drifted (stock used, machines changed): never block startup for it.
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.warn("Sales demo data (V3) was not added; the demo tenant keeps its base data: {}", e.getMessage());
        }
    }

    /** All-or-nothing: one transaction, so a failure leaves no partial sales demo data. */
    private void seed() {
        partnerDetails();

        // A: three lines, two of them produced and shipped; the container line is still open (PARTIAL).
        // The customer's credit limit is below this order, so confirmation records a credit warning.
        String a = order(
                "a",
                "island",
                "PO-ICP-7741",
                12,
                "Deliver to the Katugastota distribution centre; pallets max 1.2 m.",
                line("cap", "12000", "7.80", "0"),
                line("bottle", "6000", "27.50", "5"),
                line("container", "3000", "39.00", "2.5"));
        // B: two lines, fully delivered, then 300 bottles returned and waiting for quality control.
        String b = order(
                "b",
                "lakfood",
                "LFD-2026-118",
                6,
                "",
                line("food", "2000", "25.90", "0"),
                line("bottle", "1500", "28.00", "0"));
        sales.confirm(PLANNER, a, "demo-v3-confirm-a");
        sales.confirm(PLANNER, b, "demo-v3-confirm-b");

        String caps = produce("cap-a", lineId(a, 1), "bom-cap", "im02", "12000");
        String bottles = produce("bottle-a", lineId(a, 2), "bom-bottle", "bm02", "6000");
        String boxes = produce("food-b", lineId(b, 1), "bom-food", "im02", "2000");
        dispatch("a2", a, lineId(a, 2), "DN-DEMO-S1", bottles, "4000");
        dispatch("a1", a, lineId(a, 1), "DN-DEMO-S2", caps, "12000");
        dispatch("b1", b, lineId(b, 1), "DN-DEMO-S3", boxes, "2000");
        dispatch("b2", b, lineId(b, 2), "DN-DEMO-S4", bottles, "1500");
        var delivery = s.one(
                "SELECT id FROM deliveries WHERE tenant_id=? AND sales_order_id=? AND reference='DN-DEMO-S4'",
                DemoPersonas.TENANT,
                b);
        returns.customerReturn(
                WAREHOUSE,
                str(delivery, "id"),
                new SalesCommands.Return(
                        new BigDecimal("300"), "Crushed shrink-wrap on two pallets; customer refused on delivery"),
                "demo-v3-return-b2");

        // C: confirmed, production planned (draft MO) against line 1 only.
        String c = order(
                "c",
                "serendib",
                "SF/PUR/55210",
                18,
                "",
                line("container", "4000", "38.50", "0"),
                line("cap", "8000", "7.50", "0"));
        sales.confirm(PLANNER, c, "demo-v3-confirm-c");
        mo.create(
                PLANNER,
                new ManufacturingCommands.Manufacture(
                        "bom-container", "bm02", c, new BigDecimal("4000"), "NORMAL", due(15), lineId(c, 1)),
                "demo-v3-mo-container-c");

        // D: a draft quotation-stage order, still editable.
        order(
                "d",
                "ceylon",
                "RFQ-CBI-0193",
                25,
                "Pricing valid for 14 days; awaiting customer artwork approval.",
                line("bottle", "20000", "26.50", "2"),
                line("cap", "20000", "7.20", "2"));

        // E: confirmed, then cancelled before any production or delivery.
        String e = order("e", "island", "PO-ICP-7702", 9, "", line("food", "1000", "24.60", "0"));
        sales.confirm(PLANNER, e, "demo-v3-confirm-e");
        sales.cancel(
                PLANNER,
                e,
                new SalesCommands.Reason("Customer switched to the 1L food-grade container"),
                "demo-v3-cancel-e");
    }

    private void partnerDetails() {
        String[][] details = {
            // id, phone, billing address, shipping address, tax ID, payment days, credit limit
            {
                "ceylon",
                "+94 70 000 0101",
                "No. 42, Demo Industrial Estate, Ekala",
                "Warehouse 3, Ekala",
                "TIN-DEMO-1001",
                "45",
                "2500000"
            },
            {"serendib", "+94 70 000 0102", "88 Example Road, Colombo 09", "", "TIN-DEMO-1002", "30", "2000000"},
            {
                "island",
                "+94 70 000 0103",
                "17 Sample Lane, Kandy",
                "Distribution centre, Katugastota",
                "TIN-DEMO-1003",
                "30",
                "300000"
            },
            {"lakfood", "+94 70 000 0104", "5 Dairy Way, Negombo", "", "TIN-DEMO-1004", "15", "750000"},
            {"resinco", "+94 70 000 0201", "12 Polymer Park, Biyagama", "", "TIN-DEMO-2001", "60", ""},
            {"colorco", "+94 70 000 0202", "3 Pigment Street, Horana", "", "TIN-DEMO-2002", "45", ""},
            {"packco", "+94 70 000 0203", "Unit 9, Demo Zone, Katunayake", "", "TIN-DEMO-2003", "30", ""}
        };
        for (var d : details) {
            var p = s.one("SELECT * FROM partners WHERE tenant_id=? AND id=?", DemoPersonas.TENANT, d[0]);
            partners.update(
                    PLANNER,
                    d[0],
                    new PartnerCommands.Update(
                            str(p, "name"),
                            str(p, "email"),
                            d[1],
                            d[2],
                            d[3].isEmpty() ? d[2] : d[3],
                            d[4],
                            Integer.valueOf(d[5]),
                            d[6].isEmpty() ? null : new BigDecimal(d[6]),
                            true),
                    "demo-v3-partner-" + d[0]);
        }
        var closed = catalog.partner(
                PLANNER,
                new CatalogCommands.Partner(
                        "Harbour Fresh Exports",
                        "CUSTOMER",
                        "harbour@example.test",
                        30,
                        "+94 70 000 0105",
                        "1 Pier Road, Galle",
                        null,
                        "TIN-DEMO-1005",
                        new BigDecimal("400000")),
                "demo-v3-partner-harbour");
        partners.update(
                PLANNER,
                str(closed, "id"),
                new PartnerCommands.Update(
                        "Harbour Fresh Exports",
                        "harbour@example.test",
                        "+94 70 000 0105",
                        "1 Pier Road, Galle",
                        "1 Pier Road, Galle",
                        "TIN-DEMO-1005",
                        30,
                        new BigDecimal("400000"),
                        false),
                "demo-v3-partner-harbour-off");
    }

    private String order(
            String key, String customer, String reference, int dueInDays, String notes, SalesCommands.Line... lines) {
        return str(
                sales.create(
                        PLANNER,
                        new SalesCommands.Order(customer, reference, due(dueInDays), notes, List.of(lines)),
                        "demo-v3-order-" + key),
                "id");
    }

    /** Runs one manufacturing order for a sales line through approval, issue, output and final QC; returns the lot. */
    private String produce(String key, String line, String bom, String machine, String quantity) {
        var order = mo.create(
                PLANNER,
                new ManufacturingCommands.Manufacture(
                        bom, machine, null, new BigDecimal(quantity), "HIGH", due(2), line),
                "demo-v3-mo-" + key);
        String id = str(order, "id");
        mo.transition(ADMIN, id, "approve", "demo-v3-mo-" + key + "-approve");
        mo.transition(PLANNER, id, "reserve", "demo-v3-mo-" + key + "-reserve");
        mo.transition(WAREHOUSE, id, "issue", "demo-v3-mo-" + key + "-issue");
        mo.transition(OPERATOR, id, "start", "demo-v3-mo-" + key + "-start");
        mo.complete(
                OPERATOR,
                id,
                new ManufacturingCommands.Complete(
                        new BigDecimal(quantity),
                        BigDecimal.ZERO,
                        new BigDecimal("2800"),
                        new BigDecimal("1900"),
                        "Demonstration run for a customer order line"),
                "demo-v3-mo-" + key + "-output");
        var inspection = s.one(
                "SELECT i.id,l.id AS lot_id FROM inspections i JOIN lots l ON l.tenant_id=i.tenant_id AND l.id=i.lot_id WHERE i.tenant_id=? AND l.source_id=?",
                DemoPersonas.TENANT,
                id);
        quality.inspect(
                QUALITY,
                str(inspection, "id"),
                new QualityCommands.Inspect("PASSED", "Dimensional, weight and seal checks passed · demonstration"),
                "demo-v3-mo-" + key + "-qc");
        mo.transition(PLANNER, id, "close", "demo-v3-mo-" + key + "-close");
        return str(inspection, "lot_id");
    }

    private void dispatch(String key, String order, String line, String reference, String lot, String quantity) {
        sales.dispatchLine(
                WAREHOUSE,
                order,
                line,
                new SalesCommands.DispatchLine(
                        reference, List.of(new SalesCommands.Allocation(lot, new BigDecimal(quantity)))),
                "demo-v3-dispatch-" + key);
    }

    private String lineId(String order, int number) {
        return str(
                s.one(
                        "SELECT id FROM sales_order_lines WHERE tenant_id=? AND sales_order_id=? AND line_no=?",
                        DemoPersonas.TENANT,
                        order,
                        number),
                "id");
    }

    private static SalesCommands.Line line(String product, String quantity, String price, String discount) {
        return new SalesCommands.Line(
                null, product, new BigDecimal(quantity), new BigDecimal(price), new BigDecimal(discount));
    }

    private static String due(int days) {
        return LocalDate.now().plusDays(days).toString();
    }
}
