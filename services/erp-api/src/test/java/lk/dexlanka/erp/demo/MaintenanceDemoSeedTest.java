package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import lk.dexlanka.erp.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs the base and V11 maintenance demo seeds in one rolled-back transaction. */
class MaintenanceDemoSeedTest extends IntegrationTestBase {
    @Autowired
    DemoSeed base;

    @Autowired
    MaintenanceDemoSeed maintenance;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void seedsMaintenanceDataOnce() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            base.seedDemo();
            maintenance.persona();
            maintenance.seed();

            assertThat(row("SELECT role FROM users WHERE id='demo-maintenance'").get("role"))
                    .isEqualTo("MAINTENANCE");
            assertThat(count("SELECT COUNT(*) FROM items WHERE tenant_id='demo' AND type='SPARE_PART'"))
                    .isEqualTo(2);
            // Plans: IM-01 with a generated, scheduled order; BM-01 overdue without one.
            assertThat(count("SELECT COUNT(*) FROM maintenance_plans WHERE tenant_id='demo'"))
                    .isEqualTo(2);
            assertThat(row("SELECT status FROM maintenance_work_orders WHERE tenant_id='demo' AND type='PREVENTIVE'")
                            .get("status"))
                    .isEqualTo("SCHEDULED");
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM maintenance_plans WHERE tenant_id='demo' AND next_due_date<CURRENT_DATE"))
                    .isEqualTo(1);
            // PK-01: completed corrective order with a belt issued and a closed breakdown; the machine is idle.
            var pk = row("SELECT * FROM maintenance_work_orders WHERE tenant_id='demo' AND machine_id='pk01'");
            assertThat(pk.get("status")).isEqualTo("COMPLETED");
            assertThat(dec(pk.get("parts_cost"))).isEqualByComparingTo("1250");
            assertThat(dec(pk.get("labour_hours"))).isEqualByComparingTo("1.25");
            assertThat(row(
                                    "SELECT status FROM downtime_events WHERE tenant_id='demo' AND work_order_id=?",
                                    pk.get("id"))
                            .get("status"))
                    .isEqualTo("CLOSED");
            assertThat(row("SELECT status FROM machines WHERE tenant_id='demo' AND id='pk01'")
                            .get("status"))
                    .isEqualTo("IDLE");
            // IM-03: repair in progress with its breakdown still open.
            var im = row("SELECT * FROM maintenance_work_orders WHERE tenant_id='demo' AND machine_id='im03'");
            assertThat(im.get("status")).isEqualTo("IN_PROGRESS");
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM downtime_events WHERE tenant_id='demo' AND machine_id='im03' AND status='OPEN' AND reason='BREAKDOWN'"))
                    .isEqualTo(1);
            assertThat(
                            count(
                                    "SELECT COUNT(DISTINCT reason) FROM machine_downtime WHERE tenant_id='demo' AND status='CLOSED'"))
                    .isGreaterThanOrEqualTo(6);
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM (SELECT entry_id FROM journal_lines WHERE tenant_id='demo' GROUP BY entry_id HAVING SUM(debit)<>SUM(credit)) x"))
                    .isZero();

            long keys = count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id='demo'");
            maintenance.persona();
            maintenance.seed();
            maintenance.run(null);
            assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id='demo'"))
                    .isEqualTo(keys);
            status.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM maintenance_plans WHERE tenant_id='demo'"))
                .isZero();
    }
}
