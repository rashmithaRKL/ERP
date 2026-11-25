package lk.dexlanka.erp.demo;

import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.procurement.ProcurementCommands;
import lk.dexlanka.erp.procurement.PurchaseOrderService;
import lk.dexlanka.erp.procurement.ReceivingService;
import lk.dexlanka.erp.quality.CapaCommands;
import lk.dexlanka.erp.quality.CapaService;
import lk.dexlanka.erp.quality.InspectionPlanCommands;
import lk.dexlanka.erp.quality.InspectionPlanService;
import lk.dexlanka.erp.quality.NcrCommands;
import lk.dexlanka.erp.quality.NcrService;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import lk.dexlanka.erp.quality.ReworkService;
import lk.dexlanka.erp.sales.ReturnsService;
import lk.dexlanka.erp.sales.SalesCommands;
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
 * V12 demo data for quality management (fictional), posted through the real commands as the demo personas:
 *
 * <ul>
 *   <li>inspection plans for the 500 ml bottle (FINAL, also governing RETURN and REWORK inspections) and HDPE resin
 *       (INCOMING), and a resin receipt released with measured characteristics;
 *   <li>a customer return of bottles whose inspection failed on a critical characteristic (wall thickness): its NCR
 *       is dispositioned partly SCRAP and partly REWORK, the rework order is completed (labour + relabelling) and the
 *       reworked lot passed its re-inspection and is released;
 *   <li>a second returned lot that failed its leak test: a critical NCR still awaiting disposition;
 *   <li>a CAPA on the wall-thickness NCR with one corrective action done and one preventive action overdue.
 * </ul>
 *
 * Runs once after the other seeds ({@code demo-v12-} keys), in its own transaction; a failure (a drifted demo
 * database) is logged and never blocks startup.
 */
