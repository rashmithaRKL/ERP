package lk.dexlanka.erp.performance;

import java.util.List;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/**
 * {@code /api/state} key {@code performance} (order 2500): yesterday's measured plant OEE with A, P, Q and the time and
 * output coverage, for the overview; {@code null} when no work center has a measured OEE for that day. The Performance
 * page reads {@code /api/performance/**}.
 */
@Component
class PerformanceStateSections implements StateContributor {
    private final PerformanceQuery query;

    PerformanceStateSections(PerformanceQuery query) {
        this.query = query;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("performance", 2500, r -> query.yesterday(r.tenant())));
    }
}
