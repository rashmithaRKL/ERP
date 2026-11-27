package lk.dexlanka.erp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V24 trial balance (as of a date and for a period) and account ledger (opening balance, running balance, keyset
 * paging), plus the posting-date and source filters of the Costs &amp; reports journal.
 */
class TrialBalanceAndLedgerTest extends AccountingTestSupport {
    String cash;

    /**
     * today−10: Dr CASH 1000 / Cr OPENING_EQUITY 1000; today−5: Dr MAINTENANCE_EXPENSE 200 / Cr CASH 200 and Dr BANK
     * 300 / Cr CASH 300 (reversed on today−1); today: opening stock Dr RAW_INVENTORY 500 / Cr OPENING_EQUITY 500.
     */
    @BeforeEach
    void postings() throws Exception {
        posted(today.minusDays(10), dr("CASH", "1000.00"), cr("OPENING_EQUITY", "1000.00"));
        posted(today.minusDays(5), dr("MAINTENANCE_EXPENSE", "200.00"), cr("CASH", "200.00"));
        String bank = posted(today.minusDays(5), dr("BANK", "300.00"), cr("CASH", "300.00"));
        command(
                        finance,
                        "/api/accounting/entries/" + entryOf(bank) + "/reverse",
                        new AccountingCommands.Reverse(today.minusDays(1).toString(), "Transfer never left the till"))
                .andExpect(status().isOk());
        openingResin("10", "50");
        cash = accountId(t, "CASH");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> byCode(Map<String, Object> result) {
        var out = new HashMap<String, Map<String, Object>>();
        for (var r : (List<Map<String, Object>>) result.get("items")) out.put(str(r, "code"), r);
        return out;
    }

    static BigDecimal total(Map<String, Object> result, String column) {
        @SuppressWarnings("unchecked")
        var totals = (Map<String, Object>) result.get("totals");
        return dec(totals.get(column));
    }

    @Test
    void theTrialBalanceAsOfADateBalancesAndMatchesEveryPosting() throws Exception {
        var tb = get(finance, "/api/accounting/trial-balance");
        assertThat(tb)
                .containsEntry("mode", "AS_OF")
                .containsEntry("to", today.toString())
                .containsEntry("balanced", true);
        BigDecimal posted = decimal("SELECT SUM(debit) FROM journal_lines WHERE tenant_id=?", t);
        assertThat(posted).isEqualByComparingTo("2300");
        assertThat(total(tb, "debit")).isEqualByComparingTo(posted);
        assertThat(total(tb, "credit")).isEqualByComparingTo(posted);
        assertThat(total(tb, "closing_debit")).isEqualByComparingTo(total(tb, "closing_credit"));
        var rows = byCode(tb);
        assertThat(rows.keySet())
                .containsExactlyInAnyOrder("CASH", "BANK", "OPENING_EQUITY", "MAINTENANCE_EXPENSE", "RAW_INVENTORY");
        assertThat(dec(rows.get("CASH").get("debit"))).isEqualByComparingTo("1300");
        assertThat(dec(rows.get("CASH").get("credit"))).isEqualByComparingTo("500");
        assertThat(dec(rows.get("CASH").get("closing_debit"))).isEqualByComparingTo("800");
        assertThat(dec(rows.get("OPENING_EQUITY").get("closing_credit"))).isEqualByComparingTo("1500");
        assertThat(dec(rows.get("BANK").get("closing_debit"))).isZero();
        assertThat(dec(rows.get("BANK").get("closing_credit"))).isZero();
        // Every account's closing balance equals the sum of its posted lines.
        for (var r : rows.values())
            assertThat(dec(r.get("closing"))).as(str(r, "code")).isEqualByComparingTo(balance(str(r, "code")));

        // Before the reversal and the opening stock: only the first two dates count.
        var earlier = get(finance, "/api/accounting/trial-balance?to=" + today.minusDays(6));
        assertThat(byCode(earlier).keySet()).containsExactlyInAnyOrder("CASH", "OPENING_EQUITY");
        assertThat(total(earlier, "debit")).isEqualByComparingTo("1000");
        // zero=true lists every postable account.
        var all = get(finance, "/api/accounting/trial-balance?zero=true");
        assertThat(byCode(all)).containsKey("WIP").doesNotContainKey("INVENTORIES");
        assertThat(total(all, "debit")).isEqualByComparingTo(posted);
        query(finance, "/api/accounting/trial-balance?to=yesterday").andExpect(status().isBadRequest());
    }

    @Test
    void theTrialBalanceForAPeriodCarriesOpeningAndClosingBalances() throws Exception {
        var tb = get(finance, "/api/accounting/trial-balance?from=" + today.minusDays(5) + "&to=" + today.minusDays(1));
        assertThat(tb).containsEntry("mode", "PERIOD").containsEntry("balanced", true);
        var rows = byCode(tb);
        var c = rows.get("CASH");
        assertThat(dec(c.get("opening_debit"))).isEqualByComparingTo("1000");
        assertThat(dec(c.get("debit"))).isEqualByComparingTo("300");
        assertThat(dec(c.get("credit"))).isEqualByComparingTo("500");
        assertThat(dec(c.get("closing_debit"))).isEqualByComparingTo("800");
        assertThat(dec(rows.get("OPENING_EQUITY").get("opening_credit"))).isEqualByComparingTo("1000");
        assertThat(dec(rows.get("OPENING_EQUITY").get("debit"))).isZero();
        assertThat(rows).doesNotContainKey("RAW_INVENTORY"); // posted today, after the period
        assertThat(total(tb, "opening_debit")).isEqualByComparingTo("1000");
        assertThat(total(tb, "debit")).isEqualByComparingTo("800");
        assertThat(total(tb, "credit")).isEqualByComparingTo("800");
        assertThat(total(tb, "closing_debit")).isEqualByComparingTo("1000");
        assertThat(total(tb, "closing_credit")).isEqualByComparingTo("1000");
        query(finance, "/api/accounting/trial-balance?from=" + today + "&to=" + today.minusDays(1))
                .andExpect(status().isConflict());
    }

    @Test
    void theLedgerRunsTheBalanceAcrossPages() throws Exception {
        var ledger = get(finance, "/api/accounting/accounts/" + cash + "/ledger?limit=2");
        @SuppressWarnings("unchecked")
        var first = (List<Map<String, Object>>) ledger.get("items");
        assertThat(first).extracting(r -> dec(r.get("balance")).intValue()).containsExactly(1000, 800);
        assertThat(dec(ledger.get("opening"))).isZero();
        assertThat(dec(ledger.get("closing"))).isEqualByComparingTo("800");
        assertThat(first.get(0))
                .containsEntry("posting_date", today.minusDays(10).toString());
        var second = get(finance, "/api/accounting/accounts/" + cash + "/ledger?limit=2&before=" + ledger.get("next"));
        @SuppressWarnings("unchecked")
        var rest = (List<Map<String, Object>>) second.get("items");
        assertThat(rest).extracting(r -> dec(r.get("balance")).intValue()).containsExactly(500, 800);
        assertThat(rest.get(1)).containsEntry("source", "REVERSAL");
        assertThat(rest.get(0).get("reversed_by_entry_id")).isNotNull();
        assertThat(second.get("next")).isEqualTo("");

        // A date range: the opening balance is everything before it; paging one line at a time carries the balance.
        String range = "from=" + today.minusDays(5) + "&to=" + today.minusDays(1);
        var page = get(finance, "/api/accounting/accounts/" + cash + "/ledger?limit=1&" + range);
        assertThat(dec(page.get("opening"))).isEqualByComparingTo("1000");
        assertThat(dec(page.get("closing"))).isEqualByComparingTo("800");
        @SuppressWarnings("unchecked")
        var totals = (Map<String, Object>) page.get("totals");
        assertThat(dec(totals.get("debit"))).isEqualByComparingTo("300");
        assertThat(dec(totals.get("credit"))).isEqualByComparingTo("500");
        var balances = new java.util.ArrayList<Integer>();
        while (true) {
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) page.get("items");
            items.forEach(r -> balances.add(dec(r.get("balance")).intValue()));
            if ("".equals(page.get("next"))) break;
            page = get(
                    finance,
                    "/api/accounting/accounts/" + cash + "/ledger?limit=1&" + range + "&before=" + page.get("next"));
        }
        assertThat(balances).containsExactly(800, 500, 800);

        // A header account's ledger covers its sub-accounts.
        var assets = get(finance, "/api/accounting/accounts/" + accountId(t, "ASSETS") + "/ledger?limit=200");
        @SuppressWarnings("unchecked")
        var lines = (List<Map<String, Object>>) assets.get("items");
        assertThat(lines).extracting(r -> r.get("account")).contains("CASH", "BANK", "RAW_INVENTORY");
        assertThat(dec(assets.get("closing"))).isEqualByComparingTo("1300"); // CASH 800 + RAW_INVENTORY 500
        query(finance, "/api/accounting/accounts/" + cash + "/ledger?limit=0").andExpect(status().isConflict());
    }

