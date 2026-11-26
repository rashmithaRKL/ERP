package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.performance.PerformanceDemoSeed;
import lk.dexlanka.erp.performance.PerformanceQuery;
import lk.dexlanka.erp.routing.CalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the base, item master, planning, V10 routing, V11 maintenance and V15 OEE demo seeds in one rolled-back
 * transaction: the backfilled history is consistent (no overlapping time logs per work center, never inside downtime,
 * only inside shift time), plausible and closed, and the seed is idempotent.
 */
class PerformanceDemoSeedTest extends IntegrationTestBase {
    @Autowired
    DemoSeed base;

    @Autowired
    ItemMasterDemoSeed itemMaster;

    @Autowired
    PlanningDemoSeed planning;

    @Autowired
    RoutingDemoSeed routing;

    @Autowired
    MaintenanceDemoSeed maintenance;

    @Autowired
    PerformanceDemoSeed performance;

    @Autowired
    PerformanceQuery query;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    @SuppressWarnings("unchecked")
    void seedsFourteenDaysOfConsistentClosedHistoryOnce() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            base.seedDemo();
            itemMaster.seed();
            planning.seed();
            routing.seed();
            maintenance.persona();
            maintenance.seed();
            performance.seed();

            assertThat(rows(
                                    "SELECT number,status FROM manufacturing_orders WHERE tenant_id='demo' AND number LIKE 'MO-DEMO-OEE-%' ORDER BY number")
                            .stream()
                            .map(r -> r.get("number") + ":" + r.get("status"))
                            .toList())
                    .containsExactly("MO-DEMO-OEE-1:CLOSED", "MO-DEMO-OEE-2:CLOSED", "MO-DEMO-OEE-3:CLOSED");
            String history =
                    "SELECT l.* FROM mo_operation_time_logs l JOIN manufacturing_orders o ON o.tenant_id=l.tenant_id AND o.id=l.order_id WHERE l.tenant_id='demo' AND o.number LIKE 'MO-DEMO-OEE-%'";
            assertThat(count("SELECT COUNT(*) FROM (" + history + ") h")).isGreaterThan(100);
            // No two time logs of one work center overlap (history against everything, including V10 demo logs).
            assertThat(count("""
                            SELECT COUNT(*) FROM (%s) h JOIN mo_operation_time_logs x
                              ON x.tenant_id=h.tenant_id AND x.machine_id=h.machine_id AND x.id<>h.id
                             AND tstzrange(x.started_at,COALESCE(x.ended_at,'infinity')) && tstzrange(h.started_at,h.ended_at)""".formatted(history))).isZero();
            // Never inside effective downtime (V11 demo events and the four V15 events).
            assertThat(count("""
                            SELECT COUNT(*) FROM (%s) h JOIN machine_downtime d
                              ON d.tenant_id=h.tenant_id AND d.machine_id=h.machine_id
                             AND tstzrange(d.started_at,COALESCE(d.ended_at,'infinity')) && tstzrange(h.started_at,h.ended_at)""".formatted(history))).isZero();
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM mo_operation_reports r JOIN manufacturing_orders o ON o.tenant_id=r.tenant_id AND o.id=r.order_id WHERE r.tenant_id='demo' AND o.number LIKE 'MO-DEMO-OEE-%' AND r.notes NOT LIKE 'Demo OEE history%'"))
                    .isZero();
            // Packing never ran ahead of the bottles blown; order totals match their operations.
            for (var order :
                    rows("SELECT * FROM manufacturing_orders WHERE tenant_id='demo' AND number LIKE 'MO-DEMO-OEE-%'")) {
                var ops = rows(
                        "SELECT * FROM mo_operations WHERE tenant_id='demo' AND order_id=? ORDER BY seq",
                        order.get("id"));
                assertThat(ops).allSatisfy(op -> assertThat(op.get("status")).isEqualTo("DONE"));
                assertThat(dec(ops.getLast().get("good_qty"))
                                .add(dec(ops.getLast().get("scrap_qty"))))
                        .isLessThanOrEqualTo(dec(ops.getFirst().get("good_qty")));
                assertThat(dec(order.get("good_qty")))
                        .isEqualByComparingTo(dec(ops.getLast().get("good_qty")));
            }

            LocalDate today = LocalDate.now(CalendarService.ZONE);
            assertThat(count("SELECT COUNT(*) FROM oee_day_snapshots WHERE tenant_id='demo' AND status='CURRENT'"))
                    .isEqualTo(13L * count("SELECT COUNT(*) FROM machines WHERE tenant_id='demo'"));
            assertThat(count(
                            "SELECT COUNT(*) FROM oee_day_snapshots WHERE tenant_id='demo' AND day=?::date",
                            today.minusDays(1).toString()))
                    .isZero();

            var oee = query.oee(
                    DemoPersonas.PLANNER,
                    today.minusDays(14).toString(),
                    today.minusDays(1).toString(),
                    null,
                    "day");
            var plant = (Map<String, Object>) oee.get("plant");
            assertThat(dec(plant.get("oee")).doubleValue()).isBetween(0.45, 0.85);
            var byCode = new HashMap<String, Map<String, Object>>();
            for (var wc : (List<Map<String, Object>>) oee.get("workCenters")) byCode.put((String) wc.get("code"), wc);
            for (String code : List.of("BM-02", "IM-01")) {
                var totals = (Map<String, Object>) byCode.get(code).get("totals");
                assertThat(dec(totals.get("oee")).doubleValue()).as(code).isBetween(0.55, 0.85);
            }
            assertThat(dec(((Map<String, Object>) byCode.get("PK-01").get("totals")).get("oee"))
                            .doubleValue())
                    .isBetween(0.3, 0.8);
            // BM-01 only has output of orders without a routing (base demo): untimed, not a fabricated OEE.
            var bm01 = (Map<String, Object>) byCode.get("BM-01").get("totals");
            assertThat(((Map<String, Object>) bm01.get("statusCounts"))).containsKey("UNTIMED");
            // The bad day: the BM-02 breakdown day is clearly below its average.
            var bm02 = byCode.get("BM-02");
            String bad = today.minusDays(9).toString();
            var badCell = ((List<Map<String, Object>>) bm02.get("cells"))
                    .stream()
                            .filter(c -> bad.equals(c.get("period")))
                            .findFirst()
                            .orElseThrow();
            if (badCell.get("oee") != null)
                assertThat(dec(badCell.get("oee")).doubleValue())
                        .isLessThan(dec(((Map<String, Object>) bm02.get("totals")).get("oee"))
                                        .doubleValue()
                                - 0.1);
            // The history itself raises no data-quality warning on the three timed work centers.
            assertThat(((List<Map<String, Object>>) oee.get("warnings"))
                            .stream()
                                    .filter(w ->
                                            List.of("BM-02", "PK-01", "IM-01").contains(w.get("machine")))
                                    .map(w -> w.get("code"))
                                    .toList())
                    .doesNotContain(
                            "RUN_OUTSIDE_PLANNED_TIME",
                            "LOG_DOWNTIME_OVERLAP",
                            "PERFORMANCE_OVER_100",
                            "OPEN_TIME_LOG");

            long keys = count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id='demo'");
            long logs = count("SELECT COUNT(*) FROM mo_operation_time_logs WHERE tenant_id='demo'");
            performance.seed();
            performance.run(null);
            assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id='demo'"))
                    .isEqualTo(keys);
            assertThat(count("SELECT COUNT(*) FROM mo_operation_time_logs WHERE tenant_id='demo'"))
                    .isEqualTo(logs);
            status.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM oee_day_snapshots WHERE tenant_id='demo'"))
                .isZero();
    }
}
