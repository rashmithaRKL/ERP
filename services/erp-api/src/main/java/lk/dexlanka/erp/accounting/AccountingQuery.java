package lk.dexlanka.erp.accounting;

import static lk.dexlanka.erp.accounting.AccountingRules.FINANCE;
import static lk.dexlanka.erp.accounting.AccountingRules.TYPES;
import static lk.dexlanka.erp.accounting.AccountingRules.blankToNull;
import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.admin.ApprovalPolicyService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * General-ledger read model (V24, FINANCE and ADMIN): the chart of accounts with balances as of a date, an account's
 * ledger with opening and running balances (keyset-paged), the trial balance as of a date or for a period, manual
 * journals (keyset-paged list, detail) and posted entries. Balances are debits minus credits (debit positive); a client
 * shows them on the account's normal side. Dates are posting dates (Asia/Colombo calendar days), inclusive.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AccountingQuery {
    static final List<String> STATUSES = List.of("DRAFT", "SUBMITTED", "POSTED", "REVERSED", "CANCELLED");

    private final Store s;
    private final ApprovalPolicyService policies;

    AccountingQuery(Store s, ApprovalPolicyService policies) {
        this.s = s;
        this.policies = policies;
    }

    // ------------------------------------------------------------------ chart of accounts

    /**
     * Every account in tree order (type, then code; sub-accounts after their parent with {@code depth}), with its own
     * debits, credits and balance up to {@code asOf} (default today) and {@code total_*} including its sub-accounts.
     * {@code {asOf, items, totals:{debit, credit}, counts:{accounts, needs_review, inactive}}}.
     */
    public Map<String, Object> accounts(Actor a, String asOf) {
        a.allow(FINANCE);
        LocalDate date = date(asOf, Store.today(), "as-of date");
        var rows = s.list("""
                SELECT g.id,g.code,g.name,g.type,g.contra,g.normal_balance,g.parent_id,g.postable,g.active,g.system,
                       g.needs_review,g.description,g.updated_at,
                       COALESCE(t.debit,0) AS debit,COALESCE(t.credit,0) AS credit,COALESCE(t.lines,0) AS lines,
                       EXISTS(SELECT 1 FROM journal_lines x WHERE x.tenant_id=g.tenant_id AND x.account=g.code) AS posted,
                       EXISTS(SELECT 1 FROM manual_journal_lines m WHERE m.tenant_id=g.tenant_id AND m.account=g.code) AS on_journals
                FROM gl_accounts g
                LEFT JOIN (SELECT l.account,SUM(l.debit) AS debit,SUM(l.credit) AS credit,COUNT(*) AS lines
                           FROM journal_lines l JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id
                           WHERE l.tenant_id=? AND e.posting_date<=?
                           GROUP BY l.account) t ON t.account=g.code
                WHERE g.tenant_id=?
                ORDER BY g.code
                LIMIT 5000""", a.tenant(), date, a.tenant());
        var items = tree(rows);
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        long review = 0;
        long inactive = 0;
        for (var r : items) {
            debit = debit.add(dec(r.get("debit")));
            credit = credit.add(dec(r.get("credit")));
            if (Boolean.TRUE.equals(r.get("needs_review"))) review++;
            if (!Boolean.TRUE.equals(r.get("active"))) inactive++;
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("asOf", date.toString());
        out.put("items", items);
        out.put("totals", Map.of("debit", debit, "credit", credit));
        out.put("counts", Map.of("accounts", items.size(), "needs_review", review, "inactive", inactive));
        return out;
    }

    /** Depth-first order with depth, child counts and roll-up totals (sub-accounts summed into their parents). */
    private static List<Map<String, Object>> tree(List<Map<String, Object>> rows) {
        var byParent = new HashMap<String, List<Map<String, Object>>>();
        var roots = new ArrayList<Map<String, Object>>();
        for (var r : rows) {
            var parent = r.get("parent_id");
            if (parent == null) roots.add(r);
            else
                byParent.computeIfAbsent(parent.toString(), k -> new ArrayList<>())
                        .add(r);
        }
        Comparator<Map<String, Object>> order = Comparator.comparingInt(
                        (Map<String, Object> r) -> TYPES.indexOf(str(r, "type")))
                .thenComparing(r -> Boolean.TRUE.equals(r.get("postable")) ? 0 : 1)
                .thenComparing(r -> str(r, "code"));
        roots.sort(order);
        var out = new ArrayList<Map<String, Object>>();
        for (var root : roots) walk(root, 0, byParent, order, out);
        return out;
    }

    private static BigDecimal[] walk(
            Map<String, Object> row,
            int depth,
            Map<String, List<Map<String, Object>>> byParent,
            Comparator<Map<String, Object>> order,
            List<Map<String, Object>> out) {
        var item = new LinkedHashMap<String, Object>(row);
        BigDecimal debit = dec(row.get("debit"));
        BigDecimal credit = dec(row.get("credit"));
        item.put("depth", depth);
        item.put("balance", debit.subtract(credit));
        out.add(item);
        var children = new ArrayList<>(byParent.getOrDefault(str(row, "id"), List.of()));
        children.sort(order);
        item.put("children", children.size());
        BigDecimal totalDebit = debit;
        BigDecimal totalCredit = credit;
        for (var child : children) {
            var sums = walk(child, depth + 1, byParent, order, out);
            totalDebit = totalDebit.add(sums[0]);
            totalCredit = totalCredit.add(sums[1]);
        }
        item.put("total_debit", totalDebit);
        item.put("total_credit", totalCredit);
        item.put("total_balance", totalDebit.subtract(totalCredit));
        return new BigDecimal[] {totalDebit, totalCredit};
    }

    // ------------------------------------------------------------------ account ledger

    /**
     * Posted lines of an account (a header account: of all its sub-accounts) with posting dates {@code from}..{@code
     * to}, oldest first, keyset-paged (cursor = the last line id). {@code opening} is the balance before {@code from},
     * every row carries the running {@code balance} after it, {@code closing} is the balance at the end of the range.
     * {@code {account, from, to, opening, totals:{debit, credit}, closing, items, next}}.
     */
    public Map<String, Object> ledger(Actor a, String id, String from, String to, int limit, String before) {
        a.allow(FINANCE);
        limit(limit);
        var account = s.one(
                "SELECT id,code,name,type,contra,normal_balance,postable,active,system,needs_review FROM gl_accounts WHERE tenant_id=? AND id=?",
                a.tenant(),
                id);
        LocalDate f = AccountingRules.optionalDate(from, "start date");
        LocalDate t = AccountingRules.optionalDate(to, "end date");
        require(f == null || t == null || !t.isBefore(f), "The end date must be on or after the start date");
        var codes = s.list("""
                        WITH RECURSIVE tree(id,code) AS (
                            SELECT id,code FROM gl_accounts WHERE tenant_id=? AND id=?
                            UNION
                            SELECT g.id,g.code FROM gl_accounts g JOIN tree ON g.tenant_id=? AND g.parent_id=tree.id)
                        SELECT code FROM tree""", a.tenant(), id, a.tenant()).stream()
                .map(r -> str(r, "code"))
                .toList();
        String list = s.encode(codes); // a JSON array: imported codes may contain any character
        BigDecimal opening = f == null ? BigDecimal.ZERO : sum("""
                        SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l
                        JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id
                        WHERE l.tenant_id=? AND l.account IN (SELECT jsonb_array_elements_text(?::jsonb)) AND e.posting_date<?""", a.tenant(), list, f);
        String range = """
                 FROM journal_lines l
                JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id
                WHERE l.tenant_id=? AND l.account IN (SELECT jsonb_array_elements_text(?::jsonb))
                  AND (?::date IS NULL OR e.posting_date>=?::date)
                  AND (?::date IS NULL OR e.posting_date<=?::date)
                """;
        Object[] filter = {a.tenant(), list, f, f, t, t};
        String cursor = blankToNull(before);
        String after = """
                  AND (e.posting_date,e.created_at,e.id,l.id) >
                      (SELECT ce.posting_date,ce.created_at,ce.id,cl.id FROM journal_lines cl
                       JOIN journal_entries ce ON ce.tenant_id=cl.tenant_id AND ce.id=cl.entry_id
                       WHERE cl.tenant_id=l.tenant_id AND cl.id=?)
                """;
        var rows = s.list(
                """
                SELECT l.id,l.entry_id,l.account,l.debit,l.credit,l.memo,e.posting_date::text AS posting_date,e.reference,
                       e.description,e.source,e.actor,e.created_at,e.reverses_entry_id,
                       (SELECT r.id FROM journal_entries r WHERE r.tenant_id=e.tenant_id AND r.reverses_entry_id=e.id) AS reversed_by_entry_id""" + range + (cursor == null ? "" : after) + " ORDER BY e.posting_date,e.created_at,e.id,l.id LIMIT ?",
                cursor == null ? concat(filter, limit + 1) : concat(filter, cursor, limit + 1));
        // The balance carried into this page: opening + every line of the range up to and including the cursor.
        BigDecimal carried = opening;
        if (cursor != null)
            carried =
                    carried.add(sum("SELECT COALESCE(SUM(l.debit-l.credit),0)" + range + """
                              AND (e.posting_date,e.created_at,e.id,l.id) <=
                                  (SELECT ce.posting_date,ce.created_at,ce.id,cl.id FROM journal_lines cl
                                   JOIN journal_entries ce ON ce.tenant_id=cl.tenant_id AND ce.id=cl.entry_id
                                   WHERE cl.tenant_id=l.tenant_id AND cl.id=?)""", concat(filter, cursor)));
        boolean more = rows.size() > limit;
        var items = new ArrayList<Map<String, Object>>();
        BigDecimal running = carried;
        for (var r : more ? rows.subList(0, limit) : rows) {
            var item = new LinkedHashMap<String, Object>(r);
            running = running.add(dec(r.get("debit"))).subtract(dec(r.get("credit")));
            item.put("balance", running);
            items.add(item);
        }
        var totals = s.one(
                "SELECT COALESCE(SUM(l.debit),0) AS debit,COALESCE(SUM(l.credit),0) AS credit,COUNT(*) AS lines"
                        + range,
                filter);
        var out = new LinkedHashMap<String, Object>();
        out.put("account", account);
        out.put("from", f == null ? null : f.toString());
        out.put("to", t == null ? null : t.toString());
        out.put("opening", opening);
        out.put("carried", carried);
        out.put("totals", totals);
        out.put("closing", opening.add(dec(totals.get("debit"))).subtract(dec(totals.get("credit"))));
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        return out;
    }

    // ------------------------------------------------------------------ trial balance

    /**
     * Trial balance: as of {@code to} (default today; cumulative debits and credits, {@code from} blank) or for the
     * period {@code from}..{@code to} (opening balance before {@code from}, the period's debits and credits, closing
     * balance). One row per account with activity (every postable account with {@code zero=true}); each balance is
     * shown in the debit or the credit column. {@code totals} sum every column; {@code balanced} is true when the
     * period's debits equal its credits and the closing debit and credit columns agree (always, for balanced entries).
     */
    public Map<String, Object> trialBalance(Actor a, String from, String to, boolean zero) {
        a.allow(FINANCE);
        LocalDate f = AccountingRules.optionalDate(from, "start date");
        LocalDate t = date(to, Store.today(), "end date");
        require(f == null || !t.isBefore(f), "The end date must be on or after the start date");
        var rows = s.list("""
                SELECT g.id,g.code,g.name,g.type,g.contra,g.normal_balance,g.postable,g.active,g.needs_review,
                       COALESCE(SUM(CASE WHEN ?::date IS NOT NULL AND e.posting_date<?::date THEN l.debit-l.credit END),0) AS opening,
                       COALESCE(SUM(CASE WHEN ?::date IS NULL OR e.posting_date>=?::date THEN l.debit END),0) AS debit,
                       COALESCE(SUM(CASE WHEN ?::date IS NULL OR e.posting_date>=?::date THEN l.credit END),0) AS credit
                FROM gl_accounts g
                LEFT JOIN (journal_lines l JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id AND e.posting_date<=?)
                       ON l.tenant_id=g.tenant_id AND l.account=g.code
                WHERE g.tenant_id=?
                GROUP BY g.id
                ORDER BY g.code""", f, f, f, f, f, f, t, a.tenant());
        var items = new ArrayList<Map<String, Object>>();
        String[] columns = {"opening_debit", "opening_credit", "debit", "credit", "closing_debit", "closing_credit"};
        var totals = new LinkedHashMap<String, BigDecimal>();
        for (String c : columns) totals.put(c, BigDecimal.ZERO);
        for (var r : rows) {
            BigDecimal opening = dec(r.get("opening"));
            BigDecimal debit = dec(r.get("debit"));
            BigDecimal credit = dec(r.get("credit"));
            BigDecimal closing = opening.add(debit).subtract(credit);
            boolean active = opening.signum() != 0 || debit.signum() != 0 || credit.signum() != 0;
            if (!active && !(zero && Boolean.TRUE.equals(r.get("postable")))) continue;
            var item = new LinkedHashMap<String, Object>(r);
            item.put("opening_debit", opening.max(BigDecimal.ZERO));
            item.put("opening_credit", opening.negate().max(BigDecimal.ZERO));
            item.put("closing", closing);
            item.put("closing_debit", closing.max(BigDecimal.ZERO));
            item.put("closing_credit", closing.negate().max(BigDecimal.ZERO));
            for (String c : columns) totals.put(c, totals.get(c).add(dec(item.get(c))));
            items.add(item);
        }
        items.sort(Comparator.comparingInt((Map<String, Object> r) -> TYPES.indexOf(str(r, "type")))
                .thenComparing(r -> str(r, "code")));
        var out = new LinkedHashMap<String, Object>();
        out.put("mode", f == null ? "AS_OF" : "PERIOD");
        out.put("from", f == null ? null : f.toString());
        out.put("to", t.toString());
        out.put("items", items);
        out.put("totals", totals);
        out.put(
                "balanced",
                totals.get("debit").compareTo(totals.get("credit")) == 0
                        && totals.get("closing_debit").compareTo(totals.get("closing_credit")) == 0
                        && totals.get("opening_debit").compareTo(totals.get("opening_credit")) == 0);
        return out;
    }

    // ------------------------------------------------------------------ manual journals

    private static final String JOURNAL_FROM = """
             FROM manual_journals j
            WHERE j.tenant_id=?
              AND (?::text IS NULL OR j.status=ANY(string_to_array(?,',')))
              AND (?::text IS NULL OR j.number ILIKE ? OR j.narration ILIKE ?)
            """;

    /**
     * Manual journals, newest first, keyset-paged. {@code status}: {@code all} (default), {@code open} (DRAFT and
     * SUBMITTED) or one status. Rows carry {@code can_approve} for the viewer. {@code {items, next, total, counts}}.
     */
    public Map<String, Object> journals(Actor a, String status, String q, int limit, String before) {
        a.allow(FINANCE);
        limit(limit);
        String statuses = statuses(status);
        String like = like(q);
        String cursor = blankToNull(before);
        Object[] filter = {a.tenant(), statuses, statuses, like, like, like};
        var rows = s.list("""
                SELECT j.id,j.number,j.status,j.posting_date::text AS posting_date,j.narration,j.total_debit,j.total_credit,
                       j.line_count,j.created_by,j.created_at,j.updated_by,j.submitted_by,j.submitted_at,j.approved_by,
                       j.approved_at,j.posted_at,j.approval_required,j.entry_id,j.reversal_entry_id,j.reversed_at,
                       (SELECT name FROM users u WHERE u.id=j.created_by) AS created_by_name,
                       (SELECT name FROM users u WHERE u.id=j.submitted_by) AS submitted_by_name,
                       (SELECT name FROM users u WHERE u.id=j.approved_by) AS approved_by_name""" + JOURNAL_FROM + """
                  AND (?::text IS NULL OR (j.created_at,j.id) <
                       (SELECT c.created_at,c.id FROM manual_journals c WHERE c.tenant_id=j.tenant_id AND c.id=?))
                ORDER BY j.created_at DESC,j.id DESC
                LIMIT ?""", concat(filter, cursor, cursor, limit + 1));
        var decision = approvers(a);
        boolean more = rows.size() > limit;
        var items = new ArrayList<Map<String, Object>>();
        for (var r : more ? rows.subList(0, limit) : rows) {
            var item = new LinkedHashMap<String, Object>(r);
            item.put("can_approve", canApprove(a, decision, r));
            items.add(item);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        out.put("total", s.db().queryForObject("SELECT COUNT(*)" + JOURNAL_FROM, Long.class, filter));
        var counts = new LinkedHashMap<String, Object>();
        for (var c : s.list(
                "SELECT status,COUNT(*) AS n FROM manual_journals WHERE tenant_id=? GROUP BY status", a.tenant()))
            counts.put(str(c, "status"), c.get("n"));
        out.put("counts", counts);
        return out;
    }

    /** One manual journal with its lines, posted entries, activity and what the viewer may do with it. */
    public Map<String, Object> journal(Actor a, String id) {
        a.allow(FINANCE);
        var j = new LinkedHashMap<String, Object>(s.one("""
                SELECT j.*,j.posting_date::text AS posting_date,
                       (SELECT name FROM users u WHERE u.id=j.created_by) AS created_by_name,
                       (SELECT name FROM users u WHERE u.id=j.updated_by) AS updated_by_name,
                       (SELECT name FROM users u WHERE u.id=j.submitted_by) AS submitted_by_name,
                       (SELECT name FROM users u WHERE u.id=j.approved_by) AS approved_by_name,
                       (SELECT name FROM users u WHERE u.id=j.returned_by) AS returned_by_name,
                       (SELECT name FROM users u WHERE u.id=j.cancelled_by) AS cancelled_by_name,
                       (SELECT name FROM users u WHERE u.id=j.reversed_by) AS reversed_by_name,
                       (SELECT e.posting_date::text FROM journal_entries e WHERE e.tenant_id=j.tenant_id AND e.id=j.reversal_entry_id) AS reversal_date
                FROM manual_journals j WHERE j.tenant_id=? AND j.id=?""", a.tenant(), id));
        j.put("lines", s.list("""
                SELECT l.id,l.line_no,l.account,g.id AS account_id,g.name AS account_name,g.type AS account_type,
                       g.active AS account_active,g.postable AS account_postable,l.debit,l.credit,l.memo
                FROM manual_journal_lines l
                LEFT JOIN gl_accounts g ON g.tenant_id=l.tenant_id AND g.code=l.account
                WHERE l.tenant_id=? AND l.journal_id=? ORDER BY l.line_no""", a.tenant(), id));
        j.put("activity", s.list("""
                SELECT id,action,actor,detail,created_at FROM audit_events
                WHERE tenant_id=? AND reference=? AND (action LIKE 'MANUAL_JOURNAL_%' OR action='JOURNAL_REVERSED')
                ORDER BY created_at,id LIMIT 100""", a.tenant(), j.get("number")));
        String state = str(j, "status");
        boolean finance = a.role().equals("ADMIN") || a.role().equals(FINANCE);
        boolean own = a.id().equals(j.get("created_by")) || a.id().equals(j.get("submitted_by"));
        var decision = approvers(a);
        var can = new LinkedHashMap<String, Object>();
        can.put("edit", finance && state.equals("DRAFT"));
        can.put("submit", finance && state.equals("DRAFT"));
        can.put("approve", canApprove(a, decision, j));
        can.put("return", state.equals("SUBMITTED") && (own ? finance : decision.roleMayApprove(a)));
        can.put("cancel", finance && (state.equals("DRAFT") || state.equals("SUBMITTED")));
        can.put("reverse", finance && state.equals("POSTED"));
        j.put("can", can);
        j.put("approverRoles", decision.approverRoles());
        return j;
    }

    /** A posted journal entry with its lines, what it reverses or was reversed by, and its manual journal if any. */
    public Map<String, Object> entry(Actor a, String id) {
        a.allow(FINANCE);
        var e = new LinkedHashMap<String, Object>(s.one("""
                SELECT e.id,e.reference,e.description,e.actor,e.created_at,e.posting_date::text AS posting_date,e.source,
                       e.reverses_entry_id,
                       (SELECT o.reference FROM journal_entries o WHERE o.tenant_id=e.tenant_id AND o.id=e.reverses_entry_id) AS reverses_reference,
                       (SELECT o.posting_date::text FROM journal_entries o WHERE o.tenant_id=e.tenant_id AND o.id=e.reverses_entry_id) AS reverses_posting_date,
                       r.id AS reversed_by_entry_id,r.posting_date::text AS reversed_on,
                       m.id AS manual_journal_id,m.number AS manual_journal_number
                FROM journal_entries e
                LEFT JOIN journal_entries r ON r.tenant_id=e.tenant_id AND r.reverses_entry_id=e.id
                LEFT JOIN manual_journals m ON m.tenant_id=e.tenant_id AND (m.entry_id=e.id OR m.reversal_entry_id=e.id)
                WHERE e.tenant_id=? AND e.id=?""", a.tenant(), id));
        e.put("lines", s.list("""
                SELECT l.id,l.account,g.id AS account_id,g.name AS account_name,l.debit,l.credit,l.memo
                FROM journal_lines l LEFT JOIN gl_accounts g ON g.tenant_id=l.tenant_id AND g.code=l.account
                WHERE l.tenant_id=? AND l.entry_id=? ORDER BY l.debit DESC,l.id""", a.tenant(), id));
        boolean finance = a.role().equals("ADMIN") || a.role().equals(FINANCE);
        e.put(
                "can_reverse",
                finance && !Store.REVERSAL.equals(e.get("source")) && e.get("reversed_by_entry_id") == null);
        return e;
    }

    /** The tenant's {@code MANUAL_JOURNAL} approval policy (edited by ADMIN through the approval-policy command). */
    public Map<String, Object> policy(Actor a) {
        a.allow(FINANCE);
        return policies.policy(a.tenant(), ApprovalPolicyService.MANUAL_JOURNAL).toMap();
    }

    // ------------------------------------------------------------------ helpers

    private ApprovalPolicyService.Decision approvers(Actor a) {
        return policies.approverDecision(a.tenant(), ApprovalPolicyService.MANUAL_JOURNAL, null);
    }

    /** SUBMITTED, an approver role of the policy, and neither the creator, the last editor nor the submitter. */
    static boolean canApprove(Actor a, ApprovalPolicyService.Decision decision, Map<String, Object> j) {
        return "SUBMITTED".equals(j.get("status"))
                && decision.roleMayApprove(a)
                && !a.id().equals(j.get("created_by"))
                && !a.id().equals(j.get("updated_by"))
                && !a.id().equals(j.get("submitted_by"));
    }

    private BigDecimal sum(String sql, Object... args) {
        return dec(s.db().queryForObject(sql, BigDecimal.class, args));
    }

    private static LocalDate date(String value, LocalDate fallback, String what) {
        var parsed = AccountingRules.optionalDate(value, what);
        return parsed == null ? fallback : parsed;
    }

    private static void limit(int limit) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
    }

    private static String statuses(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("all")) return null;
        if (status.equalsIgnoreCase("open")) return "DRAFT,SUBMITTED";
        String code = status.strip().toUpperCase(Locale.ROOT);
        require(STATUSES.contains(code), "Unknown manual journal status filter");
        return code;
    }

    private static String like(String q) {
        String text = blankToNull(q);
        return text == null
                ? null
                : "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static Object[] concat(Object[] head, Object... tail) {
        var out = new Object[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
