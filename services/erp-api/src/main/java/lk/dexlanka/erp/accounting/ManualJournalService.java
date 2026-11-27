package lk.dexlanka.erp.accounting;

import static lk.dexlanka.erp.accounting.AccountingRules.FINANCE;
import static lk.dexlanka.erp.accounting.AccountingRules.amount;
import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.admin.ApprovalPolicyService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual journals (V24, documents {@code JV-…}; FINANCE and ADMIN): DRAFT (editable, may be incomplete) → submit →
 * approve → POSTED, which creates one journal entry with source MANUAL on the journal's posting date. A submitted
 * journal can be returned to draft with a reason; drafts and submitted journals can be cancelled with a reason; a posted
 * journal is reversed through {@link ReversalService}.
 *
 * <p>Posting rules (checked on submit and again on approval): at least two lines, each line exactly one positive amount
 * to the cent, debits equal to credits, active postable accounts, a posting date outside closed periods. Approval
 * follows the {@code MANUAL_JOURNAL} approval policy (default: every journal, approvers FINANCE and ADMIN): when the
 * policy needs no second person for the journal's total, submitting posts it directly (audited as auto-approved by
 * policy); otherwise an approver who is neither its creator, its last editor nor its submitter posts it (ADMIN
 * included, never self-approval; the database repeats the rule).
 */
@Service
public class ManualJournalService {
    private final Store s;
    private final ApprovalPolicyService policies;

    ManualJournalService(Store s, ApprovalPolicyService policies) {
        this.s = s;
        this.policies = policies;
    }

    @Transactional
    public Map<String, Object> create(Actor a, AccountingCommands.Journal c, String key) {
        a.allow(FINANCE);
        LocalDate date = AccountingRules.date(c.postingDate(), "posting date");
        return s.once(a, key, "mj-create", c, () -> {
            var lines = lines(a, c.lines());
            String id = Store.id();
            String number = number(a);
            s.db().update("""
                            INSERT INTO manual_journals(id,tenant_id,number,status,posting_date,narration,created_by,updated_by)
                            VALUES(?,?,?,'DRAFT',?,?,?,?)""", id, a.tenant(), number, date, c.narration().strip(), a.id(), a.id());
            writeLines(a, id, lines);
            s.audit(a, "MANUAL_JOURNAL_CREATED", number, summary(date, c.narration(), lines));
            return result(a, id);
        });
    }

    @Transactional
    public Map<String, Object> update(Actor a, String id, AccountingCommands.Journal c, String key) {
        a.allow(FINANCE);
        LocalDate date = AccountingRules.date(c.postingDate(), "posting date");
        return s.once(a, key, "mj-update:" + id, c, () -> {
            var j = locked(a, id);
            require(
                    j.get("status").equals("DRAFT"),
                    "Manual journal " + j.get("number") + " is " + status(j) + "; only drafts can be edited");
            var lines = lines(a, c.lines());
            s.db().update("DELETE FROM manual_journal_lines WHERE tenant_id=? AND journal_id=?", a.tenant(), id);
            s.db().update(
                            "UPDATE manual_journals SET posting_date=?,narration=?,updated_by=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            date,
                            c.narration().strip(),
                            a.id(),
                            a.tenant(),
                            id);
            writeLines(a, id, lines);
            s.audit(a, "MANUAL_JOURNAL_UPDATED", str(j, "number"), summary(date, c.narration(), lines));
            return result(a, id);
        });
    }

    /**
     * Sends a complete, balanced draft for approval, or posts it directly when the {@code MANUAL_JOURNAL} policy needs
     * no second person for its total.
     */
    @Transactional
    public Map<String, Object> submit(Actor a, String id, String key) {
        a.allow(FINANCE);
        return s.once(a, key, "mj-submit:" + id, Map.of(), () -> {
            var j = locked(a, id);
            require(
                    j.get("status").equals("DRAFT"),
                    "Manual journal " + j.get("number") + " is " + status(j) + "; only drafts can be submitted");
            var lines = postable(a, j);
            BigDecimal total = dec(j.get("total_debit"));
            var decision = policies.evaluate(
                    a.tenant(), ApprovalPolicyService.MANUAL_JOURNAL, money(total), str(j, "created_by"));
            s.db().update("""
                            UPDATE manual_journals SET status='SUBMITTED',submitted_by=?,submitted_at=now(),
                                   approval_required=?,approval_threshold=?
                            WHERE tenant_id=? AND id=?""", a.id(), decision.approvalRequired(), decision.threshold(), a.tenant(), id);
            s.audit(
                    a,
                    "MANUAL_JOURNAL_SUBMITTED",
                    str(j, "number"),
                    "Dated " + j.get("posting_date") + " · total Rs. " + amount(total)
                            + (decision.approvalRequired() ? " · awaiting independent approval" : ""));
            if (!decision.approvalRequired()) {
                post(a, j, lines, null);
                s.audit(
                        a,
                        "MANUAL_JOURNAL_AUTO_APPROVED",
                        str(j, "number"),
                        ApprovalPolicyService.autoApproval(decision, money(total)));
            }
            return result(a, id);
        });
    }

