package lk.dexlanka.erp.catalog;

import java.util.List;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/**
 * {@code /api/state} key of the multi-level BOM feature (V7, order range 1700-1799): {@code bomCosts}, the standard-cost
 * roll-up of every revision (per-unit cost per level, total, depth, semi-finished components without an approved BOM).
 */
@Component
class BomStateSections implements StateContributor {
    private final BomQuery query;

    BomStateSections(BomQuery query) {
        this.query = query;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("bomCosts", 1700, r -> query.rollups(r.tenant())));
    }
}
