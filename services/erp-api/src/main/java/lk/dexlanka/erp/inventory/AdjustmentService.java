package lk.dexlanka.erp.inventory;

import java.util.Map;
import lk.dexlanka.erp.admin.ApprovalPolicyService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock adjustments (DRAFT → POSTED, or → APPROVAL_REQUIRED → POSTED when the approval policy asks for a second person;
 * CANCELLED) and the approval threshold setting. Posting rules live in {@link AdjustmentPosting}, shared with cycle
 * counts. Who may approve, and above which value, is the tenant's {@code STOCK_ADJUSTMENT} policy in {@link
 * ApprovalPolicyService} (V8; it replaced V5's {@code inventory_settings}).
 */
@Service
public class AdjustmentService {
    private final Store s;
    private final AdjustmentPosting posting;
    private final ApprovalPolicyService policies;

    AdjustmentService(Store s, AdjustmentPosting posting, ApprovalPolicyService policies) {
        this.s = s;
        this.posting = posting;
        this.policies = policies;
    }

    @Transactional
    public Map<String, Object> create(Actor a, InventoryCommands.Adjustment c, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "adjustment", c, () -> {
            String id = posting.insert(a, c.reason(), c.notes(), c.lines());
            return s.one("SELECT id,number,status FROM stock_adjustments WHERE tenant_id=? AND id=?", a.tenant(), id);
        });
    }

    /** Posts a draft, or moves it to APPROVAL_REQUIRED when the approval policy requires a second person. */
    @Transactional
    public Map<String, Object> post(Actor a, String id, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "post-adjustment:" + id, Map.of(), () -> posting.submit(a, id));
    }

    /** The approver needs one of the policy's approver roles (default WAREHOUSE, PLANNER; ADMIN always). */
    @Transactional
    public Map<String, Object> approve(Actor a, String id, String key) {
        policies.requireApproverRole(a, ApprovalPolicyService.STOCK_ADJUSTMENT);
        return s.once(a, key, "approve-adjustment:" + id, Map.of(), () -> posting.approve(a, id));
    }

    @Transactional
    public Map<String, Object> cancel(Actor a, String id, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "cancel-adjustment:" + id, Map.of(), () -> posting.cancel(a, id));
    }

    /**
     * ADMIN only: the gross adjustment value above which a second person must approve. Writes the threshold of the
     * {@code STOCK_ADJUSTMENT} approval policy (its other settings are kept).
     */
    @Transactional
    public Map<String, Object> settings(Actor a, InventoryCommands.Settings c, String key) {
        a.allow();
        return s.once(a, key, "inventory-settings", c, () -> {
            policies.setThreshold(a, ApprovalPolicyService.STOCK_ADJUSTMENT, c.approvalThreshold());
            s.audit(
                    a,
                    "INVENTORY_SETTINGS_UPDATED",
                    "INVENTORY",
                    "Adjustment approval threshold Rs. "
                            + Store.money(c.approvalThreshold()).toPlainString());
            return Map.of("approvalThreshold", c.approvalThreshold());
        });
    }
}