    @Test
    void theReportsJournalFiltersByPostingDateAndSource() throws Exception {
        var reversals = get(finance, "/api/reports/journal?source=REVERSAL");
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) reversals.get("items");
        assertThat(items).hasSize(2).allSatisfy(r -> assertThat(r).containsEntry("source", "REVERSAL"));
        assertThat(items.get(0))
                .containsEntry("posting_date", today.minusDays(1).toString());
        var manual = get(
                planner,
                "/api/reports/journal?source=manual&postedFrom=" + today.minusDays(5) + "&postedTo="
                        + today.minusDays(5));
        @SuppressWarnings("unchecked")
        var manualItems = (List<Map<String, Object>>) manual.get("items");
        assertThat(manualItems).hasSize(4).allSatisfy(r -> assertThat(r).containsEntry("source", "MANUAL"));
        var system = get(finance, "/api/reports/journal?source=SYSTEM");
        @SuppressWarnings("unchecked")
        var totals = (Map<String, Object>) system.get("totals");
        assertThat(dec(totals.get("debit"))).isEqualByComparingTo("500");
        query(finance, "/api/reports/journal?source=OTHER").andExpect(status().isConflict());
        query(finance, "/api/reports/journal?postedFrom=" + today + "&postedTo=" + today.minusDays(1))
                .andExpect(status().isConflict());
    }
}
