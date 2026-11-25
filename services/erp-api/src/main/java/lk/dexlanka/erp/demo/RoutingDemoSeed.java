package lk.dexlanka.erp.demo;

import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.manufacturing.OperationCommands;
import lk.dexlanka.erp.manufacturing.OperationService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.routing.CalendarCommands;
import lk.dexlanka.erp.routing.CalendarService;
import lk.dexlanka.erp.routing.RoutingCommands;
import lk.dexlanka.erp.routing.RoutingService;
import lk.dexlanka.erp.routing.ShiftCalendarService;
import lk.dexlanka.erp.routing.SkillCommands;
import lk.dexlanka.erp.routing.SkillService;
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
 * V10 demo data (fictional), seeded once after the earlier feature seeds:
 *
 * <ul>
 *   <li>skills (extrusion blow moulding, stretch blow moulding, injection moulding, QC sampling, an expired forklift
 *       licence) held by the demo operator, QC sampling by the planner;
 *   <li>shift patterns (day 06:00–14:00 Mon–Sat, evening 14:00–22:00 Mon–Fri, night 22:00–06:00 Mon–Fri), a two-shift
 *       plant default calendar with a Poya holiday and Sunday overtime, and a continuous three-shift calendar for the
 *       blow moulders;
 *   <li>approved routings for {@code FG-001} (bottle), {@code SF-001} (preform) and {@code FG-101} (PET bottle) over
 *       the existing work centers, plus an {@code R02} draft copied from FG-101;
 *   <li>a released FG-001 order whose operation 10 is done (four hours of logged run time) and whose operation 20 is
 *       running, and released preform and PET-bottle orders whose first operations are ready.
 * </ul>
 *
 * Posted through the public commands as {@link DemoPersonas} with fixed {@code demo-v10-} keys; skipped once any of
 * them exists. Only the first operation's start time is backdated (historical demo observation, like the base seed's
 * production history). A failure is logged and rolled back without stopping the application.
 */
