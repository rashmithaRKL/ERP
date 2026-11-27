package lk.dexlanka.erp.accounting;

import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lk.dexlanka.erp.demo.DemoPersonas;
import lk.dexlanka.erp.demo.DemoSeed;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V24 demo data (fictional): the accountant persona Sanduni Rajapaksa ({@code demo.finance@dexlanka.test}), four
 * accounts added to the standard chart (electricity and water, factory insurance, accrued and prepaid expenses) and
 * four manual journals through the real commands: an accrued electricity expense posted after the administrator's
 * approval, a generator-diesel accrual that was posted and then reversed as a duplicate, a prepaid insurance premium
 * awaiting approval and an insurance-expense draft. Every journal is dated today. Idempotent: skipped once any {@code
 * demo-v24-} key exists; the persona and the data commit in their own transactions.
 */
@Component
@Order(DemoSeed.ORDER + 240)
class AccountingDemoSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AccountingDemoSeed.class);
    private static final Actor FINANCE = DemoPersonas.FINANCE;
    private static final Actor ADMIN = DemoPersonas.ADMIN;

    private final Store s;
    private final PasswordEncoder passwords;
    private final ChartService chart;
    private final ManualJournalService journals;
    private final ReversalService reversals;
    private final TransactionTemplate tx;
    private final boolean demo;

    AccountingDemoSeed(
            Store s,
            PasswordEncoder passwords,
            ChartService chart,
            ManualJournalService journals,
            ReversalService reversals,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.passwords = passwords;
        this.chart = chart;
        this.journals = journals;
        this.reversals = reversals;
        this.tx = new TransactionTemplate(transactions);
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo
                || s.list("SELECT 1 FROM tenants WHERE id=?", DemoPersonas.TENANT)
                        .isEmpty()) return;
        // The persona commits on its own, so the role switch works even when the journals below cannot be added.
        try {
            tx.executeWithoutResult(status -> persona());
        } catch (RuntimeException e) {
            log.warn("Finance demo persona (V24) was not added: {}", e.getMessage());
        }
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.warn("General ledger demo data (V24) was not added: {}", e.getMessage());
        }
    }

    /** The FINANCE persona (also added to a demo database created before V24). */
    void persona() {
        Actor f = FINANCE;
        if (!s.list("SELECT 1 FROM users WHERE id=?", f.id()).isEmpty()) return;
        s.db().update(
                        "INSERT INTO users(id,tenant_id,email,name,password_hash,role) VALUES(?,?,?,?,?,?)",
                        f.id(),
                        f.tenant(),
                        f.email(),
                        f.name(),
                        passwords.encode(Store.id() + Store.id()),
                        f.role());
    }

    /** Idempotent: does nothing once a {@code demo-v24-} command has run. */
    void seed() {
        if (!s.list(
                        "SELECT 1 FROM idempotency_keys WHERE tenant_id=? AND request_key LIKE 'demo-v24-%' LIMIT 1",
                        DemoPersonas.TENANT)
                .isEmpty()) return;
        var today = Store.today();
        String month = today.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));

        account("utilities", "UTILITIES_EXPENSE", "Electricity and water", "EXPENSE", "EXPENSES");
        account("insurance", "INSURANCE_EXPENSE", "Factory insurance", "EXPENSE", "EXPENSES");
        account("accrued", "ACCRUED_EXPENSES", "Accrued expenses", "LIABILITY", "LIABILITIES");
        account("prepaid", "PREPAID_EXPENSES", "Prepaid expenses", "ASSET", "ASSETS");

        // Posted: the month's electricity, accrued until the invoice arrives; approved by the administrator.
        String electricity = journal(
                "electricity",
                "Accrued electricity for " + month + " (invoice not yet received)",
                line("UTILITIES_EXPENSE", "182450.00", null, "Meter readings for the moulding and blowing halls"),
                line("ACCRUED_EXPENSES", null, "182450.00", "Electricity accrual"));
        journals.submit(FINANCE, electricity, "demo-v24-electricity-submit");
        journals.approve(ADMIN, electricity, "demo-v24-electricity-approve");

        // Posted and then reversed: the diesel was already part of the electricity accrual.
        String diesel = journal(
                "diesel",
                "Accrued generator diesel for " + month,
                line("UTILITIES_EXPENSE", "24800.00", null, "Standby generator fuel"),
                line("ACCRUED_EXPENSES", null, "24800.00", "Diesel accrual"));
        journals.submit(FINANCE, diesel, "demo-v24-diesel-submit");
        journals.approve(ADMIN, diesel, "demo-v24-diesel-approve");
        String entry = str(
                s.one("SELECT entry_id FROM manual_journals WHERE tenant_id=? AND id=?", FINANCE.tenant(), diesel),
                "entry_id");
        reversals.reverse(
                FINANCE,
                entry,
                new AccountingCommands.Reverse(
                        null, "Duplicate: the generator diesel is already included in the electricity accrual"),
                "demo-v24-diesel-reverse");

        // Awaiting approval: next month's insurance premium paid in advance from the bank account.
        String prepaid = journal(
                "prepaid",
                "Factory insurance premium for next month paid in advance",
                line("PREPAID_EXPENSES", "96000.00", null, "Fire and machinery cover"),
                line("BANK", null, "96000.00", "Premium transfer"));
        journals.submit(FINANCE, prepaid, "demo-v24-prepaid-submit");

        // Draft: this month's share of the insurance, still being prepared.
        journal(
                "insurance",
                "Factory insurance expense for " + month,
                line("INSURANCE_EXPENSE", "8000.00", null, "One month of cover"),
                line("PREPAID_EXPENSES", null, "8000.00", null));
    }

    private void account(String key, String code, String name, String type, String parentCode) {
        String parent = str(
                s.one("SELECT id FROM gl_accounts WHERE tenant_id=? AND code=?", DemoPersonas.TENANT, parentCode),
                "id");
        chart.create(
                FINANCE,
                new AccountingCommands.Account(code, name, type, false, false, parent, ""),
                "demo-v24-account-" + key);
    }

    private String journal(String key, String narration, AccountingCommands.Line... lines) {
        return str(
                journals.create(
                        FINANCE,
                        new AccountingCommands.Journal(Store.today().toString(), narration, List.of(lines)),
                        "demo-v24-" + key),
                "id");
    }

    private static AccountingCommands.Line line(String account, String debit, String credit, String memo) {
        return new AccountingCommands.Line(
                account,
                debit == null ? null : new BigDecimal(debit),
                credit == null ? null : new BigDecimal(credit),
                memo);
    }
}