@Component
@Order(DemoSeed.ORDER + 120)
class QualityDemoSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(QualityDemoSeed.class);
    private static final String TENANT = DemoPersonas.TENANT;
    private static final Actor ADMIN = DemoPersonas.ADMIN;
    private static final Actor PLANNER = DemoPersonas.PLANNER;
    private static final Actor WAREHOUSE = DemoPersonas.WAREHOUSE;
    private static final Actor QUALITY = DemoPersonas.QUALITY;
    private static final Actor OPERATOR = DemoPersonas.OPERATOR;

    private final Store s;
    private final InspectionPlanService plans;
    private final QualityService quality;
    private final NcrService ncrs;
    private final ReworkService rework;
    private final CapaService capas;
    private final ReturnsService returns;
    private final PurchaseOrderService orders;
    private final ReceivingService receiving;
    private final TransactionTemplate tx;
    private final boolean demo;

    QualityDemoSeed(
            Store s,
            InspectionPlanService plans,
            QualityService quality,
            NcrService ncrs,
            ReworkService rework,
            CapaService capas,
            ReturnsService returns,
            PurchaseOrderService orders,
            ReceivingService receiving,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.plans = plans;
        this.quality = quality;
        this.ncrs = ncrs;
        this.rework = rework;
        this.capas = capas;
        this.returns = returns;
        this.orders = orders;
        this.receiving = receiving;
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
            log.warn("Quality demo data (V12) was not added; the demo tenant keeps its other data: {}", e.getMessage());
        }
    }

    boolean seeded() {
        return !s.list(
                        "SELECT 1 FROM idempotency_keys WHERE tenant_id=? AND request_key LIKE 'demo-v12-%' LIMIT 1",
                        TENANT)
                .isEmpty();
    }

    /** All-or-nothing: one transaction, so a failure leaves no partial quality demo data. */
    void seed() {
        if (seeded()) return;
        LocalDate today = LocalDate.now();

        // Inspection plans: the bottle's final release plan also governs returned and reworked bottles.
        String bottlePlan = str(
                plans.create(
                        QUALITY,
                        new InspectionPlanCommands.Plan(
                                "500ml bottle · final release",
                                "bottle",
                                null,
                                "FINAL",
                                "AQL sampling per shift; returned and reworked bottles use the same plan.",
                                List.of(
                                        variable(
                                                "Wall thickness",
                                                "mm",
                                                "0.45",
                                                "0.40",
                                                "0.52",
                                                5,
                                                true,
                                                "DIM",
                                                "Ultrasonic gauge at shoulder and base"),
                                        variable(
                                                "Bottle weight",
                                                "g",
                                                "22.0",
                                                "21.5",
                                                "22.6",
                                                5,
                                                false,
                                                "DIM",
                                                "Precision balance"),
                                        attribute("Leak test", 20, true, "LEAK", "Pressure decay 0.3 bar for 10 s"),
                                        attribute(
                                                "Visual finish",
                                                20,
                                                false,
                                                "VIS",
                                                "Flash, black specks, colour streaks"))),
                        "demo-v12-plan-bottle"),
                "id");
        String resinPlan = str(
                plans.create(
                        QUALITY,
                        new InspectionPlanCommands.Plan(
                                "HDPE resin · incoming",
                                "hdpe",
                                null,
                                "INCOMING",
                                "Check every delivery against the supplier certificate.",
                                List.of(
                                        variable(
                                                "Melt flow index",
                                                "g/10 min",
                                                "0.35",
                                                "0.28",
                                                "0.42",
                                                3,
                                                true,
                                                "MAT",
                                                "ASTM D1238, 190 °C / 2.16 kg"),
                                        variable(
                                                "Moisture",
                                                "%",
                                                null,
                                                null,
                                                "0.05",
                                                3,
                                                false,
                                                "MAT",
                                                "Moisture analyser"),
                                        attribute(
                                                "Certificate of analysis",
                                                1,
                                                true,
                                                "DOC",
                                                "Batch CoA matches the delivery"))),
                        "demo-v12-plan-hdpe"),
                "id");

        // A resin delivery released against its incoming plan.
        var po = orders.create(
                PLANNER,
                new ProcurementCommands.Order(
                        "resinco",
                        "LPT-SO-56104",
                        today.plusDays(2).toString(),
                        "Resin for the wall-thickness trial runs.",
                        List.of(new ProcurementCommands.OrderLine(
                                null, "hdpe", new BigDecimal("500"), null, new BigDecimal("482"), null))),
                "demo-v12-po");
        String poId = str(po, "id");
        orders.submit(PLANNER, poId, "demo-v12-po-submit");
        if ("SUBMITTED"
                .equals(s.one("SELECT status FROM purchase_orders WHERE tenant_id=? AND id=?", TENANT, poId)
                        .get("status"))) orders.approve(ADMIN, poId, "demo-v12-po-approve");
        String poLine = str(
                s.one("SELECT id FROM purchase_order_lines WHERE tenant_id=? AND purchase_order_id=?", TENANT, poId),
                "id");
        receiving.receive(
                WAREHOUSE,
                poId,
                new ProcurementCommands.GoodsReceipt(
                        "demo-raw",
                        "DN-LPT-261124",
                        List.of(new ProcurementCommands.ReceiptLine(
                                poLine, new BigDecimal("500"), null, "LPT-B2611-07"))),
                "demo-v12-receive");
        String resinInspection = str(
                s.one(
                        "SELECT q.id FROM inspections q JOIN receipts r ON r.tenant_id=q.tenant_id AND r.lot_id=q.lot_id WHERE q.tenant_id=? AND r.purchase_order_id=?",
                        TENANT,
                        poId),
                "id");
        var resin = characteristics(resinPlan);
        quality.inspect(
                QUALITY,
                resinInspection,
                new QualityCommands.Inspect(
                        "PASSED",
                        "MFI and moisture within specification; CoA LPT-B2611-07 checked.",
                        List.of(
                                readings(resin.get(0), "0.34", "0.36", "0.35"),
                                readings(resin.get(1), "0.03", "0.04", "0.035"),
                                count(resin.get(2), 0, null))),
                "demo-v12-inspect-hdpe");

        // Returned bottles with thin walls: critical failure, partly scrapped, partly reworked and released.
        var bottle = characteristics(bottlePlan);
        var returnA = returns.customerReturn(
                WAREHOUSE,
                delivery(300),
                new SalesCommands.Return(
                        new BigDecimal("300"), "Customer complaint CB-2611-04: bottles collapse when capped"),
                "demo-v12-return-a");
        var failed = quality.inspect(
                QUALITY,
                str(returnA, "inspectionId"),
                new QualityCommands.Inspect(
                        "FAILED",
                        "Thin walls at the shoulder on returned bottles; 100 split or deformed.",
                        List.of(
                                readings(bottle.get(0), "0.44", "0.38", "0.37", "0.43", "0.39"),
                                readings(bottle.get(1), "21.9", "21.6", "21.7", "22.0", "21.8"),
                                count(bottle.get(2), 0, null),
                                count(bottle.get(3), 2, "Scuffing from transport"))),
                "demo-v12-inspect-a");
        String ncrA = str(failed, "ncrId");
        ncrs.disposition(
                QUALITY,
                ncrA,
                new NcrCommands.Disposition(
                        "SCRAP", new BigDecimal("100"), "Split and deformed bottles cannot be recovered", null),
                "demo-v12-scrap-a");
        var reworked = ncrs.disposition(
                QUALITY,
                ncrA,
                new NcrCommands.Disposition(
                        "REWORK",
                        new BigDecimal("200"),
                        "Sound bottles: sort, re-gauge the shoulder and relabel",
                        "100% wall-thickness check at the shoulder; replace scuffed labels."),
                "demo-v12-rework-a");
        var labels = s.list(
                "SELECT l.id FROM lots l JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id WHERE l.tenant_id=? AND l.item_id='label' AND l.status='RELEASED' AND b.quantity-b.reserved>=200 ORDER BY l.created_at,l.id LIMIT 1",
                TENANT);
        var done = rework.complete(
                OPERATOR,
                str(reworked, "reworkOrderId"),
                new NcrCommands.ReworkComplete(
                        new BigDecimal("200"),
                        new BigDecimal("1800"),
                        labels.isEmpty()
                                ? List.of()
                                : List.of(
                                        new NcrCommands.Material(str(labels.getFirst(), "id"), new BigDecimal("200"))),
                        "Sorted and relabelled; two operators, 1.5 h"),
                "demo-v12-rework-complete");
        quality.inspect(
                QUALITY,
                str(done, "inspectionId"),
                new QualityCommands.Inspect(
                        "PASSED",
                        "Reworked bottles re-gauged: walls within specification.",
                        List.of(
                                readings(bottle.get(0), "0.45", "0.44", "0.46", "0.43", "0.45"),
                                readings(bottle.get(1), "22.0", "21.9", "22.1", "22.0", "21.8"),
                                count(bottle.get(2), 0, null),
                                count(bottle.get(3), 0, null))),
                "demo-v12-inspect-rework");

        // A second return failed its leak test: a critical NCR waiting for a disposition.
        var returnB = returns.customerReturn(
                WAREHOUSE,
                delivery(120),
                new SalesCommands.Return(new BigDecimal("120"), "Leaking bottles reported by the filling line"),
                "demo-v12-return-b");
        quality.inspect(
                QUALITY,
                str(returnB, "inspectionId"),
                new QualityCommands.Inspect(
                        "FAILED",
                        "Three of twenty sampled bottles leak at the neck finish.",
                        List.of(
                                readings(bottle.get(0), "0.45", "0.46", "0.44", "0.45", "0.47"),
                                readings(bottle.get(1), "22.1", "22.0", "21.9", "22.2", "22.0"),
                                count(bottle.get(2), 3, null),
                                count(bottle.get(3), 0, null))),
                "demo-v12-inspect-b");

        // CAPA on the wall-thickness NCR: corrective action done, preventive action overdue.
        String capa = str(
                capas.create(
                        QUALITY,
                        new CapaCommands.Capa(
                                "Bottle wall thickness below specification at the shoulder",
                                "Returned 500ml bottles measured 0.37–0.39 mm at the shoulder (LSL 0.40 mm); bottles collapse when capped.",
                                "NCR",
                                QUALITY.id(),
                                today.plusDays(21).toString(),
                                List.of(ncrA)),
                        "demo-v12-capa"),
                "id");
        capas.update(
                QUALITY,
                capa,
                new CapaCommands.Update(
                        "Bottle wall thickness below specification at the shoulder",
                        "Returned 500ml bottles measured 0.37–0.39 mm at the shoulder (LSL 0.40 mm); bottles collapse when capped.",
                        QUALITY.id(),
                        today.plusDays(21).toString(),
                        "MACHINE",
                        List.of(
                                "Why were the walls thin? The parison was too thin at the shoulder.",
                                "Why was the parison thin? The wall-thickness profile drifted on BM-01.",
                                "Why did it drift? The parison controller was not recalibrated after the die change.",
                                "Why not? Recalibration is not part of the die-change checklist.",
                                ""),
                        "Parison controller not recalibrated after die changes; no in-process wall-thickness check.",
                        List.of(ncrA)),
                "demo-v12-capa-update");
        String corrective = str(
                capas.addAction(
                        QUALITY,
                        capa,
                        new CapaCommands.Action(
                                "CORRECTIVE",
                                "Recalibrate the BM-01 parison wall-thickness controller and add it to the die-change checklist",
                                PLANNER.id(),
                                today.minusDays(5).toString()),
                        "demo-v12-action-1"),
                "id");
        capas.addAction(
                QUALITY,
                capa,
                new CapaCommands.Action(
                        "PREVENTIVE",
                        "Introduce an hourly wall-thickness SPC check on all blow-moulding lines",
                        QUALITY.id(),
                        today.minusDays(2).toString()),
                "demo-v12-action-2");
        capas.completeAction(
                PLANNER,
                corrective,
                new CapaCommands.Note("Controller recalibrated; die-change checklist revision C issued."),
                "demo-v12-action-1-done");
        capas.start(QUALITY, capa, new CapaCommands.Note("Root cause confirmed on BM-01."), "demo-v12-capa-start");
    }

    /** A bottle delivery with at least {@code quantity} not yet returned (oldest first). */
    private String delivery(int quantity) {
        return str(
                s.one(
                        "SELECT d.id FROM deliveries d JOIN lots l ON l.tenant_id=d.tenant_id AND l.id=d.lot_id WHERE d.tenant_id=? AND l.item_id='bottle' AND d.quantity-d.returned>=? ORDER BY d.created_at,d.id LIMIT 1",
                        TENANT,
                        quantity),
                "id");
    }

    private List<String> characteristics(String plan) {
        return s
                .list(
                        "SELECT id FROM inspection_characteristics WHERE tenant_id=? AND plan_id=? AND active ORDER BY seq",
                        TENANT,
                        plan)
                .stream()
                .map(r -> str(r, "id"))
                .toList();
    }

    private static InspectionPlanCommands.Characteristic variable(
            String name,
            String unit,
            String nominal,
            String lower,
            String upper,
            int sample,
            boolean critical,
            String defect,
            String method) {
        return new InspectionPlanCommands.Characteristic(
                name,
                "VARIABLE",
                unit,
                nominal == null ? null : new BigDecimal(nominal),
                lower == null ? null : new BigDecimal(lower),
                upper == null ? null : new BigDecimal(upper),
                sample,
                critical,
                defect,
                method);
    }

    private static InspectionPlanCommands.Characteristic attribute(
            String name, int sample, boolean critical, String defect, String method) {
        return new InspectionPlanCommands.Characteristic(
                name, "ATTRIBUTE", null, null, null, null, sample, critical, defect, method);
    }

    private static QualityCommands.Measurement readings(String characteristic, String... values) {
        var list = new ArrayList<BigDecimal>();
        for (String v : values) list.add(new BigDecimal(v));
        return new QualityCommands.Measurement(characteristic, list, null, null);
    }

    private static QualityCommands.Measurement count(String characteristic, int nonconforming, String note) {
        return new QualityCommands.Measurement(characteristic, null, nonconforming, note);
    }
}
