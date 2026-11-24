package lk.dexlanka.erp.reporting;

import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/planning/mrp} (V7): the multi-level plan with levels, pegging, planned production and purchase
 * recommendations. ADMIN, PLANNER and WAREHOUSE (the Material planning page).
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PlanningQuery {
    private final PlanningStateSections planning;

    PlanningQuery(PlanningStateSections planning) {
        this.planning = planning;
    }

    public Map<String, Object> mrp(Actor a) {
        a.allow("PLANNER", "WAREHOUSE");
        return planning.plan(a.tenant()).response();
    }
}
