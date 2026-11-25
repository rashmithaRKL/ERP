package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import lk.dexlanka.erp.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the base, item master, planning, V10 routing, V11 maintenance and V13 schedule demo seeds in one rolled-back
 * transaction.
 */
class ScheduleDemoSeedTest extends IntegrationTestBase {
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
    ScheduleDemoSeed schedule;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void publishesAScheduleWithBlockedTimeALateOrderAndAPin() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            base.seedDemo();
            itemMaster.seed();
            planning.seed();
            routing.seed();
            maintenance.persona();
            maintenance.seed();
            schedule.seed();

            // Two runs: the first superseded by the re-run after pinning.
            var published = row("SELECT * FROM schedule_runs WHERE tenant_id='demo' AND status='PUBLISHED'");
            assertThat(count("SELECT COUNT(*) FROM schedule_runs WHERE tenant_id='demo' AND status='SUPERSEDED'"))
                    .isEqualTo(1);
            String run = str(published, "id");
            // Routed work on blow moulding, injection moulding and packing work centers.
            assertThat(rows(
                                    "SELECT DISTINCT substr(machine_id,1,2) AS kind FROM schedule_run_operations WHERE tenant_id='demo' AND run_id=?",
                                    run)
                            .stream()
                            .map(r -> str(r, "kind"))
                            .toList())
                    .contains("bm", "im", "pk");
            // IM-03's open breakdown is blocked time; at least one order is late; the pinned operation is honoured.
            assertThat(count(
                            "SELECT COUNT(*) FROM schedule_run_blocks WHERE tenant_id='demo' AND run_id=? AND machine_id='im03' AND kind='DOWNTIME'",
                            run))
                    .isEqualTo(1);
            assertThat(((Number) published.get("late_orders")).intValue()).isPositive();
            assertThat(count(
                            "SELECT COUNT(*) FROM schedule_run_operations WHERE tenant_id='demo' AND run_id=? AND pin_status='HONOURED'",
                            run))
                    .isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM schedule_pins WHERE tenant_id='demo' AND removed_at IS NULL"))
                    .isEqualTo(1);
            assertThat(count(
                            "SELECT COUNT(*) FROM schedule_run_orders WHERE tenant_id='demo' AND run_id=? AND estimate_minutes IS NOT NULL AND status='SCHEDULED'",
                            run))
                    .isEqualTo(1);

            long keys = count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id='demo'");
            schedule.seed();
            schedule.run(null);
            assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id='demo'"))
                    .isEqualTo(keys);
            status.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM schedule_runs WHERE tenant_id='demo'"))
                .isZero();
    }
}
