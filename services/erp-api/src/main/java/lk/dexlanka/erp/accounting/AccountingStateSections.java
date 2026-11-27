package lk.dexlanka.erp.accounting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.admin.ApprovalPolicyService;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateRequest;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/** {@code /api/state} key owned by the general ledger (V24, order 3400; ADMIN and FINANCE only). */
@Component
class AccountingStateSections implements StateContributor {
    private final Store s;
    private final ApprovalPolicyService policies;

    AccountingStateSections(Store s, ApprovalPolicyService policies) {
        this.s = s;
        this.policies = policies;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("accounting", 3400, this::accounting).visibleToRoles("ADMIN", "FINANCE"));
    }

    /**
     * Attention counts: manual journals awaiting approval (all, and those the viewer may approve: an approver role of
     * the MANUAL_JOURNAL policy who neither created, last edited nor submitted them), drafts, active accounts flagged for
     * review, and up to five journals awaiting the viewer's approval (oldest submission first).
     */
    private Map<String, Object> accounting(StateRequest r) {
        var a = r.actor();
        boolean approver = policies.approverDecision(a.tenant(), ApprovalPolicyService.MANUAL_JOURNAL, null)
                .roleMayApprove(a);
        var out = new LinkedHashMap<String, Object>(s.one("""
                SELECT (SELECT COUNT(*) FROM manual_journals WHERE tenant_id=t.id AND status='SUBMITTED') AS awaiting_approval,
                       (SELECT COUNT(*) FROM manual_journals WHERE tenant_id=t.id AND status='SUBMITTED' AND ?
                          AND created_by<>? AND updated_by<>? AND submitted_by<>?) AS awaiting_my_approval,
                       (SELECT COUNT(*) FROM manual_journals WHERE tenant_id=t.id AND status='DRAFT') AS drafts,
                       (SELECT COUNT(*) FROM gl_accounts WHERE tenant_id=t.id AND needs_review AND active) AS needs_review
                FROM tenants t WHERE t.id=?""", approver, a.id(), a.id(), a.id(), a.tenant()));
        out.put("awaiting", approver ? s.list("""
                                SELECT j.id,j.number,j.narration,j.total_debit,j.posting_date::text AS posting_date,j.submitted_at,
                                       (SELECT name FROM users u WHERE u.id=j.submitted_by) AS submitted_by_name
                                FROM manual_journals j
                                WHERE j.tenant_id=? AND j.status='SUBMITTED' AND j.created_by<>? AND j.updated_by<>? AND j.submitted_by<>?
                                ORDER BY j.submitted_at,j.id
                                LIMIT 5""", a.tenant(), a.id(), a.id(), a.id()) : List.of());
        return out;
    }
}
