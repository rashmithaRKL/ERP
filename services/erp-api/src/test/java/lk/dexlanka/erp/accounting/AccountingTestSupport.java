package lk.dexlanka.erp.accounting;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared fixture for the V24 general-ledger tests: a fresh tenant (with its standard chart from the tenant trigger), an
 * ADMIN, two FINANCE users, a PLANNER and a VIEWER, plus helpers for manual journals.
 */
abstract class AccountingTestSupport extends IntegrationTestBase {
    TestTenant tenant;
    String t;
    Actor admin, finance, finance2, planner, viewer;
    LocalDate today;

    @BeforeEach
    void accountingFixture() {
        tenant = createTenant("Ledger fixture");
        t = tenant.id();
        admin = createUser(t, "ADMIN");
        finance = createUser(t, "FINANCE");
        finance2 = createUser(t, "FINANCE");
        planner = createUser(t, "PLANNER");
        viewer = createUser(t, "VIEWER");
        today = Store.today();
    }

    static AccountingCommands.Line dr(String account, String amount) {
        return new AccountingCommands.Line(account, new BigDecimal(amount), null, null);
    }

    static AccountingCommands.Line cr(String account, String amount) {
        return new AccountingCommands.Line(account, null, new BigDecimal(amount), null);
    }

    static AccountingCommands.Journal body(LocalDate date, String narration, AccountingCommands.Line... lines) {
        return new AccountingCommands.Journal(date.toString(), narration, List.of(lines));
    }

    /** Creates a draft through the API and returns its id. */
    String draft(Actor who, LocalDate date, AccountingCommands.Line... lines) throws Exception {
        return str(
                json(command(who, "/api/accounting/journals", body(date, "Test journal", lines))
                        .andExpect(status().isOk())),
                "id");
    }

    /** A draft by {@link #finance}, submitted by {@link #finance} and approved by {@link #finance2}: posted. */
    String posted(LocalDate date, AccountingCommands.Line... lines) throws Exception {
        String id = draft(finance, date, lines);
        command(finance, "/api/accounting/journals/" + id + "/submit", null).andExpect(status().isOk());
        command(finance2, "/api/accounting/journals/" + id + "/approve", null).andExpect(status().isOk());
        return id;
    }

    String entryOf(String journal) {
        return str(row("SELECT entry_id FROM manual_journals WHERE tenant_id=? AND id=?", t, journal), "entry_id");
    }

    String accountId(String tenantId, String code) {
        return str(row("SELECT id FROM gl_accounts WHERE tenant_id=? AND code=?", tenantId, code), "id");
    }

    Map<String, Object> account(String code) {
        return row("SELECT * FROM gl_accounts WHERE tenant_id=? AND code=?", t, code);
    }

    /** Debits minus credits of {@code code} over every posted line of the fixture tenant. */
    BigDecimal balance(String code) {
        return decimal(
                "SELECT COALESCE(SUM(debit-credit),0) FROM journal_lines WHERE tenant_id=? AND account=?", t, code);
    }

    Map<String, Object> get(Actor who, String path) throws Exception {
        return json(query(who, path).andExpect(status().isOk()));
    }

    /** A finished-good / raw item with a released opening lot (Dr RAW_INVENTORY / Cr OPENING_EQUITY). */
    String openingResin(String quantity, String unitCost) {
        String item = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,?,'Resin','RAW_MATERIAL','kg',10,0)",
                item,
                t,
                "RM-" + item.substring(0, 6));
        return openingStock(admin, item, tenant.rawWarehouse(), quantity, unitCost);
    }
}
