package lk.dexlanka.erp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V24 chart of accounts: the standard chart of a new tenant (trigger) and of tenants that existed before V24
 * (migration, including the review accounts created from unknown posted codes), and the maintenance rules.
 */
class ChartOfAccountsTest extends AccountingTestSupport {
    /** Every code the Java posting code uses, with its type (and the V24 reserved codes). */
    static final Map<String, String> SYSTEM = Map.ofEntries(
            Map.entry("RAW_INVENTORY", "ASSET"),
            Map.entry("WIP", "ASSET"),
            Map.entry("REWORK_WIP", "ASSET"),
            Map.entry("FG_INVENTORY", "ASSET"),
            Map.entry("ACCOUNTS_RECEIVABLE", "ASSET"),
            Map.entry("ACCOUNTS_PAYABLE", "LIABILITY"),
            Map.entry("GRNI", "LIABILITY"),
            Map.entry("CONVERSION_CLEARING", "LIABILITY"),
            Map.entry("MAINTENANCE_LABOUR_CLEARING", "LIABILITY"),
            Map.entry("OPENING_EQUITY", "EQUITY"),
            Map.entry("SALES_REVENUE", "INCOME"),
            Map.entry("COST_OF_GOODS_SOLD", "EXPENSE"),
            Map.entry("SCRAP_EXPENSE", "EXPENSE"),
            Map.entry("QUALITY_LOSS", "EXPENSE"),
            Map.entry("INVENTORY_ADJUSTMENT", "EXPENSE"),
            Map.entry("PURCHASE_PRICE_VARIANCE", "EXPENSE"),
            Map.entry("MAINTENANCE_EXPENSE", "EXPENSE"),
            Map.entry("OUTPUT_TAX", "LIABILITY"),
            Map.entry("INPUT_TAX", "ASSET"),
            Map.entry("BANK", "ASSET"),
            Map.entry("CASH", "ASSET"),
            Map.entry("RETAINED_EARNINGS", "EQUITY"),
            Map.entry("ROUNDING", "EXPENSE"),
            Map.entry("SALES_RETURNS", "INCOME"),
            Map.entry("CUSTOMER_ADVANCES", "LIABILITY"),
            Map.entry("SUPPLIER_ADVANCES", "ASSET"));

    @Test
    void aNewTenantStartsWithTheStandardChart() {
        for (var e : SYSTEM.entrySet()) {
            var a = account(e.getKey());
            assertThat(a.get("type")).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(a).as(e.getKey()).containsEntry("system", true).containsEntry("postable", true);
            assertThat(a).as(e.getKey()).containsEntry("active", true).containsEntry("needs_review", false);
        }
        assertThat(count("SELECT COUNT(*) FROM gl_accounts WHERE tenant_id=? AND system", t))
                .isEqualTo(SYSTEM.size());
        // Normal balance follows the type; SALES_RETURNS is a contra income account (debit).
        assertThat(account("GRNI").get("normal_balance")).isEqualTo("CREDIT");
        assertThat(account("PURCHASE_PRICE_VARIANCE").get("normal_balance")).isEqualTo("DEBIT");
        assertThat(account("OPENING_EQUITY").get("normal_balance")).isEqualTo("CREDIT");
        assertThat(account("SALES_RETURNS")).containsEntry("contra", true).containsEntry("normal_balance", "DEBIT");
        // Hierarchy: RAW_INVENTORY → INVENTORIES → ASSETS (header accounts take no postings).
        var inventories = account("INVENTORIES");
        assertThat(account("RAW_INVENTORY").get("parent_id")).isEqualTo(inventories.get("id"));
        assertThat(inventories.get("parent_id")).isEqualTo(account("ASSETS").get("id"));
        assertThat(inventories).containsEntry("postable", false).containsEntry("system", false);
        assertThat(count("SELECT COUNT(*) FROM gl_accounts WHERE tenant_id=? AND parent_id IS NULL", t))
                .isEqualTo(5);
        // Another tenant has its own chart.
        var other = createTenant("Other ledger");
        assertThat(count("SELECT COUNT(*) FROM gl_accounts WHERE tenant_id=?", other.id()))
                .isEqualTo(count("SELECT COUNT(*) FROM gl_accounts WHERE tenant_id=?", t));
    }

