package lk.dexlanka.erp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** V24 reversals: any posted SYSTEM or MANUAL entry, once, swapped sides, dated in an open period, audited. */
class ReversalTest extends AccountingTestSupport {
    String reversePath(String entry) {
        return "/api/accounting/entries/" + entry + "/reverse";
    }

    @Test
    void aSystemEntryIsReversedOnceWithSwappedSides() throws Exception {
        openingResin("10", "30"); // Dr RAW_INVENTORY 300 / Cr OPENING_EQUITY 300
        String original = str(row("SELECT id FROM journal_entries WHERE tenant_id=?", t), "id");
        var detail = get(finance, "/api/accounting/entries/" + original);
        assertThat(detail).containsEntry("source", "SYSTEM").containsEntry("can_reverse", true);

        command(finance, reversePath(original), new AccountingCommands.Reverse(null, "bad"))
                .andExpect(status().isBadRequest());
        command(
                        finance,
                        reversePath(original),
                        new AccountingCommands.Reverse(today.minusDays(1).toString(), "Wrong opening value"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("A reversal cannot be dated before the entry it reverses (" + today + ")"));
        String key = id();
        var reversed = json(command(
                        finance,
                        reversePath(original),
                        new AccountingCommands.Reverse(null, "Wrong opening value"),
                        key)
                .andExpect(status().isOk()));
        String reversal = str(reversed, "id");
        assertThat(reversed).containsEntry("postingDate", today.toString()).containsEntry("reference", "OPENING");
        // The retry returns the same reversal.
        assertThat(json(command(
                                finance,
                                reversePath(original),
                                new AccountingCommands.Reverse(null, "Wrong opening value"),
                                key)
                        .andExpect(status().isOk())))
                .containsEntry("id", reversal);

        var entry = row("SELECT * FROM journal_entries WHERE id=?", reversal);
        assertThat(entry)
                .containsEntry("source", "REVERSAL")
                .containsEntry("reverses_entry_id", original)
                .containsEntry("reference", "OPENING");
        assertThat(str(entry, "description")).endsWith("Wrong opening value");
        var lines = rows("SELECT account,debit,credit FROM journal_lines WHERE entry_id=? ORDER BY account", reversal);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("account", "OPENING_EQUITY");
        assertThat(dec(lines.get(0).get("debit"))).isEqualByComparingTo("300");
        assertThat(lines.get(1)).containsEntry("account", "RAW_INVENTORY");
        assertThat(dec(lines.get(1).get("credit"))).isEqualByComparingTo("300");
        assertThat(balance("RAW_INVENTORY")).isZero();
        assertThat(balance("OPENING_EQUITY")).isZero();
        assertThat(row("SELECT detail FROM audit_events WHERE tenant_id=? AND action='JOURNAL_REVERSED'", t)
                        .get("detail"))
                .isEqualTo("Entry of " + today + " (Rs. 300.00) reversed on " + today + ": Wrong opening value");

        // At most once, and a reversal is never reversed.
        command(finance, reversePath(original), new AccountingCommands.Reverse(null, "Second attempt"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Entry OPENING was already reversed on " + today));
        command(finance2, reversePath(reversal), new AccountingCommands.Reverse(null, "Undo the undo"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Entry OPENING is itself a reversal; post a new manual journal instead"));
        assertThat(get(finance, "/api/accounting/entries/" + original))
                .containsEntry("reversed_by_entry_id", reversal)
                .containsEntry("can_reverse", false);
        assertThat(get(finance, "/api/accounting/entries/" + reversal))
                .containsEntry("reverses_entry_id", original)
                .containsEntry("can_reverse", false);
    }

    @Test
    void reversingAManualJournalMarksItReversed() throws Exception {
        String journal = posted(today.minusDays(2), dr("CASH", "250.00"), cr("BANK", "250.00"));
        String entry = entryOf(journal);
        // Into a closed period: refused.
        update("INSERT INTO financial_periods(tenant_id,end_date,closed) VALUES(?,?,true)", t, today.minusDays(1));
        command(
                        finance,
                        reversePath(entry),
                        new AccountingCommands.Reverse(today.minusDays(1).toString(), "Posted to the wrong account"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("The financial period covering " + today.minusDays(1) + " is closed"));
        var result = json(command(
                        finance,
                        reversePath(entry),
                        new AccountingCommands.Reverse(today.toString(), "Posted to the wrong account"))
                .andExpect(status().isOk()));
        var j = row("SELECT * FROM manual_journals WHERE id=?", journal);
        assertThat(j)
                .containsEntry("status", "REVERSED")
                .containsEntry("reversal_entry_id", result.get("id"))
                .containsEntry("reversed_by", finance.id())
                .containsEntry("reversal_reason", "Posted to the wrong account");
        assertThat(balance("CASH")).isZero();
        var detail = get(finance, "/api/accounting/journals/" + journal);
        assertThat(detail).containsEntry("status", "REVERSED").containsEntry("reversal_date", today.toString());
        @SuppressWarnings("unchecked")
        var activity = (List<Map<String, Object>>) detail.get("activity");
        assertThat(activity).extracting(r -> r.get("action")).endsWith("JOURNAL_REVERSED");
        assertThat(rows(
                        "SELECT source FROM journal_entries WHERE tenant_id=? AND reference=? ORDER BY created_at",
                        t,
                        j.get("number")))
                .extracting(r -> r.get("source"))
                .containsExactly("MANUAL", "REVERSAL");
    }

    @Test
    void onlyFinanceAndAdminReverseAndOnlyTheirOwnTenantsEntries() throws Exception {
        String journal = posted(today, dr("CASH", "9.99"), cr("BANK", "9.99"));
        String entry = entryOf(journal);
        var reverse = new AccountingCommands.Reverse(null, "Role check reversal");
        command(planner, reversePath(entry), reverse).andExpect(status().isForbidden());
        command(viewer, reversePath(entry), reverse).andExpect(status().isForbidden());
        query(planner, "/api/accounting/entries/" + entry).andExpect(status().isForbidden());
        var stranger = createUser(createTenant("Other").id(), "FINANCE");
        command(stranger, reversePath(entry), reverse).andExpect(status().isNotFound());
        query(stranger, "/api/accounting/entries/" + entry).andExpect(status().isNotFound());
        command(admin, reversePath(entry), reverse).andExpect(status().isOk());
    }
}
