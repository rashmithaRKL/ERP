package lk.dexlanka.erp.reporting;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/** Latest audit events and the (always local-only) outbox synchronization status. */
@Component
class AuditStateSections implements StateContributor {
    private final Store s;

    AuditStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("audit", 290, this::audit), StateSection.of("sync", 300, this::sync));
    }

    private List<Map<String, Object>> audit(StateRequest r) {
        return s.list("SELECT * FROM audit_events WHERE tenant_id=? ORDER BY created_at DESC,id LIMIT 100", r.tenant());
    }

    private Map<String, Object> sync(StateRequest r) {
        return s.one(
                "SELECT COUNT(*) AS local_events,0 AS acknowledged, 'NOT_CONFIGURED' AS status FROM outbox_events WHERE tenant_id=?",
                r.tenant());
    }
}
