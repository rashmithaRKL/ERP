package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import lk.dexlanka.erp.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the base and V12 quality demo seeds inside one transaction that is rolled back, so the shared test database
 * keeps no quality demo data.
 */
class QualityDemoSeedTest extends IntegrationTestBase {
    @Autowired
    DemoSeed base;

    @Autowired
    QualityDemoSeed quality;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void seedsPlansAReworkedNcrAnOpenCriticalNcrAndACapaOnce() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            base.seedDemo();
            quality.seed();

            assertThat(count("SELECT COUNT(*) FROM inspection_plans WHERE tenant_id='demo' AND active"))
                    .isEqualTo(2);
            // The resin receipt was released with its three measured characteristics.
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM inspection_measurements m JOIN inspections q ON q.tenant_id=m.tenant_id AND q.id=m.inspection_id WHERE m.tenant_id='demo' AND q.type='INCOMING' AND q.status='PASSED'"))
                    .isEqualTo(3);
            // NCR from the failed return inspection: partly scrapped, partly reworked, rework lot released.
            var reworked = row(
                    "SELECT * FROM ncrs WHERE tenant_id='demo' AND source='INSPECTION' AND status='DISPOSITIONED' AND defect_code='DIM'");
            assertThat(reworked).containsEntry("severity", "CRITICAL");
            assertThat(rows(
                                    "SELECT type FROM ncr_dispositions WHERE tenant_id='demo' AND ncr_id=? ORDER BY line_no",
                                    reworked.get("id"))
                            .stream()
                            .map(r -> r.get("type"))
                            .toList())
                    .containsExactly("SCRAP", "REWORK");
            var rework = row("SELECT * FROM rework_orders WHERE tenant_id='demo' AND ncr_id=?", reworked.get("id"));
            assertThat(rework).containsEntry("status", "COMPLETED");
            assertThat(row("SELECT status FROM lots WHERE tenant_id='demo' AND id=?", rework.get("output_lot_id")))
                    .containsEntry("status", "RELEASED");
            assertThat(count(
                            "SELECT COUNT(*) FROM rework_lot_links WHERE tenant_id='demo' AND rework_order_id=?",
                            rework.get("id")))
                    .isGreaterThanOrEqualTo(1);
            // An open critical NCR awaiting disposition.
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM ncrs WHERE tenant_id='demo' AND status='OPEN' AND severity='CRITICAL' AND defect_code='LEAK'"))
                    .isEqualTo(1);
            // CAPA in progress: one action done, one overdue.
            var capa = row("SELECT * FROM capas WHERE tenant_id='demo'");
            assertThat(capa).containsEntry("status", "IN_PROGRESS").containsEntry("root_cause_category", "MACHINE");
            assertThat(count(
                            "SELECT COUNT(*) FROM capa_actions WHERE tenant_id='demo' AND capa_id=? AND status='DONE'",
                            capa.get("id")))
                    .isEqualTo(1);
            assertThat(count(
                            "SELECT COUNT(*) FROM capa_actions WHERE tenant_id='demo' AND capa_id=? AND status='OPEN' AND due_date<CURRENT_DATE",
                            capa.get("id")))
                    .isEqualTo(1);
            assertThat(decimal("SELECT COALESCE(SUM(debit-credit),0) FROM journal_lines WHERE tenant_id='demo'"))
                    .isZero();
            assertThat(
                            decimal(
                                    "SELECT COALESCE(SUM(jl.debit-jl.credit),0) FROM journal_lines jl WHERE jl.tenant_id='demo' AND jl.account='REWORK_WIP'"))
                    .isZero();

            // Idempotent: a second start changes nothing.
            long audits = count("SELECT COUNT(*) FROM audit_events WHERE tenant_id='demo'");
            quality.seed();
            quality.run(null);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id='demo'"))
                    .isEqualTo(audits);
            status.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM capas WHERE tenant_id='demo'")).isZero();
    }
}
