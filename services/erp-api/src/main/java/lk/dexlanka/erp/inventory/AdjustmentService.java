package lk.dexlanka.erp.inventory;

import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock adjustments (DRAFT → POSTED, or → APPROVAL_REQUIRED → POSTED when above the tenant threshold; CANCELLED) and
 * the approval threshold setting. Posting rules live in {@link AdjustmentPosting}, shared with cycle counts.
 */
@Service
public class AdjustmentService {
    private final Store s;
    private final AdjustmentPosting posting;

    AdjustmentService(Store s, AdjustmentPosting posting) {
        this.s = s;
        this.posting = posting;
    }

    @Transactional
    public Map<String, Object> create(Actor a, InventoryCommands.Adjustment c, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "adjustment", c, () -> {
            String id = posting.insert(a, c.reason(), c.notes(), c.lines());
            return s.one("SELECT id,number,status FROM stock_adjustments WHERE tenant_id=? AND id=?", a.tenant(), id);
        });
    }

    /** Posts a draft, or moves it to APPROVAL_REQUIRED when its gross value exceeds the threshold. */
    @Transactional
    public Map<String, Object> post(Actor a, String id, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "post-adjustment:" + id, Map.of(), () -> posting.submit(a, id));
    }

    @Transactional
    public Map<String, Object> approve(Actor a, String id, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "approve-adjustment:" + id, Map.of(), () -> posting.approve(a, id));
    }

    @Transactional
    public Map<String, Object> cancel(Actor a, String id, String key) {
        a.allow("WAREHOUSE", "PLANNER");
        return s.once(a, key, "cancel-adjustment:" + id, Map.of(), () -> posting.cancel(a, id));
    }

    /** ADMIN only: the gross adjustment value above which a second person must approve. */
    @Transactional
    public Map<String, Object> settings(Actor a, InventoryCommands.Settings c, String key) {
        a.allow();
        return s.once(a, key, "inventory-settings", c, () -> {
            s.db().update("""
                            INSERT INTO inventory_settings(tenant_id,approval_threshold,updated_by,updated_at) VALUES(?,?,?,now())
                            ON CONFLICT (tenant_id) DO UPDATE
                            SET approval_threshold=EXCLUDED.approval_threshold,updated_by=EXCLUDED.updated_by,updated_at=now()""", a.tenant(), c.approvalThreshold(), a.id());
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
