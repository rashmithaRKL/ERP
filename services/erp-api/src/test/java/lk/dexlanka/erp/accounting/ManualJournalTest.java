package lk.dexlanka.erp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.admin.AdminCommands;
import org.junit.jupiter.api.Test;

/**
 * V24 manual journals: draft → submit → independent approval → posted (one MANUAL entry on the journal's posting date),
 * the posting rules, the MANUAL_JOURNAL approval policy, return to draft and cancellation, idempotent retries, roles
 * and tenant isolation.
 */
class ManualJournalTest extends AccountingTestSupport {
    String path(String id) {
        return "/api/accounting/journals/" + id;
    }

    @Test
    void aBalancedJournalIsPostedByAnIndependentApprover() throws Exception {
        var date = today.minusDays(3);
        // A draft may be incomplete and unbalanced.
        String id = draft(finance, date, dr("CASH", "100.00"));
        var draft = row("SELECT * FROM manual_journals WHERE id=?", id);
        assertThat(draft).containsEntry("status", "DRAFT");
        assertThat(str(draft, "number")).startsWith("JV-");
        command(finance, path(id) + "/submit", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A manual journal needs at least two lines"));
        command(finance, path(id), body(date, "Petty cash top-up", dr("CASH", "100.00"), cr("BANK", "99.99")))
                .andExpect(status().isOk());
        command(finance, path(id) + "/submit", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("The journal does not balance: debits Rs. 100.00, credits Rs. 99.99"));
        command(
                        finance,
                        path(id),
                        body(
                                date,
                                "Petty cash top-up",
                                dr("CASH", "100.00"),
                                cr("BANK", "60.00"),
                                new AccountingCommands.Line("BANK", null, bd("40.00"), "Second transfer")))
                .andExpect(status().isOk());
        // Exactly one side per line, to the cent.
        command(
                        finance,
                        path(id),
                        body(date, "x", new AccountingCommands.Line("CASH", bd("1"), bd("1"), null), cr("BANK", "1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Line 1: enter either a debit or a credit amount"));
        command(finance, path(id), body(date, "x", dr("CASH", "1.001"), cr("BANK", "1.001")))
                .andExpect(status().isBadRequest());
        command(finance, path(id), body(date, "x", dr("INVENTORIES", "1"), cr("BANK", "1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Line 1: account INVENTORIES is a header account; choose one of its sub-accounts"));
        command(finance, path(id), body(date, "x", dr("NOPE", "1"), cr("BANK", "1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Line 1: account NOPE is not in the chart of accounts"));

        command(finance, path(id) + "/submit", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        // Submitted journals are not editable (API and database).
        command(finance, path(id), body(date, "Changed", dr("CASH", "1"), cr("BANK", "1")))
                .andExpect(status().isConflict());
        assertThatThrownBy(() -> update("UPDATE manual_journal_lines SET memo='x' WHERE journal_id=?", id))
                .hasMessageContaining("only drafts can be edited");

        // Independence: never the creator/submitter, ADMIN included; other roles are refused outright.
        command(finance, path(id) + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A different authorized person must approve this document"));
        command(planner, path(id) + "/approve", null).andExpect(status().isForbidden());
        var detail = get(finance2, path(id));
        assertThat(detail.get("can"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("approve", true);
        assertThat(get(finance, path(id)).get("can"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("approve", false);

        String key = id();
        var approved = json(command(finance2, path(id) + "/approve", null, key).andExpect(status().isOk()));
        assertThat(approved).containsEntry("status", "POSTED");
        // An idempotent retry returns the stored result and posts nothing more; the key cannot be reused elsewhere.
        assertThat(json(command(finance2, path(id) + "/approve", null, key).andExpect(status().isOk())))
                .containsEntry("status", "POSTED");
        command(finance2, path(id) + "/cancel", new AccountingCommands.Reason("Too late now"), key)
                .andExpect(status().isConflict());

        var journal = row("SELECT * FROM manual_journals WHERE id=?", id);
        assertThat(journal).containsEntry("approved_by", finance2.id()).containsEntry("submitted_by", finance.id());
        var entry = row("SELECT * FROM journal_entries WHERE id=?", journal.get("entry_id"));
        assertThat(entry)
                .containsEntry("source", "MANUAL")
                .containsEntry("reference", journal.get("number"))
                .containsEntry("reverses_entry_id", null);
        assertThat(entry.get("posting_date").toString()).isEqualTo(date.toString());
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE tenant_id=? AND source='MANUAL'", t))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM journal_lines WHERE tenant_id=? AND entry_id=?", t, entry.get("id")))
                .isEqualTo(3);
        assertThat(balance("CASH")).isEqualByComparingTo("100");
        assertThat(balance("BANK")).isEqualByComparingTo("-100");
        assertThat(rows("SELECT memo FROM journal_lines WHERE entry_id=? AND memo IS NOT NULL", entry.get("id")))
                .extracting(r -> r.get("memo"))
                .containsExactly("Second transfer");
        assertThat(rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at",
                        t,
                        journal.get("number")))
                .extracting(r -> r.get("action"))
                .containsExactly(
                        "MANUAL_JOURNAL_CREATED",
                        "MANUAL_JOURNAL_UPDATED",
                        "MANUAL_JOURNAL_UPDATED",
                        "MANUAL_JOURNAL_SUBMITTED",
                        "MANUAL_JOURNAL_APPROVED");
        // Posted journals are frozen: no edit, no cancel; the database refuses changes too.
        command(finance, path(id) + "/cancel", new AccountingCommands.Reason("Changed my mind"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Manual journal " + journal.get("number") + " is posted; reverse it instead"));
        assertThatThrownBy(() -> update("UPDATE manual_journals SET narration='x' WHERE id=?", id))
                .hasMessageContaining("reverse it instead");
        assertThatThrownBy(() -> update("DELETE FROM manual_journals WHERE id=?", id))
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void adminApprovesOthersButNeverTheirOwnAndEditorsCannotApprove() throws Exception {
        String own = draft(admin, today, dr("CASH", "5"), cr("BANK", "5"));
        command(admin, path(own) + "/submit", null).andExpect(status().isOk());
        command(admin, path(own) + "/approve", null).andExpect(status().isConflict());
        command(finance, path(own) + "/approve", null).andExpect(status().isOk());

        // finance2 edits finance's draft and submits it: neither may approve, ADMIN may.
        String edited = draft(finance, today, dr("CASH", "5"), cr("BANK", "5"));
        command(finance2, path(edited), body(today, "Edited", dr("CASH", "6"), cr("BANK", "6")))
                .andExpect(status().isOk());
        command(finance, path(edited) + "/submit", null).andExpect(status().isOk());
        command(finance2, path(edited) + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("A different authorized person must approve this journal: you edited or submitted it"));
        command(admin, path(edited) + "/approve", null).andExpect(status().isOk());
        // The database repeats the independence rule.
        String direct = draft(finance, today, dr("CASH", "1"), cr("BANK", "1"));
        assertThatThrownBy(() -> update("UPDATE manual_journals SET approved_by=? WHERE id=?", finance.id(), direct))
                .hasMessageContaining("manual_journals_check");
    }

    @Test
    void theApprovalPolicyDecidesWhetherASecondPersonIsNeeded() throws Exception {
        // FINANCE approves manual journals only; manual journals are approved by FINANCE only.
        command(
                        admin,
                        "/api/admin/approval-policies/MANUAL_JOURNAL",
                        new AdminCommands.Policy(true, null, List.of("PLANNER")))
                .andExpect(status().isConflict());
        command(
                        admin,
                        "/api/admin/approval-policies/PURCHASE_ORDER",
                        new AdminCommands.Policy(true, null, List.of("FINANCE")))
                .andExpect(status().isConflict());
        command(
                        finance,
                        "/api/admin/approval-policies/MANUAL_JOURNAL",
                        new AdminCommands.Policy(false, null, List.of()))
                .andExpect(status().isForbidden());
        var policy = get(finance, "/api/accounting/policy");
        assertThat(policy)
                .containsEntry("documentType", "MANUAL_JOURNAL")
                .containsEntry("approvalRequired", true)
                .containsEntry("approverRoles", List.of("FINANCE"))
                .containsEntry("customized", false);
        // Every role reads the summary (approve buttons); the administration list keeps the operational documents.
        @SuppressWarnings("unchecked")
        var summaries = (List<Map<String, Object>>)
                get(viewer, "/api/approval-policies").get("items");
        assertThat(summaries).extracting(p -> p.get("documentType")).contains("MANUAL_JOURNAL");

        // Above a Rs. 1,000 threshold a second person approves; at or below it, submitting posts directly.
        command(
                        admin,
                        "/api/admin/approval-policies/MANUAL_JOURNAL",
                        new AdminCommands.Policy(true, bd("1000"), List.of("FINANCE")))
                .andExpect(status().isOk());
        String small = draft(finance, today, dr("CASH", "1000.00"), cr("BANK", "1000.00"));
        command(finance, path(small) + "/submit", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));
        var auto = row("SELECT * FROM manual_journals WHERE id=?", small);
        assertThat(auto).containsEntry("approved_by", null).containsEntry("approval_required", false);
        assertThat(row(
                                "SELECT detail FROM audit_events WHERE tenant_id=? AND reference=? AND action='MANUAL_JOURNAL_AUTO_APPROVED'",
                                t,
                                auto.get("number"))
                        .get("detail"))
                .isEqualTo("Auto-approved by policy: Rs. 1000.00 is within the Rs. 1000.00 approval threshold");
        String large = draft(finance, today, dr("CASH", "1000.01"), cr("BANK", "1000.01"));
        command(finance, path(large) + "/submit", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // Switched off: every journal posts on submit.
        command(admin, "/api/admin/approval-policies/MANUAL_JOURNAL", new AdminCommands.Policy(false, null, List.of()))
                .andExpect(status().isOk());
        String off = draft(finance, today, dr("CASH", "50000"), cr("BANK", "50000"));
        command(finance, path(off) + "/submit", null)
                .andExpect(jsonPath("$.status").value("POSTED"));
        // A journal already waiting keeps waiting for an approver (now ADMIN only, as the policy names no role).
        command(finance2, path(large) + "/approve", null).andExpect(status().isForbidden());
        command(admin, path(large) + "/approve", null).andExpect(status().isOk());
    }

    @Test
    void closedPeriodsInactiveAccountsReturnAndCancel() throws Exception {
        var past = today.minusDays(20);
        String id = draft(finance, past, dr("CASH", "10"), cr("BANK", "10"));
        command(finance, path(id) + "/submit", null).andExpect(status().isOk());
        // The period closes before approval: the posting date is checked again on approval.
        update("INSERT INTO financial_periods(tenant_id,end_date,closed) VALUES(?,?,true)", t, today.minusDays(15));
        command(finance2, path(id) + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The financial period covering " + past + " is closed"));
        // Back to draft with a reason (by an approver), re-dated into the open period.
        command(finance2, path(id) + "/return", new AccountingCommands.Reason("Re-date it after the close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        assertThat(row("SELECT * FROM manual_journals WHERE id=?", id))
                .containsEntry("return_reason", "Re-date it after the close")
                .containsEntry("submitted_by", null);
        command(finance, path(id) + "/submit", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The financial period covering " + past + " is closed"));
        command(finance, path(id), body(today, "Re-dated", dr("CASH", "10"), cr("BANK", "10")))
                .andExpect(status().isOk());

        // An account deactivated after the draft was saved is refused on submission.
        update(
                "INSERT INTO gl_accounts(id,tenant_id,code,name,type) VALUES(?,?,'TEMP_FLOAT','Float','ASSET')",
                id(),
                t);
        command(finance, path(id), body(today, "Float", dr("TEMP_FLOAT", "10"), cr("BANK", "10")))
                .andExpect(status().isOk());
        update("UPDATE gl_accounts SET active=false WHERE tenant_id=? AND code='TEMP_FLOAT'", t);
        command(finance, path(id) + "/submit", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Line 1: account TEMP_FLOAT is inactive"));

        // The creator withdraws a submitted journal; others without an approver role cannot return it.
        command(finance, path(id), body(today, "Cash", dr("CASH", "10"), cr("BANK", "10")))
                .andExpect(status().isOk());
        command(finance, path(id) + "/submit", null).andExpect(status().isOk());
        command(planner, path(id) + "/return", new AccountingCommands.Reason("Not mine to return"))
                .andExpect(status().isForbidden());
        command(finance, path(id) + "/return", new AccountingCommands.Reason("Withdrawn to add a line"))
                .andExpect(status().isOk());
        command(finance, path(id) + "/cancel", new AccountingCommands.Reason("No longer needed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        command(finance, path(id) + "/submit", null).andExpect(status().isConflict());
        assertThat(rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND action IN ('MANUAL_JOURNAL_RETURNED','MANUAL_JOURNAL_CANCELLED')",
                        t))
                .hasSize(3);
        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE tenant_id=? AND source='MANUAL'", t))
                .isZero();
    }

    @Test
    void listsDetailRolesAndTenantIsolation() throws Exception {
        String a = draft(finance, today, dr("CASH", "1"), cr("BANK", "1"));
        String b = posted(today, dr("CASH", "2"), cr("BANK", "2"));
        String c = draft(finance, today, dr("CASH", "3"), cr("BANK", "3"));
        command(finance, path(c) + "/submit", null).andExpect(status().isOk());

        var all = get(finance2, "/api/accounting/journals?limit=2");
        @SuppressWarnings("unchecked")
        var first = (List<Map<String, Object>>) all.get("items");
        assertThat(first).extracting(r -> r.get("id")).containsExactly(c, b);
        assertThat(first.get(0)).containsEntry("can_approve", true).containsEntry("status", "SUBMITTED");
        assertThat(all.get("total")).isEqualTo(3);
        assertThat(all.get("counts"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("DRAFT", 1)
                .containsEntry("POSTED", 1)
                .containsEntry("SUBMITTED", 1);
        var rest = get(finance2, "/api/accounting/journals?limit=2&before=" + all.get("next"));
        assertThat(rest.get("items"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(1);
        assertThat(rest.get("next")).isEqualTo("");
        var open = get(finance, "/api/accounting/journals?status=open");
        assertThat(open.get("total")).isEqualTo(2);
        var search = get(
                finance,
                "/api/accounting/journals?q="
                        + row("SELECT number FROM manual_journals WHERE id=?", a)
                                .get("number"));
        assertThat(search.get("total")).isEqualTo(1);
        query(finance, "/api/accounting/journals?status=WRONG").andExpect(status().isConflict());

        var detail = get(finance, path(b));
        assertThat(detail.get("lines"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(2);
        assertThat(detail.get("activity"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(3);
        assertThat(detail.get("can"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("reverse", true)
                .containsEntry("edit", false);

        // Other roles: no access at all.
        for (var role : List.of("PLANNER", "WAREHOUSE", "OPERATOR", "QUALITY", "SALES", "VIEWER", "MAINTENANCE")) {
            var other = createUser(t, role);
            query(other, "/api/accounting/journals").andExpect(status().isForbidden());
            query(other, path(b)).andExpect(status().isForbidden());
            command(other, "/api/accounting/journals", body(today, "x", dr("CASH", "1"), cr("BANK", "1")))
                    .andExpect(status().isForbidden());
            command(other, path(c) + "/submit", null).andExpect(status().isForbidden());
        }
        // Another tenant's finance user cannot see or act on these journals.
        var stranger = createUser(createTenant("Other").id(), "FINANCE");
        query(stranger, path(b)).andExpect(status().isNotFound());
        command(stranger, path(c) + "/approve", null).andExpect(status().isNotFound());
        command(stranger, path(a), body(today, "x", dr("CASH", "1"), cr("BANK", "1")))
                .andExpect(status().isNotFound());
        assertThat(get(stranger, "/api/accounting/journals").get("total")).isEqualTo(0);
    }
}
