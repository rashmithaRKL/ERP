package lk.dexlanka.erp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import lk.dexlanka.erp.admin.AdminCommands;
import lk.dexlanka.erp.catalog.CatalogCommands;
import org.junit.jupiter.api.Test;

/**
 * V24 FINANCE role: what the accountant may do (Accounting, accounting periods, Costs &amp; reports, Overview with the
 * financial and accounting state keys) and may not (other modules' writes, user administration); the approval attention
 * counts; assigning the role.
 */
class FinanceRoleTest extends AccountingTestSupport {
    @Test
    void financeClosesAndReopensPeriodsAndReadsCostsAndReports() throws Exception {
        query(finance, "/api/admin/periods").andExpect(status().isOk());
        command(
                        finance,
                        "/api/admin/periods/close",
                        new AdminCommands.ClosePeriod(today.minusDays(40).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true));
        command(finance, "/api/admin/periods/reopen", new AdminCommands.ReopenPeriod("Late supplier invoice to book"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(false));
        command(
                        planner,
                        "/api/admin/periods/close",
                        new AdminCommands.ClosePeriod(today.minusDays(40).toString()))
                .andExpect(status().isForbidden());
        query(planner, "/api/admin/periods").andExpect(status().isForbidden());
        query(finance, "/api/reports/costs").andExpect(status().isOk());
        query(finance, "/api/reports/journal").andExpect(status().isOk());
        query(viewer, "/api/reports/journal").andExpect(status().isForbidden());
    }

    @Test
    void financeIsNotAnAdministratorOrAnOperationalRole() throws Exception {
        query(finance, "/api/admin/users").andExpect(status().isForbidden());
        query(finance, "/api/admin/approval-policies").andExpect(status().isForbidden());
        query(finance, "/api/admin/audit").andExpect(status().isForbidden());
        command(finance, "/api/purchases/orders/" + id() + "/submit", null).andExpect(status().isForbidden());
        command(finance, "/api/purchases/orders/" + id() + "/approve", null).andExpect(status().isForbidden());
    }

    @Test
    void theStateCarriesFinanceKeysAndApprovalAttentionForTheRightPeople() throws Exception {
        String id = draft(finance, today, dr("CASH", "10"), cr("BANK", "10"));
        command(finance, "/api/accounting/journals/" + id + "/submit", null).andExpect(status().isOk());
        draft(finance, today, dr("CASH", "1"), cr("BANK", "1"));
        update("UPDATE gl_accounts SET needs_review=true WHERE tenant_id=? AND code='ROUNDING'", t);

        var mine = get(finance2, "/api/state?days=14");
        assertThat(mine).containsKeys("financial", "accounting");
        @SuppressWarnings("unchecked")
        var accounting = (Map<String, Object>) mine.get("accounting");
        assertThat(accounting)
                .containsEntry("awaiting_approval", 1)
                .containsEntry("awaiting_my_approval", 1)
                .containsEntry("drafts", 1)
                .containsEntry("needs_review", 1);
        assertThat(accounting.get("awaiting"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(1);
        // The submitter sees it waiting, but not as theirs to approve.
        @SuppressWarnings("unchecked")
        var own = (Map<String, Object>) get(finance, "/api/state?days=14").get("accounting");
        assertThat(own).containsEntry("awaiting_approval", 1).containsEntry("awaiting_my_approval", 0);
        assertThat(own.get("awaiting"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .isEmpty();
        @SuppressWarnings("unchecked")
        var adminState = (Map<String, Object>) get(admin, "/api/state?days=14").get("accounting");
        assertThat(adminState).containsEntry("awaiting_my_approval", 1);
        // Other roles do not receive the key (planners keep the financial aggregates).
        var plannerState = get(planner, "/api/state?days=14");
        assertThat(plannerState).containsKey("financial").doesNotContainKey("accounting");
        assertThat(get(viewer, "/api/state?days=14")).doesNotContainKeys("financial", "accounting");
    }

    @Test
    void administratorsAssignTheFinanceRole() throws Exception {
        command(
                        admin,
                        "/api/users",
                        new CatalogCommands.User(
                                "finance-" + id().substring(0, 8) + "@example.test",
                                "Fictional Accountant",
                                "a-long-enough-password-123",
                                "FINANCE"))
                .andExpect(status().isOk());
        command(admin, "/api/admin/users/" + planner.id(), new AdminCommands.UserUpdate("Planner", "FINANCE"))
                .andExpect(status().isOk());
        assertThat(row("SELECT role FROM users WHERE id=?", planner.id()).get("role"))
                .isEqualTo("FINANCE");
    }
}