    /**
     * Independent approval: an approver role of the {@code MANUAL_JOURNAL} policy (default FINANCE; ADMIN always) who
     * neither created, last edited nor submitted the journal. Posts the journal entry.
     */
    @Transactional
    public Map<String, Object> approve(Actor a, String id, String key) {
        policies.requireApproverRole(a, ApprovalPolicyService.MANUAL_JOURNAL);
        return s.once(a, key, "mj-approve:" + id, Map.of(), () -> {
            var j = locked(a, id);
            require(
                    j.get("status").equals("SUBMITTED"),
                    j.get("status").equals("DRAFT")
                            ? "Submit the draft for approval first"
                            : "Manual journal " + j.get("number") + " is " + status(j)
                                    + "; only journals awaiting approval can be approved");
            policies.approverDecision(a.tenant(), ApprovalPolicyService.MANUAL_JOURNAL, str(j, "created_by"))
                    .requireApprover(a);
            require(
                    !a.id().equals(j.get("submitted_by")) && !a.id().equals(j.get("updated_by")),
                    "A different authorized person must approve this journal: you edited or submitted it");
            var lines = postable(a, j);
            post(a, j, lines, a.id());
            s.audit(
                    a,
                    "MANUAL_JOURNAL_APPROVED",
                    str(j, "number"),
                    "Approved and posted on " + j.get("posting_date") + " · total Rs. "
                            + amount(dec(j.get("total_debit"))));
            return result(a, id);
        });
    }

    /** SUBMITTED → DRAFT with a reason: an approver sends it back, or its creator or submitter withdraws it. */
    @Transactional
    public Map<String, Object> returnToDraft(Actor a, String id, AccountingCommands.Reason c, String key) {
        a.allow(FINANCE);
        return s.once(a, key, "mj-return:" + id, c, () -> {
            var j = locked(a, id);
            require(
                    j.get("status").equals("SUBMITTED"),
                    "Manual journal " + j.get("number") + " is " + status(j)
                            + "; only journals awaiting approval can be returned to draft");
            boolean own = a.id().equals(j.get("created_by")) || a.id().equals(j.get("submitted_by"));
            if (!own) policies.requireApproverRole(a, ApprovalPolicyService.MANUAL_JOURNAL);
            s.db().update("""
                            UPDATE manual_journals SET status='DRAFT',submitted_by=NULL,submitted_at=NULL,approval_required=NULL,
                                   approval_threshold=NULL,returned_by=?,returned_at=now(),return_reason=?
                            WHERE tenant_id=? AND id=?""", a.id(), c.reason().strip(), a.tenant(), id);
            s.audit(
                    a,
                    "MANUAL_JOURNAL_RETURNED",
                    str(j, "number"),
                    (own ? "Withdrawn: " : "Returned to draft: ") + c.reason().strip());
            return result(a, id);
        });
    }

    /** DRAFT or SUBMITTED → CANCELLED with a reason. Nothing was posted, so nothing is reversed. */
    @Transactional
    public Map<String, Object> cancel(Actor a, String id, AccountingCommands.Reason c, String key) {
        a.allow(FINANCE);
        return s.once(a, key, "mj-cancel:" + id, c, () -> {
            var j = locked(a, id);
            require(
                    j.get("status").equals("DRAFT") || j.get("status").equals("SUBMITTED"),
                    "Manual journal " + j.get("number") + " is " + status(j)
                            + (j.get("status").equals("POSTED") ? "; reverse it instead" : "; it cannot be cancelled"));
            s.db().update(
                            "UPDATE manual_journals SET status='CANCELLED',cancelled_by=?,cancelled_at=now(),cancel_reason=? WHERE tenant_id=? AND id=?",
                            a.id(),
                            c.reason().strip(),
                            a.tenant(),
                            id);
            s.audit(a, "MANUAL_JOURNAL_CANCELLED", str(j, "number"), c.reason().strip());
            return result(a, id);
        });
    }

    // ------------------------------------------------------------------ helpers

    private record Line(String account, BigDecimal debit, BigDecimal credit, String memo) {}

