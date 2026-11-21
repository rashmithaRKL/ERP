package lk.dexlanka.erp.reporting;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/** Dashboard series and KPIs for the requested period ({@link StateRequest#days()}); dates use Asia/Colombo. */
@Component
class DashboardStateSections implements StateContributor {
    private final Store s;

    DashboardStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("trend", 270, this::trend), StateSection.of("metrics", 280, this::metrics));
    }

    private List<Map<String, Object>> trend(StateRequest r) {
        return s.list("""
                SELECT to_char(d,'DD Mon') AS day,
                       COALESCE(SUM(p.good_qty),0) AS completed,
                       COALESCE(SUM(p.scrap_qty),0) AS scrap
                FROM generate_series(CURRENT_DATE-(? - 1),CURRENT_DATE,interval '1 day') d
                LEFT JOIN production_actuals p
                       ON p.tenant_id=? AND (p.created_at AT TIME ZONE 'Asia/Colombo')::date=d::date
                GROUP BY d
                ORDER BY d""", r.days(), r.tenant());
    }

    private Map<String, Object> metrics(StateRequest r) {
        var t = r.tenant();
        var days = r.days();
        return s.one("""
                SELECT (SELECT COUNT(*) FROM manufacturing_orders
                        WHERE tenant_id=? AND status NOT IN ('CLOSED','CANCELLED','COMPLETED')) AS active_orders,
                       (SELECT COALESCE(SUM(good_qty),0) FROM production_actuals
                        WHERE tenant_id=? AND created_at>=CURRENT_DATE-(? - 1)) AS output,
                       (SELECT COALESCE(SUM(scrap_qty),0) FROM production_actuals
                        WHERE tenant_id=? AND created_at>=CURRENT_DATE-(? - 1)) AS scrap,
                       (SELECT COUNT(*) FROM inspections WHERE tenant_id=? AND status='PENDING') AS pending_qc,
                       (SELECT COUNT(*) FROM manufacturing_orders
                        WHERE tenant_id=? AND due_date<CURRENT_DATE
                          AND status NOT IN ('CLOSED','CANCELLED','COMPLETED')) AS overdue""", t, t, days, t, days, t, t);
    }
}
