package lk.dexlanka.erp.demo;

import static org.assertj.core.api.Assertions.assertThat;

import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.accounting.AccountingDemoSeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs the base and V24 general-ledger demo seeds in one rolled-back transaction. */
class AccountingDemoSeedTest extends IntegrationTestBase {
    @Autowired
    DemoSeed base;

    @Autowired
    AccountingDemoSeed accounting;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void seedsTheAccountantAndOneJournalPerStateOnce() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            base.seedDemo();
            accounting.persona();
            accounting.seed();

            assertThat(row("SELECT name,role FROM users WHERE id='demo-finance'"))
                    .containsEntry("role", "FINANCE")
                    .containsEntry("name", "Sanduni Rajapaksa");
            assertThat(rows("SELECT status FROM manual_journals WHERE tenant_id='demo' ORDER BY status"))
                    .extracting(r -> r.get("status"))
                    .containsExactly("DRAFT", "POSTED", "REVERSED", "SUBMITTED");
            var electricity = row("SELECT * FROM manual_journals WHERE tenant_id='demo' AND status='POSTED'");
            assertThat(str(electricity, "narration")).startsWith("Accrued electricity for");
            assertThat(electricity)
                    .containsEntry("created_by", "demo-finance")
                    .containsEntry("approved_by", "demo-admin");
            assertThat(dec(electricity.get("total_debit"))).isEqualByComparingTo("182450");
            assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE tenant_id='demo' AND source='REVERSAL'"))
                    .isEqualTo(1);
            assertThat(
                            decimal(
                                    "SELECT SUM(debit-credit) FROM journal_lines WHERE tenant_id='demo' AND account='UTILITIES_EXPENSE'"))
                    .isEqualByComparingTo("182450");
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM gl_accounts WHERE tenant_id='demo' AND code IN ('UTILITIES_EXPENSE','INSURANCE_EXPENSE','ACCRUED_EXPENSES','PREPAID_EXPENSES')"))
                    .isEqualTo(4);
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM (SELECT entry_id FROM journal_lines WHERE tenant_id='demo' GROUP BY entry_id HAVING SUM(debit)<>SUM(credit)) x"))
                    .isZero();

            long keys = count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id='demo'");
            accounting.persona();
            accounting.seed();
            assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id='demo'"))
                    .isEqualTo(keys);
            status.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM manual_journals WHERE tenant_id='demo'"))
                .isZero();
    }
}