    @Test
    void theMigrationGivesExistingTenantsTheChartAndFlagsUnknownCodes() throws Exception {
        String schema = "ledger_v24_" + id().replace("-", "");
        var folder = Files.createTempDirectory("dexlanka-v24-migration-");
        try {
            Files.writeString(folder.resolve("V17_1__legacy_journal.sql"), """
                    INSERT INTO tenants(id,name) VALUES('t1','Legacy'),('t2','Empty');
                    INSERT INTO journal_entries(id,tenant_id,reference,description,actor,created_at) VALUES
                        ('j1','t1','OPENING','Opening stock','Legacy','2026-03-31 20:00:00+00'),
                        ('j2','t1','OLD-1','Legacy cost','Legacy','2026-04-02 10:00:00+05:30');
                    INSERT INTO journal_lines(id,tenant_id,entry_id,account,debit,credit) VALUES
                        ('l1','t1','j1','RAW_INVENTORY',500,0),('l2','t1','j1','OPENING_EQUITY',0,500),
                        ('l3','t1','j2','LEGACY_COST',75,0),('l4','t1','j2','LEGACY_CLEARING',0,75);
                    """);
            Flyway.configure()
                    .dataSource(s.db().getDataSource())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration", "filesystem:" + folder)
                    .target("24")
                    .load()
                    .migrate();
            String q = schema + ".";
            for (String tenantId : List.of("t1", "t2"))
                assertThat(count("SELECT COUNT(*) FROM " + q + "gl_accounts WHERE tenant_id=? AND system", tenantId))
                        .isEqualTo(SYSTEM.size());
            // Unknown codes become reviewable accounts, typed conservatively by their balance.
            var cost = row("SELECT * FROM " + q + "gl_accounts WHERE tenant_id='t1' AND code='LEGACY_COST'");
            assertThat(cost)
                    .containsEntry("type", "EXPENSE")
                    .containsEntry("needs_review", true)
                    .containsEntry("system", false)
                    .containsEntry("postable", true);
            var clearing = row("SELECT * FROM " + q + "gl_accounts WHERE tenant_id='t1' AND code='LEGACY_CLEARING'");
            assertThat(clearing).containsEntry("type", "LIABILITY").containsEntry("needs_review", true);
            assertThat(count("SELECT COUNT(*) FROM " + q + "gl_accounts WHERE tenant_id='t2' AND needs_review"))
                    .isZero();
            assertThat(count("SELECT COUNT(*) FROM " + q + "gl_accounts WHERE tenant_id='t1' AND needs_review"))
                    .isEqualTo(2);
            // Posting dates are the Asia/Colombo calendar day of created_at; every old entry is a SYSTEM entry.
            assertThat(row("SELECT posting_date::text AS d,source FROM " + q + "journal_entries WHERE id='j1'"))
                    .containsEntry("d", "2026-04-01")
                    .containsEntry("source", "SYSTEM");
            assertThat(row("SELECT posting_date::text AS d FROM " + q + "journal_entries WHERE id='j2'")
                            .get("d"))
                    .isEqualTo("2026-04-02");
            // Posted history stays immutable after the backfill.
            assertThatThrownBy(() -> update("UPDATE " + q + "journal_entries SET posting_date='2026-01-01'"))
                    .hasMessageContaining("immutable");
        } finally {
            update("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            try (var files = Files.list(folder)) {
                for (var file : files.toList()) Files.delete(file);
            }
            Files.delete(folder);
        }
    }

