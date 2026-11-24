package lk.dexlanka.erp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.inventory.InventoryCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Accounting period administration: close in order (never in the future), the unchanged posting guard (a closed period
 * covering today blocks postings, a closed past period does not), reopen the latest closed period with an audited
 * reason, permissions and tenant isolation.
 */
class AccountingPeriodTest extends AdminTestSupport {
    String today, resinLot;

    @BeforeEach
    void stock() {
        today = s.db().queryForObject("SELECT CURRENT_DATE::text", String.class);
        String resin = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'RM','Resin','RAW_MATERIAL','kg',10,0)",
                resin,
                t);
        resinLot = openingStock(admin, resin, tenant.rawWarehouse(), "100", "10");
    }

    private org.springframework.test.web.servlet.ResultActions postAdjustment() throws Exception {
        var line = new InventoryCommands.AdjustmentLine("DECREASE", resinLot, null, null, bd("1"), null, null);
        var draft = json(command(
                        warehouse,
                        "/api/inventory/adjustments",
                        new InventoryCommands.Adjustment("DAMAGE", "Period test", List.of(line)))
                .andExpect(status().isOk()));
        return command(warehouse, "/api/inventory/adjustments/" + draft.get("id") + "/post", null);
    }

    private String day(int offset) {
        return LocalDate.parse(today).plusDays(offset).toString();
    }

    @Test
    void closingTodayBlocksPostingsUntilTheLatestPeriodIsReopened() throws Exception {
        // A closed past period leaves today's postings alone.
        command(admin, "/api/admin/periods/close", new AdminCommands.ClosePeriod(day(-31)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true));
        postAdjustment().andExpect(status().isOk());

        command(admin, "/api/admin/periods/close", new AdminCommands.ClosePeriod(today))
                .andExpect(status().isOk());
        postAdjustment()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The financial period is closed"));
        var listed = json(query(admin, "/api/admin/periods").andExpect(status().isOk()));
        assertThat(listed.get("postingBlocked")).isEqualTo(true);
        assertThat(listed.get("latestClosed")).isEqualTo(today);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) listed.get("items");
        assertThat(items).extracting(p -> p.get("end_date")).containsExactly(today, day(-31));
        assertThat(items.get(0).get("start_date")).isEqualTo(day(-30));
        assertThat(items.get(1).get("start_date")).isNull();
        assertThat(items.get(0).get("closed_by")).isEqualTo("ADMIN");
        assertThat(((Number) items.get(0).get("journal_entries")).intValue()).isEqualTo(2); // opening + adjustment

        command(admin, "/api/admin/periods/reopen", new AdminCommands.ReopenPeriod("short"))
                .andExpect(status().isBadRequest());
        command(admin, "/api/admin/periods/reopen", new AdminCommands.ReopenPeriod("Late supplier invoice to post"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value(today))
                .andExpect(jsonPath("$.closed").value(false));
        postAdjustment().andExpect(status().isOk());
        var reopened = row("SELECT * FROM financial_periods WHERE tenant_id=? AND end_date=?::date", t, today);
        assertThat(reopened.get("reopen_reason")).isEqualTo("Late supplier invoice to post");
        assertThat(reopened.get("reopened_by")).isEqualTo(admin.id());
        assertThat(rows(
                        "SELECT action,reference,detail FROM audit_events WHERE tenant_id=? AND action LIKE 'PERIOD_%' ORDER BY created_at",
                        t))
                .extracting(r -> r.get("action") + " " + r.get("reference"))
                .containsExactly(
                        "PERIOD_CLOSED Period to " + day(-31),
                        "PERIOD_CLOSED Period to " + today,
                        "PERIOD_REOPENED Period to " + today);

        // The reopened period has to be closed again before a later one; it may be closed again.
        command(admin, "/api/admin/periods/close", new AdminCommands.ClosePeriod(today))
                .andExpect(status().isOk());
    }

    @Test
    void periodsCloseInOrderAndOnlyOnceTheyHaveEnded() throws Exception {
        command(admin, "/api/admin/periods/close", new AdminCommands.ClosePeriod(day(1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A period can be closed once its end date has been reached"));
        command(admin, "/api/admin/periods/close", new AdminCommands.ClosePeriod(day(-10)))
                .andExpect(status().isOk());
        command(admin, "/api/admin/periods/close", new AdminCommands.ClosePeriod(day(-20)))
                .andExpect(status().isConflict());
        command(admin, "/api/admin/periods/close", new AdminCommands.ClosePeriod("2026-02-30"))
                .andExpect(status().isBadRequest());
        command(admin, "/api/admin/periods/reopen", new AdminCommands.ReopenPeriod("Correction of a receipt"))
                .andExpect(status().isOk());
        command(admin, "/api/admin/periods/close", new AdminCommands.ClosePeriod(day(-5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Close the reopened period ending " + day(-10) + " first"));
        command(admin, "/api/admin/periods/reopen", new AdminCommands.ReopenPeriod("Nothing to reopen here"))
                .andExpect(status().isConflict());

        command(planner, "/api/admin/periods/close", new AdminCommands.ClosePeriod(day(-1)))
                .andExpect(status().isForbidden());
        query(planner, "/api/admin/periods").andExpect(status().isForbidden());

        var other = createTenant("Other");
        var otherAdmin = createUser(other.id(), "ADMIN");
        command(otherAdmin, "/api/admin/periods/close", new AdminCommands.ClosePeriod(today))
                .andExpect(status().isOk());
        postAdjustment().andExpect(status().isOk()); // our tenant is not affected
    }
}
