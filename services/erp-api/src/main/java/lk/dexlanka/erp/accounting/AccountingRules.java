package lk.dexlanka.erp.accounting;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared general-ledger rules (V24): who acts, account lookups, dates and amounts. */
final class AccountingRules {
    /** The accountant role. ADMIN passes every {@code Actor.allow} check as well. */
    static final String FINANCE = "FINANCE";

    static final List<String> TYPES = List.of("ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE");

    private AccountingRules() {}

    /** The account with {@code code}, or null when the tenant's chart has none. */
    static Map<String, Object> account(Store s, String tenant, String code) {
        var rows = s.list("SELECT * FROM gl_accounts WHERE tenant_id=? AND code=?", tenant, code);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 409 unless {@code code} is an active, postable account of the tenant's chart; {@code where} prefixes the message. */
    static Map<String, Object> postable(Store s, String tenant, String code, String where) {
        var account = account(s, tenant, code);
        require(account != null, where + "account " + code + " is not in the chart of accounts");
        require(
                Boolean.TRUE.equals(account.get("postable")),
                where + "account " + code + " is a header account; choose one of its sub-accounts");
        require(Boolean.TRUE.equals(account.get("active")), where + "account " + code + " is inactive");
        return account;
    }

    /** Debits minus credits of every posted line of {@code code} (its balance, debit positive). */
    static BigDecimal balance(Store s, String tenant, String code) {
        return dec(s.db().queryForObject(
                        "SELECT COALESCE(SUM(debit-credit),0) FROM journal_lines WHERE tenant_id=? AND account=?",
                        BigDecimal.class,
                        tenant,
                        code));
    }

    /** A yyyy-MM-dd date (400 otherwise). */
    static LocalDate date(String value, String what) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a valid yyyy-MM-dd " + what);
        }
    }

    /** An optional yyyy-MM-dd date (null when blank). */
    static LocalDate optionalDate(String value, String what) {
        return value == null || value.isBlank() ? null : date(value.strip(), what);
    }

    /** {@code 1234.50}: an amount for audit details (Rs. is added by the caller). */
    static String amount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