    @Test
    void financeMaintainsTheChartWithinTheRules() throws Exception {
        String expenses = accountId(t, "EXPENSES");
        var created = json(command(
                        finance,
                        "/api/accounting/accounts",
                        new AccountingCommands.Account(
                                "UTILITIES", "Utilities", "EXPENSE", false, true, expenses, "Header"))
                .andExpect(status().isOk()));
        String utilities = str(created, "id");
        assertThat(created).containsEntry("postable", false).containsEntry("normal_balance", "DEBIT");
        var power = json(command(
                        finance,
                        "/api/accounting/accounts",
                        new AccountingCommands.Account(
                                "POWER", "Electricity", "EXPENSE", false, false, utilities, null))
                .andExpect(status().isOk()));
        String powerId = str(power, "id");
        assertThat(row("SELECT action,detail FROM audit_events WHERE tenant_id=? AND reference='POWER'", t))
                .containsEntry("action", "GL_ACCOUNT_CREATED");

        // Duplicate code, parent rules (header, same type) and cycles.
        command(
                        finance,
                        "/api/accounting/accounts",
                        new AccountingCommands.Account("POWER", "Again", "EXPENSE", false, false, null, null))
                .andExpect(status().isConflict());
        command(
                        finance,
                        "/api/accounting/accounts",
                        new AccountingCommands.Account("WATER", "Water", "EXPENSE", false, false, powerId, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The parent account POWER must be a header account"));
        command(
                        finance,
                        "/api/accounting/accounts",
                        new AccountingCommands.Account("WATER", "Water", "ASSET", false, false, utilities, null))
                .andExpect(status().isConflict());
        var sub = json(command(
                        finance,
                        "/api/accounting/accounts",
                        new AccountingCommands.Account("PLANT_UTILS", "Plant", "EXPENSE", false, true, utilities, null))
                .andExpect(status().isOk()));
        command(
                        finance,
                        "/api/accounting/accounts/" + utilities,
                        new AccountingCommands.Account(
                                "UTILITIES", "Utilities", "EXPENSE", false, true, str(sub, "id"), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("An account cannot be placed under itself or one of its own sub-accounts"));
        // The database guard refuses a cycle as well.
        assertThatThrownBy(() -> update(
                        "UPDATE gl_accounts SET parent_id=? WHERE tenant_id=? AND id=?", str(sub, "id"), t, utilities))
                .hasMessageContaining("own sub-accounts");

        // System accounts: the name is editable, code, type and postable state are not (API and database).
        String grni = accountId(t, "GRNI");
        command(
                        finance,
                        "/api/accounting/accounts/" + grni,
                        new AccountingCommands.Account(
                                "GRNI",
                                "Goods received, not yet invoiced",
                                "LIABILITY",
                                false,
                                false,
                                accountId(t, "LIABILITIES"),
                                null))
                .andExpect(status().isOk());
        assertThat(account("GRNI").get("name")).isEqualTo("Goods received, not yet invoiced");
        command(
                        finance,
                        "/api/accounting/accounts/" + grni,
                        new AccountingCommands.Account("GRNI_X", "GRNI", "LIABILITY", false, false, null, null))
                .andExpect(status().isConflict());
        command(
                        finance,
                        "/api/accounting/accounts/" + grni,
                        new AccountingCommands.Account("GRNI", "GRNI", "EXPENSE", false, false, null, null))
                .andExpect(status().isConflict());
        command(finance, "/api/accounting/accounts/" + grni + "/deactivate", null)
                .andExpect(status().isConflict());
        assertThatThrownBy(() -> update("UPDATE gl_accounts SET type='EXPENSE' WHERE tenant_id=? AND code='GRNI'", t))
                .hasMessageContaining("system postings");
        assertThatThrownBy(() -> update("DELETE FROM gl_accounts WHERE tenant_id=? AND code='POWER'", t))
                .hasMessageContaining("cannot be deleted");

        // A code changes only while unused; once posted, it is fixed and the balance blocks deactivation.
        command(
                        finance,
                        "/api/accounting/accounts/" + powerId,
                        new AccountingCommands.Account(
                                "ELECTRICITY", "Electricity", "EXPENSE", false, false, utilities, null))
                .andExpect(status().isOk());
        posted(today, dr("ELECTRICITY", "1200.00"), cr("BANK", "1200.00"));
        command(
                        finance,
                        "/api/accounting/accounts/" + powerId,
                        new AccountingCommands.Account(
                                "POWER", "Electricity", "EXPENSE", false, false, utilities, null))
                .andExpect(status().isConflict());
        command(
                        finance,
                        "/api/accounting/accounts/" + powerId,
                        new AccountingCommands.Account(
                                "ELECTRICITY", "Electricity", "EXPENSE", false, true, utilities, null))
                .andExpect(status().isConflict());
        command(finance, "/api/accounting/accounts/" + powerId + "/deactivate", null)
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "Account ELECTRICITY has a balance of Rs. 1200.00; bring it to zero before deactivating it"));
        posted(today, dr("BANK", "1200.00"), cr("ELECTRICITY", "1200.00"));
        command(finance, "/api/accounting/accounts/" + powerId + "/deactivate", null)
                .andExpect(status().isOk());
        assertThat(account("ELECTRICITY").get("active")).isEqualTo(false);
        // An inactive account takes no postings: manual journals refuse it, so does the database.
        command(finance, "/api/accounting/journals", body(today, "x", dr("ELECTRICITY", "1"), cr("BANK", "1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Line 1: account ELECTRICITY is inactive"));
        assertThatThrownBy(() -> s.journal(admin, "X", "Inactive", "ELECTRICITY", "BANK", bd("1")))
                .hasMessageContaining("inactive");
        command(finance, "/api/accounting/accounts/" + powerId + "/reactivate", null)
                .andExpect(status().isOk());
        assertThat(account("ELECTRICITY").get("active")).isEqualTo(true);

        // Saving a flagged account marks it reviewed.
        update("UPDATE gl_accounts SET needs_review=true WHERE tenant_id=? AND id=?", t, powerId);
        command(
                        finance,
                        "/api/accounting/accounts/" + powerId,
                        new AccountingCommands.Account(
                                "ELECTRICITY", "Electricity (reviewed)", "EXPENSE", false, false, utilities, null))
                .andExpect(status().isOk());
        assertThat(account("ELECTRICITY").get("needs_review")).isEqualTo(false);
    }

    @Test
    void chartBalancesRollUpIntoHeaderAccounts() throws Exception {
        openingResin("10", "50"); // Dr RAW_INVENTORY 500 / Cr OPENING_EQUITY 500
        posted(today, dr("WIP", "120.00"), cr("RAW_INVENTORY", "120.00"));
        var chart = get(finance, "/api/accounting/accounts");
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) chart.get("items");
        var byCode = new java.util.HashMap<String, Map<String, Object>>();
        for (var i : items) byCode.put(str(i, "code"), i);
        assertThat(dec(byCode.get("RAW_INVENTORY").get("balance"))).isEqualByComparingTo("380");
        assertThat(dec(byCode.get("WIP").get("balance"))).isEqualByComparingTo("120");
        assertThat(dec(byCode.get("INVENTORIES").get("total_balance"))).isEqualByComparingTo("500");
        assertThat(dec(byCode.get("ASSETS").get("total_balance"))).isEqualByComparingTo("500");
        assertThat(dec(byCode.get("EQUITY").get("total_balance"))).isEqualByComparingTo("-500");
        assertThat(byCode.get("RAW_INVENTORY").get("depth")).isEqualTo(2);
        // Tree order: the header comes before its sub-accounts; assets before liabilities.
        var codes = items.stream().map(i -> str(i, "code")).toList();
        assertThat(codes.indexOf("ASSETS")).isLessThan(codes.indexOf("INVENTORIES"));
        assertThat(codes.indexOf("INVENTORIES")).isLessThan(codes.indexOf("RAW_INVENTORY"));
        assertThat(codes.indexOf("FG_INVENTORY")).isLessThan(codes.indexOf("LIABILITIES"));
        // As of yesterday nothing was posted.
        var before = get(finance, "/api/accounting/accounts?asOf=" + today.minusDays(1));
        @SuppressWarnings("unchecked")
        var old = (List<Map<String, Object>>) before.get("items");
        assertThat(old.stream().allMatch(i -> dec(i.get("total_balance")).signum() == 0))
                .isTrue();
    }

    @Test
    void onlyFinanceAndAdminSeeOrChangeTheChartAndOnlyTheirOwn() throws Exception {
        var account = new AccountingCommands.Account("MISC", "Misc", "EXPENSE", false, false, null, null);
        command(planner, "/api/accounting/accounts", account).andExpect(status().isForbidden());
        command(viewer, "/api/accounting/accounts", account).andExpect(status().isForbidden());
        query(planner, "/api/accounting/accounts").andExpect(status().isForbidden());
        query(viewer, "/api/accounting/trial-balance").andExpect(status().isForbidden());
        command(admin, "/api/accounting/accounts", account).andExpect(status().isOk());
        query(admin, "/api/accounting/accounts").andExpect(status().isOk());

        var other = createTenant("Other ledger");
        var stranger = createUser(other.id(), "FINANCE");
        String ours = accountId(t, "MISC");
        command(stranger, "/api/accounting/accounts/" + ours, account).andExpect(status().isNotFound());
        command(stranger, "/api/accounting/accounts/" + ours + "/deactivate", null)
                .andExpect(status().isNotFound());
        query(stranger, "/api/accounting/accounts/" + ours + "/ledger").andExpect(status().isNotFound());
        // A parent from another tenant is not found either.
        command(
                        stranger,
                        "/api/accounting/accounts",
                        new AccountingCommands.Account(
                                "MISC2", "Misc", "EXPENSE", false, false, accountId(t, "EXPENSES"), null))
                .andExpect(status().isNotFound());
        // Validation: codes are upper case.
        command(
                        finance,
                        "/api/accounting/accounts",
                        new AccountingCommands.Account("misc", "Misc", "EXPENSE", false, false, null, null))
                .andExpect(status().isBadRequest());
    }
}
