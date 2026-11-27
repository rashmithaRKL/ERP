package lk.dexlanka.erp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.util.List;
import lk.dexlanka.erp.platform.Store;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V24 posting integrity in the database: journal lines name chart accounts only (deferred foreign key), never inactive
 * or header accounts; entries carry a posting date and a source; no entry is dated inside a closed period; posted
 * entries stay immutable (new columns included) and are reversed at most once.
 */
class PostingIntegrityTest extends AccountingTestSupport {
    @Autowired
    PlatformTransactionManager transactions;

    /** Runs {@code work} in its own transaction and returns what it threw (null when it committed). */
    Throwable inTransaction(Runnable work) {
        return catchThrowable(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> work.run()));
    }

    void entry(String id, String date, String account, String other) {
        update(
                "INSERT INTO journal_entries(id,tenant_id,reference,description,actor,posting_date) VALUES(?,?,'DIRECT','Direct','Test',?::date)",
                id,
                t,
                date);
        update(
                "INSERT INTO journal_lines(id,tenant_id,entry_id,account,debit,credit) VALUES(?,?,?,?,10,0),(?,?,?,?,0,10)",
                id(),
                t,
                id,
                account,
                id(),
                t,
                id,
                other);
    }

    @Test
    void journalLinesNeedActivePostableChartAccounts() {
        // Unknown code: refused by the deferred foreign key when the transaction commits; nothing is kept.
        var unknown = inTransaction(() -> entry(id(), today.toString(), "NO_SUCH_ACCOUNT", "BANK"));
        assertThat(unknown).hasMessageContaining("journal_lines_account_fk");
        // A header account and an inactive account: refused by the line trigger.
        var header = inTransaction(() -> entry(id(), today.toString(), "INVENTORIES", "BANK"));
        assertThat(header).hasMessageContaining("Account INVENTORIES is a header account");
        update(
                "INSERT INTO gl_accounts(id,tenant_id,code,name,type,active) VALUES(?,?,'OLD_SUSPENSE','Old suspense','ASSET',false)",
                id(),
                t);
        var inactive = inTransaction(() -> entry(id(), today.toString(), "OLD_SUSPENSE", "BANK"));
        assertThat(inactive).hasMessageContaining("Account OLD_SUSPENSE is inactive");
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE tenant_id=?", t))
                .isZero();
        // The same codes of another tenant's chart do not help: the key includes the tenant.
        var other = createTenant("Other");
        update(
                "INSERT INTO gl_accounts(id,tenant_id,code,name,type) VALUES(?,?,'ONLY_THERE','Elsewhere','ASSET')",
                id(),
                other.id());
        assertThat(inTransaction(() -> entry(id(), today.toString(), "ONLY_THERE", "BANK")))
                .hasMessageContaining("journal_lines_account_fk");
        // A valid entry commits.
        assertThat(inTransaction(() -> entry(id(), today.toString(), "CASH", "BANK")))
                .isNull();
    }

    @Test
    void systemPostingsAreDatedTodayAndPostingDatesRespectClosedPeriods() {
        openingResin("4", "25");
        var system = row("SELECT posting_date,source,reverses_entry_id FROM journal_entries WHERE tenant_id=?", t);
        assertThat(system.get("posting_date").toString()).isEqualTo(today.toString());
        assertThat(system).containsEntry("source", "SYSTEM").containsEntry("reverses_entry_id", null);

        // Close a past period: system postings today go on; a posting dated inside it is refused (Java and database).
        var end = today.minusDays(10);
        update("INSERT INTO financial_periods(tenant_id,end_date,closed) VALUES(?,?,true)", t, end);
        s.journal(admin, "TODAY", "Still open", "CASH", "BANK", bd("5"));
        assertThatThrownBy(() -> s.journal(admin, "BACK", "Backdated", "CASH", "BANK", bd("5"), end))
                .hasMessageContaining("The financial period covering " + end + " is closed");
        assertThat(inTransaction(() -> entry(id(), end.toString(), "CASH", "BANK")))
                .hasMessageContaining("The financial period covering " + end + " is closed");
        String later = s.journal(admin, "AFTER", "After the close", "CASH", "BANK", bd("5"), end.plusDays(1));
        assertThat(row("SELECT posting_date::text AS d,source FROM journal_entries WHERE id=?", later))
                .containsEntry("d", end.plusDays(1).toString())
                .containsEntry("source", "SYSTEM");

        // The multi-line overload: balanced, two lines at least, sides exclusive.
        var lines = List.of(
                new Store.JournalLine("CASH", bd("7.50"), BigDecimal.ZERO, "memo"),
                new Store.JournalLine("BANK", BigDecimal.ZERO, bd("7.49"), null));
        assertThatThrownBy(() -> s.journal(admin, "M", "Unbalanced", today, Store.MANUAL, null, lines))
                .hasMessageContaining("must balance");
        assertThatThrownBy(() -> s.journal(
                        admin,
                        "M",
                        "One line",
                        today,
                        Store.MANUAL,
                        null,
                        List.of(new Store.JournalLine("CASH", bd("1"), BigDecimal.ZERO, null))))
                .hasMessageContaining("at least two lines");
        assertThatThrownBy(() -> s.journal(admin, "M", "Unlinked", today, Store.REVERSAL, null, lines))
                .hasMessageContaining("names the entry");
    }

    @Test
    void postedEntriesStayImmutableAndAreReversedOnce() {
        String original = s.journal(admin, "IMM", "Immutable", "CASH", "BANK", bd("10"), today);
        assertThatThrownBy(() ->
                        update("UPDATE journal_entries SET posting_date=? WHERE id=?", today.minusDays(1), original))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> update("UPDATE journal_entries SET source='MANUAL' WHERE id=?", original))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> update("UPDATE journal_lines SET memo='x' WHERE entry_id=?", original))
                .hasMessageContaining("immutable");
        var swapped = List.of(
                new Store.JournalLine("BANK", bd("10"), BigDecimal.ZERO, null),
                new Store.JournalLine("CASH", BigDecimal.ZERO, bd("10"), null));
        s.journal(admin, "IMM", "Reversal", today, Store.REVERSAL, original, swapped);
        // A second reversal of the same entry is refused by the unique index.
        assertThatThrownBy(() -> s.journal(admin, "IMM", "Again", today, Store.REVERSAL, original, swapped))
                .hasMessageContaining("journal_entries_reversed_once");
        // A REVERSAL must name its entry, other sources must not.
        assertThatThrownBy(() -> update(
                        "INSERT INTO journal_entries(id,tenant_id,reference,description,actor,source) VALUES(?,?,'X','X','X','REVERSAL')",
                        id(),
                        t))
                .hasMessageContaining("journal_entries_reversal_link");
    }
}
