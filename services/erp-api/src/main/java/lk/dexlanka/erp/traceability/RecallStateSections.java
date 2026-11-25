package lk.dexlanka.erp.traceability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateRequest;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/** {@code /api/state} key owned by traceability (V14, order 2400). */
@Component
class RecallStateSections implements StateContributor {
    private final Store s;

    RecallStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("recalls", 2400, this::recalls));
    }

    /**
     * Attention counts for the overview and attention center: open (not yet contained) and contained recalls, customers
     * still to notify, and up to five active recalls (open or contained), most severe first.
     */
    private Map<String, Object> recalls(StateRequest r) {
        var out = new LinkedHashMap<String, Object>(s.one("""
                SELECT (SELECT COUNT(*) FROM recalls WHERE tenant_id=t.id AND status='OPEN') AS open,
                       (SELECT COUNT(*) FROM recalls WHERE tenant_id=t.id AND status='CONTAINED') AS contained,
                       (SELECT COUNT(*) FROM recall_customers c JOIN recalls x ON x.tenant_id=c.tenant_id AND x.id=c.recall_id
                        WHERE c.tenant_id=t.id AND c.status='PENDING' AND x.status='CONTAINED') AS pending_customers
                FROM tenants t WHERE t.id=?""", r.tenant()));
        out.put("active", s.list("""
                SELECT r.id,r.number,r.title,r.severity,r.status,r.created_at,
                       (SELECT COUNT(*) FROM recall_customers c WHERE c.tenant_id=r.tenant_id AND c.recall_id=r.id
                          AND c.status='PENDING') AS pending_customers
                FROM recalls r WHERE r.tenant_id=? AND r.status IN ('OPEN','CONTAINED')
                ORDER BY CASE r.severity WHEN 'CRITICAL' THEN 0 WHEN 'MAJOR' THEN 1 ELSE 2 END,r.created_at DESC,r.id
                LIMIT 5""", r.tenant()));
        return out;
    }
}
