package lk.dexlanka.erp.demo;

import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.catalog.ItemService;
import lk.dexlanka.erp.inventory.AdjustmentService;
import lk.dexlanka.erp.inventory.InventoryCommands;
import lk.dexlanka.erp.maintenance.DowntimeService;
import lk.dexlanka.erp.maintenance.MaintenanceCommands;
import lk.dexlanka.erp.maintenance.PlanService;
import lk.dexlanka.erp.maintenance.SparePartService;
import lk.dexlanka.erp.maintenance.WorkOrderService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V11 demo data (fictional): the maintenance technician persona, two spare parts with stock (one below its reorder
 * level), closed downtime of different reasons over the last week, preventive plans for IM-01 (scheduled order
 * generated) and BM-01 (overdue, no order yet), a completed corrective order on PK-01 with a spare part issued, labour
 * and a closed BREAKDOWN, and a corrective order in progress on IM-03 with its breakdown still open. Runs once after
 * the earlier feature seeds through the real commands as the demo personas; skipped when any {@code demo-v11-} key
 * exists. Times are relative to the current day so the data never ages out of the default 30-day views.
 */
@Component
@Order(DemoSeed.ORDER + 110)
class MaintenanceDemoSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceDemoSeed.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");
    private static final Actor OPERATOR = DemoPersonas.OPERATOR;
    private static final Actor PLANNER = DemoPersonas.PLANNER;
    private static final Actor WAREHOUSE = DemoPersonas.WAREHOUSE;
    private static final Actor TECHNICIAN = DemoPersonas.MAINTENANCE;

    private final Store s;
    private final PasswordEncoder passwords;
    private final ItemService items;
    private final AdjustmentService adjustments;
    private final DowntimeService downtime;
    private final WorkOrderService orders;
    private final SparePartService parts;
    private final PlanService plans;
    private final TransactionTemplate tx;
    private final boolean demo;

    MaintenanceDemoSeed(
            Store s,
            PasswordEncoder passwords,
            ItemService items,
            AdjustmentService adjustments,
            DowntimeService downtime,
            WorkOrderService orders,
            SparePartService parts,
            PlanService plans,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.passwords = passwords;
        this.items = items;
        this.adjustments = adjustments;
        this.downtime = downtime;
        this.orders = orders;
        this.parts = parts;
        this.plans = plans;
        this.tx = new TransactionTemplate(transactions);
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo
                || s.list("SELECT 1 FROM tenants WHERE id=?", DemoPersonas.TENANT)
                        .isEmpty()) return;
        // An existing demo database may have drifted (machines busy, stock used): never block startup for it.
        try {
            tx.executeWithoutResult(status -> {
                persona();
                seed();
            });
        } catch (RuntimeException e) {
            log.warn("Maintenance demo data (V11) was not added: {}", e.getMessage());
        }
    }

    /** The MAINTENANCE persona (also added to a demo database created before V11). */
    void persona() {
        Actor t = TECHNICIAN;
        if (!s.list("SELECT 1 FROM users WHERE id=?", t.id()).isEmpty()) return;
        s.db().update(
                        "INSERT INTO users(id,tenant_id,email,name,password_hash,role) VALUES(?,?,?,?,?,?)",
                        t.id(),
                        t.tenant(),
                        t.email(),
                        t.name(),
                        passwords.encode(Store.id() + Store.id()),
                        t.role());
    }

    /** Idempotent: does nothing once a {@code demo-v11-} command has run. */
    void seed() {
        if (!s.list(
                        "SELECT 1 FROM idempotency_keys WHERE tenant_id=? AND request_key LIKE 'demo-v11-%' LIMIT 1",
                        DemoPersonas.TENANT)
                .isEmpty()) return;
        LocalDate today = LocalDate.now(ZONE);

        // Spare parts with opening stock in the raw-material store (a found-stock adjustment below the approval limit).
        String bearing = sparePart("bearing", "SP-001", "Deep-groove ball bearing 6205-2RS", "3800", "6", "12", 10);
        String belt = sparePart("belt", "SP-002", "V-belt A-42 (applicator drive)", "1250", "10", "20", 7);
        stock("bearing", bearing, "12", "3800", "SP-BRG-2611");
        stock("belt", belt, "8", "1250", "SP-VB-2611");

        // Closed downtime over the last week (different reasons, planned and unplanned).
        closed("bm01-breakdown", "bm01", "BREAKDOWN", today.minusDays(6), "10:20", 120, "Parison head heater failed");
        closed("im02-operator", "im02", "NO_OPERATOR", today.minusDays(5), "06:00", 55, "Night-shift operator absent");
        closed(
                "bm02-utilities",
                "bm02",
                "UTILITIES",
                today.minusDays(4),
                "14:05",
                25,
                "Grid power dip, compressor trip");
        closed("im01-material", "im01", "MATERIAL_SHORTAGE", today.minusDays(3), "09:30", 75, "Masterbatch not staged");
        closed("bm01-changeover", "bm01", "CHANGEOVER", today.minusDays(2), "13:00", 40, "Mould change 500 ml → 1 L");
        closed("bm01-quality", "bm01", "QUALITY_HOLD", today.minusDays(1), "11:10", 30, "Wall-thickness check failed");

        // Preventive plans: IM-01 due in five days (its order is generated), BM-01 overdue (no order yet).
        plans.create(
                PLANNER,
                new MaintenanceCommands.Plan(
                        "im01",
                        "Quarterly hydraulic service",
                        "Hydraulic unit and clamp service per the machine manual.",
                        List.of(
                                "Replace hydraulic oil filter",
                                "Check oil level and temperature",
                                "Inspect hoses and fittings for leaks",
                                "Lubricate tie-bars and toggles"),
                        90,
                        null,
                        today.plusDays(5).toString(),
                        new BigDecimal("3"),
                        "NORMAL"),
                "demo-v11-plan-im01");
        plans.generate(PLANNER, new MaintenanceCommands.Generate(7), "demo-v11-generate");
        plans.meter(
                TECHNICIAN,
                "bm01",
                new MaintenanceCommands.Meter(new BigDecimal("1240"), "Hour meter"),
                "demo-v11-meter-1");
        plans.create(
                PLANNER,
                new MaintenanceCommands.Plan(
                        "bm01",
                        "Monthly blow-moulding service",
                        "Monthly preventive service of the blow-moulding line.",
                        List.of(
                                "Clean and inspect parison head",
                                "Check heater bands and thermocouples",
                                "Inspect blow pins and moulds",
                                "Grease cutter and carriage guides",
                                "Test emergency stops and guards"),
                        30,
                        new BigDecimal("500"),
                        today.minusDays(3).toString(),
                        new BigDecimal("4"),
                        "HIGH"),
                "demo-v11-plan-bm01");
        plans.meter(
                TECHNICIAN,
                "bm01",
                new MaintenanceCommands.Meter(new BigDecimal("1385"), "Hour meter"),
                "demo-v11-meter-2");

        // Completed corrective order on PK-01: breakdown, approval, belt replaced, labour, machine back to idle.
        var pk = orders.request(
                OPERATOR,
                new MaintenanceCommands.RequestWorkOrder(
                        "pk01",
                        "Label applicator stopped – drive belt snapped",
                        "Applicator drive belt broke during the morning run; labels not applied.",
                        "HIGH",
                        true,
                        at(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(95)),
                        null,
                        null),
                "demo-v11-request-pk01");
        String pkId = str(pk, "id");
        orders.approve(
                PLANNER, pkId, new MaintenanceCommands.Approve(null, null, TECHNICIAN.id()), "demo-v11-approve-pk01");
        orders.start(TECHNICIAN, pkId, "demo-v11-start-pk01");
        parts.issue(
                TECHNICIAN,
                pkId,
                new MaintenanceCommands.IssuePart(belt, BigDecimal.ONE, "Replacement belt"),
                "demo-v11-issue-pk01");
        orders.labour(
                TECHNICIAN,
                pkId,
                new MaintenanceCommands.Labour(new BigDecimal("1.25"), null, "Belt change and tensioning"),
                "demo-v11-labour-pk01");
        orders.complete(
                TECHNICIAN,
                pkId,
                new MaintenanceCommands.Complete(
                        "Drive belt worn through at the tensioner pulley; pulley bearing noisy but within limits.",
                        "Replaced V-belt, re-tensioned and aligned the pulleys; test run of 200 labels OK.",
                        null,
                        null),
                "demo-v11-complete-pk01");

        // Corrective order in progress on IM-03: the breakdown is still open.
        var im = orders.request(
                OPERATOR,
                new MaintenanceCommands.RequestWorkOrder(
                        "im03",
                        "Hydraulic pressure drops during injection",
                        "Pressure falls below 90 bar at the end of the injection stroke; parts short-shot.",
                        "URGENT",
                        true,
                        at(OffsetDateTime.now(ZoneOffset.UTC).minusHours(3)),
                        null,
                        null),
                "demo-v11-request-im03");
        String imId = str(im, "id");
        orders.approve(
                PLANNER, imId, new MaintenanceCommands.Approve(null, null, TECHNICIAN.id()), "demo-v11-approve-im03");
        orders.start(TECHNICIAN, imId, "demo-v11-start-im03");
        orders.labour(
                TECHNICIAN,
                imId,
                new MaintenanceCommands.Labour(new BigDecimal("1.5"), null, "Diagnosis: pump and relief valve"),
                "demo-v11-labour-im03");
        orders.progress(
                TECHNICIAN,
                imId,
                new MaintenanceCommands.Progress(
                        null, "Relief valve seat worn; pump output within specification.", null),
                "demo-v11-progress-im03");
    }

    private String sparePart(
            String key, String sku, String name, String cost, String reorderLevel, String reorderQty, int lead) {
        var existing = s.list("SELECT id FROM items WHERE tenant_id=? AND sku=?", DemoPersonas.TENANT, sku);
        if (!existing.isEmpty()) return str(existing.getFirst(), "id");
        var item = items.create(
                PLANNER,
                new CatalogCommands.Item(
                        sku,
                        name,
                        "SPARE_PART",
                        "pcs",
                        new BigDecimal(cost),
                        new BigDecimal(reorderLevel),
                        "Maintenance spare part",
                        null,
                        new BigDecimal(reorderQty),
                        lead),
                "demo-v11-item-" + key);
        return str(item, "id");
    }

    private void stock(String key, String item, String quantity, String unitCost, String lot) {
        var adjustment = adjustments.create(
                WAREHOUSE,
                new InventoryCommands.Adjustment(
                        "OTHER",
                        "Opening spare-part stock of the maintenance store",
                        List.of(new InventoryCommands.AdjustmentLine(
                                "FOUND",
                                null,
                                item,
                                DemoPersonas.TENANT + "-raw",
                                new BigDecimal(quantity),
                                new BigDecimal(unitCost),
                                lot))),
                "demo-v11-stock-" + key);
        var posted = adjustments.post(WAREHOUSE, str(adjustment, "id"), "demo-v11-post-" + key);
        if ("APPROVAL_REQUIRED".equals(posted.get("status")))
            adjustments.approve(PLANNER, str(adjustment, "id"), "demo-v11-approve-" + key);
    }

    private void closed(
            String key, String machine, String reason, LocalDate day, String time, int minutes, String notes) {
        OffsetDateTime start = day.atTime(LocalTime.parse(time)).atZone(ZONE).toOffsetDateTime();
        downtime.report(
                OPERATOR,
                new MaintenanceCommands.ReportDowntime(
                        machine,
                        reason,
                        null,
                        start.toString(),
                        start.plusMinutes(minutes).toString(),
                        null,
                        notes),
                "demo-v11-downtime-" + key);
    }

    private static String at(OffsetDateTime time) {
        return time.withNano(0).toString();
    }
}