    /** Validates draft lines: chart accounts that are active and postable, exactly one positive side to the cent. */
    private List<Line> lines(Actor a, List<AccountingCommands.Line> input) {
        var out = new ArrayList<Line>();
        for (int i = 0; i < input.size(); i++) {
            var l = input.get(i);
            String where = "Line " + (i + 1) + ": ";
            BigDecimal dr = l.debit() == null ? BigDecimal.ZERO : l.debit();
            BigDecimal cr = l.credit() == null ? BigDecimal.ZERO : l.credit();
            require((dr.signum() > 0) != (cr.signum() > 0), where + "enter either a debit or a credit amount");
            AccountingRules.postable(s, a.tenant(), l.account().strip(), where);
            out.add(new Line(
                    l.account().strip(),
                    money(dr),
                    money(cr),
                    l.memo() == null ? "" : l.memo().strip()));
        }
        return out;
    }

    private void writeLines(Actor a, String id, List<Line> lines) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            var l = lines.get(i);
            s.db().update(
                            "INSERT INTO manual_journal_lines(id,tenant_id,journal_id,line_no,account,debit,credit,memo) VALUES(?,?,?,?,?,?,?,?)",
                            Store.id(),
                            a.tenant(),
                            id,
                            i + 1,
                            l.account(),
                            l.debit(),
                            l.credit(),
                            l.memo());
            debit = debit.add(l.debit());
            credit = credit.add(l.credit());
        }
        s.db().update(
                        "UPDATE manual_journals SET total_debit=?,total_credit=?,line_count=? WHERE tenant_id=? AND id=?",
                        debit,
                        credit,
                        lines.size(),
                        a.tenant(),
                        id);
    }

    /** The posting rules on the stored draft; returns its lines ready to post. */
    private List<Store.JournalLine> postable(Actor a, Map<String, Object> j) {
        var rows = s.list(
                "SELECT * FROM manual_journal_lines WHERE tenant_id=? AND journal_id=? ORDER BY line_no",
                a.tenant(),
                j.get("id"));
        require(rows.size() >= 2, "A manual journal needs at least two lines");
        BigDecimal debit = dec(j.get("total_debit"));
        BigDecimal credit = dec(j.get("total_credit"));
        require(
                debit.compareTo(credit) == 0,
                "The journal does not balance: debits Rs. " + amount(debit) + ", credits Rs. " + amount(credit));
        require(debit.signum() > 0, "The journal has no amounts");
        var out = new ArrayList<Store.JournalLine>();
        for (var r : rows) {
            AccountingRules.postable(s, a.tenant(), str(r, "account"), "Line " + r.get("line_no") + ": ");
            out.add(new Store.JournalLine(
                    str(r, "account"), dec(r.get("debit")), dec(r.get("credit")), str(r, "memo")));
        }
        s.openPeriod(a, LocalDate.parse(j.get("posting_date").toString()));
        return out;
    }

    /** Posts the journal entry (source MANUAL) and marks the journal POSTED; {@code approver} null = by policy. */
    private void post(Actor a, Map<String, Object> j, List<Store.JournalLine> lines, String approver) {
        String entry = s.journal(
                a,
                str(j, "number"),
                "Manual journal: " + str(j, "narration"),
                LocalDate.parse(j.get("posting_date").toString()),
                Store.MANUAL,
                null,
                lines);
        s.db().update("""
                        UPDATE manual_journals SET status='POSTED',approved_by=?,approved_at=now(),posted_at=now(),entry_id=?
                        WHERE tenant_id=? AND id=?""", approver, entry, a.tenant(), j.get("id"));
    }

    private Map<String, Object> locked(Actor a, String id) {
        return s.one("SELECT * FROM manual_journals WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), id);
    }

    private String number(Actor a) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String number = s.number(a, "JV");
            if (s.list("SELECT 1 FROM manual_journals WHERE tenant_id=? AND number=?", a.tenant(), number)
                    .isEmpty()) return number;
        }
        throw new IllegalStateException("Could not allocate a manual journal number");
    }

    private static String status(Map<String, Object> j) {
        return j.get("status").toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static String summary(LocalDate date, String narration, List<Line> lines) {
        BigDecimal debit = lines.stream().map(Line::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = lines.stream().map(Line::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return "Dated " + date + " · " + lines.size() + (lines.size() == 1 ? " line" : " lines") + " · debits Rs. "
                + amount(debit) + ", credits Rs. " + amount(credit) + " · " + narration.strip();
    }

    private Map<String, Object> result(Actor a, String id) {
        var row = s.one(
                "SELECT id,number,status,posting_date::text AS posting_date,total_debit,total_credit,entry_id FROM manual_journals WHERE tenant_id=? AND id=?",
                a.tenant(),
                id);
        return new LinkedHashMap<>(row);
    }
}
