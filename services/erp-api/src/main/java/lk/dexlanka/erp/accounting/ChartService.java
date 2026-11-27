package lk.dexlanka.erp.accounting;

import static lk.dexlanka.erp.accounting.AccountingRules.FINANCE;
import static lk.dexlanka.erp.accounting.AccountingRules.amount;
import static lk.dexlanka.erp.accounting.AccountingRules.balance;
import static lk.dexlanka.erp.accounting.AccountingRules.blankToNull;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chart-of-accounts maintenance (V24, FINANCE and ADMIN). Every tenant starts with the standard chart (migration and the
 * {@code tenant_chart_of_accounts} trigger); this service adds accounts and edits them.
 *
 * <p>Rules: codes are unique per tenant; a parent is a header account of the same type and the hierarchy has no cycles;
 * system accounts (the posting codes) keep their code, type and postable/active state (their name is editable); a code
 * changes only while nothing was posted to the account or written on a manual journal; an account becomes a header only
 * without postings and stays one while it has sub-accounts; accounts are never deleted, and deactivating needs a zero
 * balance, no active sub-accounts and no open manual journal using it. Saving an account marks it reviewed. The
 * database repeats the structural rules ({@code gl_account_guard}).
 */
@Service
public class ChartService {
    private final Store s;

    ChartService(Store s) {
        this.s = s;
    }

