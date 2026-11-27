package lk.dexlanka.erp.admin;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-tenant approval policies for {@value #MANUFACTURING_ORDER}, {@value #PURCHASE_ORDER}, {@value #STOCK_ADJUSTMENT}
 * and (V24) {@value #MANUAL_JOURNAL}: whether a second person must approve, above which document value, and which roles may
 * approve (ADMIN always may). The self-approval ban is absolute and not part of a policy: {@link Decision#requireApprover}
 * rejects the document's creator, including an ADMIN.
 *
 * <p>Without a stored row a document type uses its {@link #defaults built-in default}, which reproduces the behaviour
 * before policies existed. Every document type is wired (V9): stock adjustments (and cycle-count variances, which post
 * through them) are evaluated when posted, manufacturing orders when created (amount = estimated cost) and purchase
 * orders when submitted (amount = order total). A document whose evaluation needs no approval takes effect directly
 * and is audited as auto-approved by policy ({@link #autoApproval}); the others wait for an approver chosen by the
 * current policy's roles ({@link #approverDecision}).
 *
 * <p>Read methods are not transactional: they join the caller's transaction (a command holding the tenant lock, or a
 * read-only snapshot).
 */
@Service
public class ApprovalPolicyService {
    public static final String MANUFACTURING_ORDER = "MANUFACTURING_ORDER";
    public static final String PURCHASE_ORDER = "PURCHASE_ORDER";
    public static final String STOCK_ADJUSTMENT = "STOCK_ADJUSTMENT";
    /**
     * V24: manual journals (package {@code accounting}), evaluated when submitted (amount = total debits). Approved by
     * FINANCE (and ADMIN) only; listed by {@link #summaries} and edited through the same command, but kept out of the
     * administration list of operational documents ({@link #DOCUMENT_TYPES}): the Accounting page shows it.
     */
    public static final String MANUAL_JOURNAL = "MANUAL_JOURNAL";
    /** The operational document types of the Administration → Approval policies tab. */
    public static final List<String> DOCUMENT_TYPES = List.of(MANUFACTURING_ORDER, PURCHASE_ORDER, STOCK_ADJUSTMENT);
    /** Every document type with a policy (V24 adds {@link #MANUAL_JOURNAL}). */
    public static final List<String> ALL_TYPES =
            List.of(MANUFACTURING_ORDER, PURCHASE_ORDER, STOCK_ADJUSTMENT, MANUAL_JOURNAL);
    /** Document types whose commands evaluate the policy (all of them since V9; manual journals since V24). */
    public static final List<String> WIRED = ALL_TYPES;
    /** V24: FINANCE approves manual journals only, and manual journals are approved by FINANCE only (ADMIN always). */
    static final List<String> FINANCE_ROLES = List.of("FINANCE");

    static final BigDecimal DEFAULT_ADJUSTMENT_THRESHOLD = new BigDecimal("50000");

    private final Store s;

    ApprovalPolicyService(Store s) {
        this.s = s;
    }

    /**
     * A document type's policy.
     *
     * @param amountThreshold approval is needed only above this value; null = for every document
     * @param approverRoles roles besides ADMIN that may approve (ADMIN always may)
     * @param customized false while the built-in default applies (no stored row)
     */
    public record Policy(
            String documentType,
            boolean approvalRequired,
            BigDecimal amountThreshold,
            List<String> approverRoles,
            boolean customized,
            Object updatedAt,
            Object updatedBy) {
        public Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("documentType", documentType);
            m.put("approvalRequired", approvalRequired);
            m.put("amountThreshold", amountThreshold);
            m.put("approverRoles", approverRoles);
            m.put("customized", customized);
            m.put("wired", WIRED.contains(documentType));
            m.put("updatedAt", updatedAt);
            m.put("updatedBy", updatedBy);
            return m;
        }
    }

    /**
     * The outcome of {@link #evaluate} for one document.
     *
     * @param approvalRequired a second person must approve this document before it takes effect
     * @param threshold the policy's amount threshold when the policy requires approval (null: every document, or the
     *     policy requires none)
     * @param approverRoles roles besides ADMIN that may approve
     * @param creatorId the document's creator, who may never approve it
     */
    public record Decision(
            String documentType,
            boolean approvalRequired,
            BigDecimal threshold,
            List<String> approverRoles,
            String creatorId) {
        /** True when {@code a} has an approving role (ADMIN or listed); does not check the self-approval ban. */
        public boolean roleMayApprove(Actor a) {
            return a.role().equals("ADMIN") || approverRoles.contains(a.role());
        }

        /** True when {@code a} may approve: an approving role and not the creator. */
        public boolean mayApprove(Actor a) {
            return roleMayApprove(a) && !a.id().equals(creatorId);
        }

        /** 403 for a role that may not approve; 409 for the document's creator (also an ADMIN). */
        public void requireApprover(Actor a) {
            if (!roleMayApprove(a)) throw new AccessDeniedException("Your role cannot perform this action");
            require(!a.id().equals(creatorId), "A different authorized person must approve this document");
        }
    }

    /** The built-in policy of a document type (the behaviour before configurable policies). */
    static Policy defaults(String documentType) {
        return switch (documentType) {
            case MANUFACTURING_ORDER, PURCHASE_ORDER ->
                new Policy(documentType, true, null, List.of("PLANNER"), false, null, null);
            case STOCK_ADJUSTMENT ->
                new Policy(
                        documentType,
                        true,
                        DEFAULT_ADJUSTMENT_THRESHOLD,
                        List.of("PLANNER", "WAREHOUSE"),
                        false,
                        null,
                        null);
            case MANUAL_JOURNAL -> new Policy(documentType, true, null, FINANCE_ROLES, false, null, null);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown document type");
        };
    }

    /** The tenant's policy for {@code documentType} (its built-in default when none is stored). */
    public Policy policy(String tenant, String documentType) {
        var fallback = defaults(documentType);
        var rows = s.list("""
                SELECT p.*,u.name AS updated_by_name FROM approval_policies p LEFT JOIN users u ON u.id=p.updated_by
                WHERE p.tenant_id=? AND p.document_type=?""", tenant, documentType);
        if (rows.isEmpty()) return fallback;
        var r = rows.getFirst();
        return new Policy(
                documentType,
                Boolean.TRUE.equals(r.get("approval_required")),
                r.get("amount_threshold") == null ? null : dec(r.get("amount_threshold")),
                roles(r.get("approver_roles")),
                true,
                r.get("updated_at"),
                r.get("updated_by_name"));
    }

    /**
     * Whether a document of {@code documentType} worth {@code amount}, created by {@code creatorId}, needs a second
     * person's approval: the policy requires approval and has no threshold, or {@code amount} exceeds it (strictly).
     */
    public Decision evaluate(String tenant, String documentType, BigDecimal amount, String creatorId) {
        var p = policy(tenant, documentType);
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        boolean required =
                p.approvalRequired() && (p.amountThreshold() == null || value.compareTo(p.amountThreshold()) > 0);
        return new Decision(
                documentType,
                required,
                p.approvalRequired() ? p.amountThreshold() : null,
                p.approverRoles(),
                creatorId);
    }

    /** 403 unless {@code a} is ADMIN or has one of the policy's approver roles. */
    public void requireApproverRole(Actor a, String documentType) {
        a.allow(policy(a.tenant(), documentType).approverRoles().toArray(String[]::new));
    }

    /**
     * Who may approve a document that is already waiting for approval: the current policy's approver roles (ADMIN
     * always), never its creator. Whether the document needed approval was decided when it was created or submitted; a
     * later policy change only changes who may approve it, so a waiting document is never stranded.
     */
    public Decision approverDecision(String tenant, String documentType, String creatorId) {
        var p = policy(tenant, documentType);
        return new Decision(documentType, true, p.amountThreshold(), p.approverRoles(), creatorId);
    }

    /** The audit detail of a document that took effect without a second person ({@code d} needed no approval). */
    public static String autoApproval(Decision d, BigDecimal amount) {
        require(!d.approvalRequired(), "This document needs a second person's approval");
        return d.threshold() == null
                ? "Auto-approved by policy: no second approval required"
                : "Auto-approved by policy: Rs. " + rupees(amount == null ? BigDecimal.ZERO : amount)
                        + " is within the Rs. " + rupees(d.threshold()) + " approval threshold";
    }

    /**
     * The policies every signed-in user may read (which documents need a second person, above which value and who
     * approves them), so the workspace offers approve buttons to the right roles.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> summaries(Actor a) {
        var out = new ArrayList<Map<String, Object>>();
        for (String type : ALL_TYPES) {
            var p = policy(a.tenant(), type);
            var m = new LinkedHashMap<String, Object>();
            m.put("documentType", type);
            m.put("approvalRequired", p.approvalRequired());
            m.put("amountThreshold", p.amountThreshold());
            m.put("approverRoles", p.approverRoles());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> policies(Actor a) {
        a.allow();
        var out = new ArrayList<Map<String, Object>>();
        for (String type : DOCUMENT_TYPES) out.add(policy(a.tenant(), type).toMap());
        return out;
    }

    /** ADMIN: replaces a document type's policy. */
    @Transactional
    public Map<String, Object> update(Actor a, String documentType, AdminCommands.Policy c, String key) {
        a.allow();
        defaults(documentType); // 404 for an unknown type
        return s.once(a, key, "approval-policy:" + documentType, c, () -> {
            var roles = c.approverRoles().stream().distinct().sorted().toList();
            if (MANUAL_JOURNAL.equals(documentType))
                require(
                        FINANCE_ROLES.containsAll(roles),
                        "Manual journals are approved by Finance and administrators only");
            else require(!roles.contains("FINANCE"), "Finance approves manual journals only");
            store(a, documentType, c.approvalRequired(), c.amountThreshold(), roles);
            var p = policy(a.tenant(), documentType);
            s.audit(a, "APPROVAL_POLICY_UPDATED", documentType, describe(p));
            return p.toMap();
        });
    }

    /**
     * Changes only the amount threshold of a policy, inside the caller's transaction (the inventory settings command,
     * which keeps its own audit event). The other fields keep their stored or default values.
     */
    public void setThreshold(Actor a, String documentType, BigDecimal threshold) {
        var p = policy(a.tenant(), documentType);
        store(a, documentType, p.approvalRequired(), threshold, p.approverRoles());
    }

    private void store(Actor a, String documentType, boolean required, BigDecimal threshold, List<String> roles) {
        s.db().update("""
                        INSERT INTO approval_policies(tenant_id,document_type,approval_required,amount_threshold,approver_roles,updated_by,updated_at)
                        VALUES(?,?,?,?,?,?,now())
                        ON CONFLICT (tenant_id,document_type) DO UPDATE
                        SET approval_required=EXCLUDED.approval_required,amount_threshold=EXCLUDED.amount_threshold,
                            approver_roles=EXCLUDED.approver_roles,updated_by=EXCLUDED.updated_by,updated_at=now()""", a.tenant(), documentType, required, threshold, String.join(",", roles), a.id());
    }

    static List<String> roles(Object stored) {
        String text = stored == null ? "" : stored.toString();
        return text.isBlank() ? List.of() : Arrays.stream(text.split(",")).toList();
    }

    static String describe(Policy p) {
        String label = switch (p.documentType()) {
            case MANUFACTURING_ORDER -> "Manufacturing orders";
            case PURCHASE_ORDER -> "Purchase orders";
            case MANUAL_JOURNAL -> "Manual journals";
            default -> "Stock adjustments";
        };
        if (!p.approvalRequired()) return label + ": no second approval";
        var who = new ArrayList<String>();
        who.add("Admin");
        for (String r : p.approverRoles()) who.add(r.charAt(0) + r.substring(1).toLowerCase(Locale.ROOT));
        return label + ": "
                + (p.amountThreshold() == null ? "every document" : "above Rs. " + rupees(p.amountThreshold()))
                + " · approvers " + String.join(", ", who);
    }

    /** {@code 50000.00}: a money amount for audit details. */
    static String rupees(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