@Component
@Order(DemoSeed.ORDER + 100)
class RoutingDemoSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RoutingDemoSeed.class);
    private static final String T = DemoPersonas.TENANT;
    private static final Actor PLANNER = DemoPersonas.PLANNER;
    private static final Actor ADMIN = DemoPersonas.ADMIN;
    private static final Actor OPERATOR = DemoPersonas.OPERATOR;
    private static final Actor WAREHOUSE = DemoPersonas.WAREHOUSE;

    private final Store s;
    private final SkillService skills;
    private final ShiftCalendarService calendars;
    private final RoutingService routings;
    private final ManufacturingService mo;
    private final OperationService operations;
    private final TransactionTemplate tx;
    private final boolean demo;

    RoutingDemoSeed(
            Store s,
            SkillService skills,
            ShiftCalendarService calendars,
            RoutingService routings,
            ManufacturingService mo,
            OperationService operations,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.skills = skills;
        this.calendars = calendars;
        this.routings = routings;
        this.mo = mo;
        this.operations = operations;
        this.tx = new TransactionTemplate(transactions);
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo) return;
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.warn("Routing, shift and operation demo data (V10) was not seeded: {}", e.getMessage());
        }
    }

    /** Idempotent: does nothing once a {@code demo-v10-} command has run or the base demo data is missing. */
    void seed() {
        if (s.list("SELECT 1 FROM tenants WHERE id=?", T).isEmpty()) return;
        if (!s.list("SELECT 1 FROM idempotency_keys WHERE tenant_id=? AND request_key LIKE 'demo-v10-%' LIMIT 1", T)
                .isEmpty()) return;
        var machines = s.list(
                "SELECT id FROM machines WHERE tenant_id=? AND id IN ('bm01','bm02','im01','im02','im03','pk01')", T);
        if (machines.size() < 6) return; // the base demo work centers were changed

        // ---------------------------------------------------------------- skills
        var skill = new HashMap<String, String>();
        String[][] catalogue = {
            {"EBM", "Extrusion blow moulding", "HDPE bottles from extruded parisons"},
            {"SBM", "Stretch blow moulding", "PET bottles from injection-moulded preforms"},
            {"IM", "Injection moulding", "Preforms and caps"},
            {"QCS", "QC sampling", "In-process sampling, leak and weight checks"},
            {"FLT", "Forklift licence", "Material handling in the stores"}
        };
        for (var k : catalogue)
            skill.put(
                    k[0],
                    str(
                            skills.create(
                                    PLANNER,
                                    new SkillCommands.Skill(k[0], k[1], k[2]),
                                    "demo-v10-skill-" + k[0].toLowerCase()),
                            "id"));
        LocalDate today = LocalDate.now(CalendarService.ZONE);
        grant(OPERATOR, skill.get("EBM"), 3, today.plusYears(1), "EBM");
        grant(OPERATOR, skill.get("SBM"), 2, today.plusMonths(6), "SBM");
        grant(OPERATOR, skill.get("IM"), 2, null, "IM");
        grant(OPERATOR, skill.get("QCS"), 2, today.plusMonths(3), "QCS");
        grant(OPERATOR, skill.get("FLT"), 1, today.minusDays(10), "FLT"); // expired: shown in the skill matrix
        grant(PLANNER, skill.get("QCS"), 4, today.plusYears(1), "QCS-PLANNER");

        // ---------------------------------------------------------------- shift calendars
        String day = pattern("DAY", "Day shift", "06:00", "14:00", "10:00", 30, List.of(1, 2, 3, 4, 5, 6));
        String evening = pattern("EVE", "Evening shift", "14:00", "22:00", "18:00", 30, List.of(1, 2, 3, 4, 5));
        String night = pattern("NIGHT", "Night shift", "22:00", "06:00", "02:00", 30, List.of(1, 2, 3, 4, 5));
        String twoShift = str(
                calendars.createCalendar(
                        PLANNER,
                        new CalendarCommands.Calendar("TWO-SHIFT", "Two shifts (plant)", List.of(day, evening), true),
                        "demo-v10-calendar-two-shift"),
                "id");
        String continuous = str(
                calendars.createCalendar(
                        PLANNER,
                        new CalendarCommands.Calendar(
                                "CONTINUOUS", "Three shifts (blow moulding)", List.of(day, evening, night), false),
                        "demo-v10-calendar-continuous"),
                "id");
        for (String machine : List.of("bm01", "bm02"))
            calendars.assign(
                    PLANNER, new CalendarCommands.Assignment(machine, continuous), "demo-v10-assign-" + machine);
        String poya = today.plusDays(9).toString();
        for (var c : Map.of("two-shift", twoShift, "continuous", continuous).entrySet())
            calendars.addException(
                    PLANNER,
                    c.getValue(),
                    new CalendarCommands.DateException(poya, "CLOSED", null, null, "Full moon Poya holiday (demo)"),
                    "demo-v10-poya-" + c.getKey());
        String sunday = today.with(TemporalAdjusters.next(DayOfWeek.SUNDAY)).toString();
        calendars.addException(
                PLANNER,
                twoShift,
                new CalendarCommands.DateException(
                        sunday, "OVERTIME", "08:00", "12:00", "Weekend overtime for urgent orders (demo)"),
                "demo-v10-overtime");

        // ---------------------------------------------------------------- routings (planner authors, admin approves)
        String bottleRouting = approved(
                "bottle",
                "bottle",
                List.of(
                        op(
                                10,
                                "Extrusion blow moulding",
                                "bm01",
                                List.of("bm02"),
                                "45",
                                "0.05",
                                "1",
                                skill.get("EBM"),
                                2,
                                false,
                                "Purge the parison head, check wall thickness on the first 20 bottles."),
                        op(
                                20,
                                "Label, inspect & pack",
                                "pk01",
                                List.of(),
                                "15",
                                "0.02",
                                "2",
                                skill.get("QCS"),
                                1,
                                true,
                                "Sample 5 bottles every 500 for label position and leaks; 50 per carton.")));
        var preform = s.list("SELECT id FROM items WHERE tenant_id=? AND lower(sku)='sf-001'", T);
        if (!preform.isEmpty())
            approved(
                    str(preform.getFirst(), "id"),
                    "preform",
                    List.of(
                            op(
                                    10,
                                    "Injection mould preforms",
                                    "im01",
                                    List.of("im02", "im03"),
                                    "60",
                                    "0.015",
                                    "1",
                                    skill.get("IM"),
                                    2,
                                    false,
                                    "Dry PET resin for 4 h at 170 °C before moulding."),
                            op(
                                    20,
                                    "Weight & visual check",
                                    "im01",
                                    List.of("im02"),
                                    "0",
                                    "0.005",
                                    "1",
                                    skill.get("QCS"),
                                    1,
                                    true,
                                    "Weigh 10 preforms per hour (24 g ± 0.3 g); check the neck finish.")));
        var pet = s.list("SELECT id FROM items WHERE tenant_id=? AND sku='FG-101'", T);
        if (!pet.isEmpty()) {
            String petRouting = approved(
                    str(pet.getFirst(), "id"),
                    "pet-bottle",
                    List.of(
                            op(
                                    10,
                                    "Stretch blow moulding",
                                    "bm02",
                                    List.of("bm01"),
                                    "30",
                                    "0.04",
                                    "1",
                                    skill.get("SBM"),
                                    2,
                                    false,
                                    "Reheat profile P-500C; verify base clearance."),
                            op(20, "Leak test", "pk01", List.of(), "10", "0.01", "1", skill.get("QCS"), 1, true, ""),
                            op(30, "Label & pack", "pk01", List.of(), "15", "0.02", "2", null, null, false, "")));
            // R02: a draft copied from R01 with a faster blow cycle, to compare.
            String draft = str(
                    routings.copy(PLANNER, petRouting, new RoutingCommands.Copy("R02"), "demo-v10-pet-bottle-copy"),
                    "id");
            routings.updateDraft(
                    PLANNER,
                    draft,
                    new RoutingCommands.Draft(
                            "R02",
                            "Trial: faster stretch-blow cycle with the new reheat oven.",
                            List.of(
                                    op(
                                            10,
                                            "Stretch blow moulding",
                                            "bm02",
                                            List.of("bm01"),
                                            "30",
                                            "0.035",
                                            "1",
                                            skill.get("SBM"),
                                            2,
                                            false,
                                            "Reheat profile P-500D."),
                                    op(
                                            20,
                                            "Leak test",
                                            "pk01",
                                            List.of(),
                                            "10",
                                            "0.01",
                                            "1",
                                            skill.get("QCS"),
                                            1,
                                            true,
                                            ""),
                                    op(
                                            30,
                                            "Label & pack",
                                            "pk01",
                                            List.of(),
                                            "15",
                                            "0.02",
                                            "2",
                                            null,
                                            null,
                                            false,
                                            ""))),
                    "demo-v10-pet-bottle-r02");
        }

        // ---------------------------------------------------------------- orders on the shop floor
        // FG-001: operation 10 done after four hours, operation 20 running.
        String bottleOrder = released("bom-bottle", "bm01", "5000", "HIGH", 2, "bottle");
        String blow = operation(bottleOrder, 10);
        operations.start(OPERATOR, blow, new OperationCommands.Start(null, "RUN", null), "demo-v10-bottle-op10-start");
        // Historical demo observation: the run started four hours before seeding.
        s.db().update(
                        "UPDATE mo_operation_time_logs SET started_at=now()-interval '4 hours' WHERE tenant_id=? AND operation_id=? AND ended_at IS NULL",
                        T,
                        blow);
        s.db().update(
                        "UPDATE mo_operations SET started_at=now()-interval '4 hours',ready_at=now()-interval '4 hours 20 minutes' WHERE tenant_id=? AND id=?",
                        T,
                        blow);
        s.db().update(
                        "UPDATE manufacturing_orders SET started_at=now()-interval '4 hours' WHERE tenant_id=? AND id=?",
                        T,
                        bottleOrder);
        operations.finish(
                OPERATOR,
                blow,
                new OperationCommands.Quantities(
                        new BigDecimal("4960"), new BigDecimal("40"), "Start-up parison rejects (demo)"),
                "demo-v10-bottle-op10-finish");
        operations.start(
                OPERATOR,
                operation(bottleOrder, 20),
                new OperationCommands.Start(null, null, null),
                "demo-v10-bottle-op20-start");

        // SF-001 preforms and FG-101 bottles: released, first operations ready (when stock allows).
        if (!preform.isEmpty() && available("pet").compareTo(new BigDecimal("49")) >= 0) {
            var bom = s.list(
                    "SELECT id FROM boms WHERE tenant_id=? AND product_id=? AND status='APPROVED' ORDER BY created_at DESC LIMIT 1",
                    T,
                    str(preform.getFirst(), "id"));
            if (!bom.isEmpty()) released(str(bom.getFirst(), "id"), "im01", "2000", "NORMAL", 4, "preform");
        }
        if (!pet.isEmpty()
                && !preform.isEmpty()
                && available(str(preform.getFirst(), "id")).compareTo(new BigDecimal("1100")) >= 0) {
            var bom = s.list(
                    "SELECT id FROM boms WHERE tenant_id=? AND product_id=? AND status='APPROVED' ORDER BY created_at DESC LIMIT 1",
                    T,
                    str(pet.getFirst(), "id"));
            if (!bom.isEmpty()) released(str(bom.getFirst(), "id"), "bm02", "1000", "URGENT", 1, "pet-bottle");
        }
        log.info("Routing, shift calendar, skill and operation demo data (V10) seeded");
    }

    private void grant(Actor user, String skill, int level, LocalDate until, String key) {
        skills.assign(
                PLANNER,
                new SkillCommands.UserSkill(
                        user.id(), skill, level, until == null ? null : until.toString(), "Demo qualification"),
                "demo-v10-grant-" + key.toLowerCase());
    }

    private String pattern(
            String code, String name, String start, String end, String breakStart, int minutes, List<Integer> days) {
        return str(
                calendars.createPattern(
                        PLANNER,
                        new CalendarCommands.ShiftPattern(code, name, start, end, breakStart, minutes, days),
                        "demo-v10-pattern-" + code.toLowerCase()),
                "id");
    }

    private static RoutingCommands.Operation op(
            int seq,
            String name,
            String machine,
            List<String> alternates,
            String setup,
            String run,
            String crew,
            String skill,
            Integer level,
            boolean inspection,
            String instructions) {
        return new RoutingCommands.Operation(
                seq,
                name,
                machine,
                alternates,
                new BigDecimal(setup),
                new BigDecimal(run),
                new BigDecimal(crew),
                skill,
                level,
                inspection,
                instructions);
    }

    /** Revision R01 authored by the planner and approved by the administrator (a different person). */
    private String approved(String product, String key, List<RoutingCommands.Operation> ops) {
        String id = str(
                routings.create(
                        PLANNER,
                        new RoutingCommands.Routing(product, "R01", "Standard routing (demo)", ops),
                        "demo-v10-" + key + "-routing"),
                "id");
        routings.approve(ADMIN, id, "demo-v10-" + key + "-routing-approve");
        return id;
    }

    /** A manufacturing order created by the planner, approved by the administrator, reserved and issued: RELEASED. */
    private String released(String bom, String machine, String quantity, String priority, int dueDays, String key) {
        String order = str(
                mo.create(
                        PLANNER,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                machine,
                                null,
                                new BigDecimal(quantity),
                                priority,
                                LocalDate.now(CalendarService.ZONE)
                                        .plusDays(dueDays)
                                        .toString()),
                        "demo-v10-" + key + "-order"),
                "id");
        mo.transition(ADMIN, order, "approve", "demo-v10-" + key + "-approve");
        mo.transition(WAREHOUSE, order, "reserve", "demo-v10-" + key + "-reserve");
        mo.transition(WAREHOUSE, order, "issue", "demo-v10-" + key + "-issue");
        return order;
    }

    private String operation(String order, int seq) {
        return str(
                s.one("SELECT id FROM mo_operations WHERE tenant_id=? AND order_id=? AND seq=?", T, order, seq), "id");
    }

    private BigDecimal available(String item) {
        return s.db().queryForObject(
                        "SELECT COALESCE(SUM(b.quantity-b.reserved),0) FROM lots l JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id WHERE l.tenant_id=? AND l.item_id=? AND l.status='RELEASED'",
                        BigDecimal.class,
                        T,
                        item);
    }
}