    @Transactional
    public Map<String, Object> create(Actor a, AccountingCommands.Account c, String key) {
        a.allow(FINANCE);
        return s.once(a, key, "gl-account-create", c, () -> {
            String code = c.code();
            require(
                    AccountingRules.account(s, a.tenant(), code) == null,
                    "Account code " + code + " already exists in the chart");
            boolean header = Boolean.TRUE.equals(c.header());
            String parent = blankToNull(c.parentId());
            if (parent != null) checkParent(a, null, parent, c.type());
            String id = Store.id();
            s.db().update(
                            """
                            INSERT INTO gl_accounts(id,tenant_id,code,name,type,contra,parent_id,postable,description,created_by,updated_by)
                            VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                            id,
                            a.tenant(),
                            code,
                            c.name().strip(),
                            c.type(),
                            Boolean.TRUE.equals(c.contra()),
                            parent,
                            !header,
                            description(c),
                            a.id(),
                            a.id());
            s.audit(
                    a,
                    "GL_ACCOUNT_CREATED",
                    code,
                    c.name().strip() + " · " + c.type() + (header ? " header" : "")
                            + (Boolean.TRUE.equals(c.contra()) ? " (contra)" : "")
                            + (parent == null ? "" : " under " + parentCode(a, parent)));
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> update(Actor a, String id, AccountingCommands.Account c, String key) {
        a.allow(FINANCE);
        return s.once(a, key, "gl-account:" + id, c, () -> {
            var account = s.one("SELECT * FROM gl_accounts WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
            String code = str(account, "code");
            boolean system = Boolean.TRUE.equals(account.get("system"));
            boolean header = Boolean.TRUE.equals(c.header());
            boolean wasHeader = !Boolean.TRUE.equals(account.get("postable"));
            boolean contra = Boolean.TRUE.equals(c.contra());
            String parent = blankToNull(c.parentId());
            var changes = new ArrayList<String>();
            if (system) {
                require(
                        c.code().equals(code),
                        "Account " + code + " is used by system postings: its code cannot change");
                require(
                        c.type().equals(account.get("type")) && contra == Boolean.TRUE.equals(account.get("contra")),
                        "Account " + code + " is used by system postings: its type cannot change");
                require(!header, "Account " + code + " is used by system postings and stays postable");
            }
            if (!c.code().equals(code)) {
                require(
                        AccountingRules.account(s, a.tenant(), c.code()) == null,
                        "Account code " + c.code() + " already exists in the chart");
                require(
                        !used(a, code),
                        "Account " + code + " has postings or manual journal lines, so its code cannot change");
                changes.add("code " + code + " → " + c.code());
            }
            boolean hasChildren = !s.list(
                            "SELECT 1 FROM gl_accounts WHERE tenant_id=? AND parent_id=? LIMIT 1", a.tenant(), id)
                    .isEmpty();
            if (!c.type().equals(account.get("type"))) {
                require(!hasChildren, "Account " + code + " has sub-accounts; move them before changing its type");
                changes.add("type " + account.get("type") + " → " + c.type());
            }
            if (contra != Boolean.TRUE.equals(account.get("contra")))
                changes.add(contra ? "now a contra account" : "no longer a contra account");
            if (header && !wasHeader) {
                require(
                        !used(a, code),
                        "Account " + code + " has postings or manual journal lines, so it cannot become a header");
                changes.add("now a header account");
            }
            if (!header && wasHeader) {
                require(!hasChildren, "Account " + code + " has sub-accounts, so it must stay a header account");
                changes.add("now a postable account");
            }
            if (!Objects.equals(parent, account.get("parent_id"))) {
                if (parent != null) checkParent(a, id, parent, c.type());
                changes.add(parent == null ? "moved to the top level" : "moved under " + parentCode(a, parent));
            }
            if (!c.name().strip().equals(account.get("name")))
                changes.add("name " + account.get("name") + " → " + c.name().strip());
            if (!description(c).equals(str(account, "description"))) changes.add("description changed");
            if (Boolean.TRUE.equals(account.get("needs_review"))) changes.add("reviewed");
            s.db().update(
                            """
                            UPDATE gl_accounts SET code=?,name=?,type=?,contra=?,parent_id=?,postable=?,description=?,
                                   needs_review=false,updated_by=?,updated_at=now()
                            WHERE tenant_id=? AND id=?""",
                            c.code(),
                            c.name().strip(),
                            c.type(),
                            contra,
                            parent,
                            !header,
                            description(c),
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(a, "GL_ACCOUNT_UPDATED", c.code(), changes.isEmpty() ? "No changes" : String.join("; ", changes));
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> deactivate(Actor a, String id, String key) {
        a.allow(FINANCE);
        return s.once(a, key, "gl-account-deactivate:" + id, Map.of(), () -> {
            var account = s.one("SELECT * FROM gl_accounts WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
            String code = str(account, "code");
            require(
                    !Boolean.TRUE.equals(account.get("system")),
                    "Account " + code + " is used by system postings and stays active");
            require(Boolean.TRUE.equals(account.get("active")), "Account " + code + " is already inactive");
            var balance = balance(s, a.tenant(), code);
            require(
                    balance.signum() == 0,
                    "Account " + code + " has a balance of Rs. " + amount(balance.abs())
                            + "; bring it to zero before deactivating it");
            require(
                    s.list(
                                    "SELECT 1 FROM gl_accounts WHERE tenant_id=? AND parent_id=? AND active LIMIT 1",
                                    a.tenant(),
                                    id)
                            .isEmpty(),
                    "Deactivate the sub-accounts of " + code + " first");
            var open = s.list("""
                    SELECT DISTINCT j.number FROM manual_journal_lines l
                    JOIN manual_journals j ON j.tenant_id=l.tenant_id AND j.id=l.journal_id
                    WHERE l.tenant_id=? AND l.account=? AND j.status IN ('DRAFT','SUBMITTED')
                    ORDER BY j.number LIMIT 3""", a.tenant(), code);
            require(
                    open.isEmpty(),
                    "Account " + code + " is used on open manual journal "
                            + String.join(
                                    ", ",
                                    open.stream().map(r -> str(r, "number")).toList())
                            + "; change or cancel it first");
            s.db().update(
                            "UPDATE gl_accounts SET active=false,updated_by=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(a, "GL_ACCOUNT_DEACTIVATED", code, str(account, "name"));
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> reactivate(Actor a, String id, String key) {
        a.allow(FINANCE);
        return s.once(a, key, "gl-account-reactivate:" + id, Map.of(), () -> {
            var account = s.one("SELECT * FROM gl_accounts WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
            String code = str(account, "code");
            require(!Boolean.TRUE.equals(account.get("active")), "Account " + code + " is already active");
            if (account.get("parent_id") != null) {
                var parent = s.one(
                        "SELECT code,active FROM gl_accounts WHERE tenant_id=? AND id=?",
                        a.tenant(),
                        account.get("parent_id"));
                require(
                        Boolean.TRUE.equals(parent.get("active")),
                        "Reactivate its parent account " + parent.get("code") + " first");
            }
            s.db().update(
                            "UPDATE gl_accounts SET active=true,updated_by=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            a.id(),
                            a.tenant(),
                            id);
            s.audit(a, "GL_ACCOUNT_REACTIVATED", code, str(account, "name"));
            return result(a, id);
        });
    }

    /** A parent must be a header account of the same type that is not the account itself or one of its sub-accounts. */
    private void checkParent(Actor a, String id, String parentId, String type) {
        var parent = s.one("SELECT * FROM gl_accounts WHERE tenant_id=? AND id=?", a.tenant(), parentId);
        require(
                !Boolean.TRUE.equals(parent.get("postable")),
                "The parent account " + parent.get("code") + " must be a header account");
        require(
                type.equals(parent.get("type")),
                "The parent account " + parent.get("code") + " is " + parent.get("type") + "; choose a " + type
                        + " header account");
        require(Boolean.TRUE.equals(parent.get("active")), "The parent account " + parent.get("code") + " is inactive");
        if (id != null)
            require(
                    s.list("""
                                    WITH RECURSIVE up(id,parent_id) AS (
                                        SELECT id,parent_id FROM gl_accounts WHERE tenant_id=? AND id=?
                                        UNION
                                        SELECT g.id,g.parent_id FROM gl_accounts g JOIN up ON g.tenant_id=? AND g.id=up.parent_id)
                                    SELECT 1 FROM up WHERE id=?""", a.tenant(), parentId, a.tenant(), id).isEmpty(),
                    "An account cannot be placed under itself or one of its own sub-accounts");
    }

    /** True when journal lines (posted) or manual journal lines (any status) name {@code code}. */
    private boolean used(Actor a, String code) {
        return !s.list("SELECT 1 FROM journal_lines WHERE tenant_id=? AND account=? LIMIT 1", a.tenant(), code)
                        .isEmpty()
                || !s.list(
                                "SELECT 1 FROM manual_journal_lines WHERE tenant_id=? AND account=? LIMIT 1",
                                a.tenant(),
                                code)
                        .isEmpty();
    }

    private String parentCode(Actor a, String parentId) {
        return str(s.one("SELECT code FROM gl_accounts WHERE tenant_id=? AND id=?", a.tenant(), parentId), "code");
    }

    private static String description(AccountingCommands.Account c) {
        return c.description() == null ? "" : c.description().strip();
    }

    private Map<String, Object> result(Actor a, String id) {
        var row = s.one(
                "SELECT id,code,name,type,contra,normal_balance,parent_id,postable,active,system,needs_review FROM gl_accounts WHERE tenant_id=? AND id=?",
                a.tenant(),
                id);
        return new LinkedHashMap<>(row);
    }
}
