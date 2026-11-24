package lk.dexlanka.erp.reporting;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * The (always local-only) outbox synchronization status. The audit trail itself is no longer part of the snapshot
 * (V8): administrators read it server-paged from {@code GET /api/admin/audit}.
 */
@Component
class AuditStateSections implements StateContributor {
    private final Store s;

    AuditStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("sync", 300, this::sync));
    }

    private Map<String, Object> sync(StateRequest r) {
        return s.one(
                "SELECT COUNT(*) AS local_events,0 AS acknowledged, 'NOT_CONFIGURED' AS status FROM outbox_events WHERE tenant_id=?",
                r.tenant());
    }
}
