package lk.dexlanka.erp.demo;

import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.routing.CalendarService;
import lk.dexlanka.erp.scheduling.ScheduleCommands;
import lk.dexlanka.erp.scheduling.ScheduleService;
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
 * V13 demo data (fictional), seeded once after the V10–V12 seeds: four more approved orders over the routed products
 * (a bottle rush order due today that the schedule cannot finish in time, a preform batch, a second PET-bottle order
 * and a bottle campaign that runs into the Poya week), a duration estimate for the oldest open order without a
 * routing, a schedule run published by the planner, then the PET order's last operation pinned six hours after its
 * scheduled start ("customer collection slot") with a re-run that is published too: the Gantt shows BM, IM and PK work
 * centers, the IM-03 breakdown as blocked time, the IM-01 preventive window, the Poya holiday, late orders, a pinned
 * operation and a superseded run to compare with. Posted through the public commands as {@link DemoPersonas} with
 * {@code demo-v13-} keys; skipped once any of them exists. A failure is logged and rolled back without stopping the
 * application.
 */
@Component
@Order(DemoSeed.ORDER + 130)
class ScheduleDemoSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ScheduleDemoSeed.class);
    private static final String T = DemoPersonas.TENANT;
    private static final Actor PLANNER = DemoPersonas.PLANNER;
    private static final Actor ADMIN = DemoPersonas.ADMIN;

    private final Store s;
    private final ManufacturingService mo;
    private final ScheduleService schedule;
    private final TransactionTemplate tx;
    private final boolean demo;

    ScheduleDemoSeed(
            Store s,
            ManufacturingService mo,
            ScheduleService schedule,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.mo = mo;
        this.schedule = schedule;
        this.tx = new TransactionTemplate(transactions);
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo) return;
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.warn("Production schedule demo data (V13) was not seeded: {}", e.getMessage());
        }
    }

    /** Idempotent: does nothing once a {@code demo-v13-} command has run or the routed demo data is missing. */
    void seed() {
        if (s.list("SELECT 1 FROM tenants WHERE id=?", T).isEmpty()) return;
        if (!s.list("SELECT 1 FROM idempotency_keys WHERE tenant_id=? AND request_key LIKE 'demo-v13-%' LIMIT 1", T)
                .isEmpty()) return;
        if (s.list("SELECT 1 FROM routing_defaults WHERE tenant_id=? LIMIT 1", T)
                .isEmpty()) return; // no routings (V10 demo data missing)
        LocalDate today = LocalDate.now(CalendarService.ZONE);

        // ---------------------------------------------------------------- more routed work
        if (!s.list("SELECT 1 FROM boms WHERE tenant_id=? AND id='bom-bottle' AND status='APPROVED'", T)
                .isEmpty()) {
            approved("rush", "bom-bottle", "bm01", "30000", "NORMAL", today);
            approved("campaign", "bom-bottle", "bm01", "60000", "NORMAL", today.plusDays(14));
        }
        String preformBom = currentBom("sf-001");
        if (preformBom != null) approved("preform", preformBom, "im01", "30000", "HIGH", today.plusDays(6));
        String petBom = currentBom("fg-101");
        String pet = petBom == null ? null : approved("pet", petBom, "bm02", "40000", "NORMAL", today.plusDays(12));

        // ---------------------------------------------------------------- an estimate for an order without a routing
        var legacy = s.list("""
                SELECT o.id FROM manufacturing_orders o
                WHERE o.tenant_id=? AND o.status IN ('APPROVED','MATERIALS_RESERVED','RELEASED','ON_HOLD')
                  AND NOT EXISTS (SELECT 1 FROM mo_operations x WHERE x.tenant_id=o.tenant_id AND x.order_id=o.id)
                ORDER BY o.created_at,o.id LIMIT 1""", T);
        if (!legacy.isEmpty())
            schedule.estimate(
                    PLANNER,
                    new ScheduleCommands.Estimate(
                            str(legacy.getFirst(), "id"),
                            new BigDecimal("360"),
                            "Six hours on its work center (demo estimate)"),
                    "demo-v13-estimate");

        // ---------------------------------------------------------------- publish, pin, re-run, publish
        String first = str(
                schedule.run(
                        PLANNER,
                        new ScheduleCommands.Run(null, null, null, null, "Weekly production schedule (demo)"),
                        "demo-v13-run-1"),
                "id");
        schedule.publish(PLANNER, first, "demo-v13-publish-1");
        var candidates = s.list("""
                SELECT x.operation_id,x.machine_id,x.start_at FROM schedule_run_operations x
                JOIN mo_operations op ON op.tenant_id=x.tenant_id AND op.id=x.operation_id
                WHERE x.tenant_id=? AND x.run_id=? AND op.status IN ('PENDING','READY') AND x.pin_id IS NULL
                ORDER BY (x.order_id=?) DESC,x.seq DESC,x.start_at
                LIMIT 1""", T, first, pet == null ? "" : pet);
        if (candidates.isEmpty()) return;
        var target = candidates.getFirst();
        String start = ((Timestamp) target.get("start_at"))
                .toInstant()
                .plus(6, ChronoUnit.HOURS)
                .atZone(CalendarService.ZONE)
                .toLocalDateTime()
                .truncatedTo(ChronoUnit.MINUTES)
                .toString();
        var pinned = schedule.pin(
                PLANNER,
                new ScheduleCommands.Pin(
                        null,
                        str(target, "operation_id"),
                        str(target, "machine_id"),
                        start,
                        "Customer collection slot (demo)",
                        true),
                "demo-v13-pin");
        @SuppressWarnings("unchecked")
        var rerun = (java.util.Map<String, Object>) pinned.get("run");
        schedule.publish(PLANNER, str(rerun, "id"), "demo-v13-publish-2");
        log.info("Production schedule demo data (V13) seeded");
    }

    private String currentBom(String sku) {
        var bom = s.list("""
                SELECT b.id FROM boms b JOIN items i ON i.tenant_id=b.tenant_id AND i.id=b.product_id
                WHERE b.tenant_id=? AND lower(i.sku)=? AND b.status='APPROVED'
                  AND EXISTS (SELECT 1 FROM routing_defaults d WHERE d.tenant_id=b.tenant_id AND d.product_id=b.product_id)
                ORDER BY b.created_at DESC LIMIT 1""", T, sku);
        return bom.isEmpty() ? null : str(bom.getFirst(), "id");
    }

    /** An order created by the planner and approved by the administrator (materials not yet reserved). */
    private String approved(String key, String bom, String machine, String quantity, String priority, LocalDate due) {
        String order = str(
                mo.create(
                        PLANNER,
                        new ManufacturingCommands.Manufacture(
                                bom, machine, null, new BigDecimal(quantity), priority, due.toString()),
                        "demo-v13-" + key + "-order"),
                "id");
        if ("DRAFT"
                .equals(s.one("SELECT status FROM manufacturing_orders WHERE tenant_id=? AND id=?", T, order)
                        .get("status"))) mo.transition(ADMIN, order, "approve", "demo-v13-" + key + "-approve");
        return order;
    }
}
