package lk.dexlanka.erp.reporting;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Material requirements planning in {@code /api/state} ({@code mrp}) and for {@code GET /api/planning/mrp}.
 *
 * <p>V7: multi-level. Open order demand explodes through semi-finished levels into planned production and purchase
 * recommendations ({@link MrpPlanner}). Covers every non-finished item type; inactive items keep their open demand but
 * no longer ask for safety stock. The {@code mrp} rows keep their original columns ({@code gross}, {@code available},
 * {@code incoming}, {@code shortage}, ...) and add the multi-level ones ({@code level}, {@code on_order},
 * {@code inspection}, {@code action}, {@code need_date}, ...).
 */
@Component
class PlanningStateSections implements StateContributor {
    private final Store s;
    private final MrpPlanner planner;

    PlanningStateSections(Store s, MrpPlanner planner) {
        this.s = s;
        this.planner = planner;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("mrp", 260, r -> plan(r.tenant()).stateRows()));
    }

    /** The tenant's multi-level plan; runs inside the caller's read-only snapshot. */
    MrpPlanner.Plan plan(String tenant) {
        return planner.plan(tenant, incomingPurchaseSupply(tenant));
    }

    /**
     * Incoming purchase supply per item: the quantity still to be received on approved and partially received purchase
     * orders (V6 header + lines; mirrors procurement.ProcurementSupply.OPEN_SUPPLY_SQL). This is the only place planning
     * reads purchase orders.
     */
    Map<String, BigDecimal> incomingPurchaseSupply(String tenant) {
        var supply = new HashMap<String, BigDecimal>();
        for (var r : s.list("""
                SELECT l.item_id,SUM(l.base_quantity-l.received) AS incoming
                FROM purchase_order_lines l
                JOIN purchase_orders po ON po.tenant_id=l.tenant_id AND po.id=l.purchase_order_id
                WHERE l.tenant_id=? AND po.status IN ('APPROVED','PARTIAL')
                GROUP BY l.item_id""", tenant)) supply.put(str(r, "item_id"), dec(r.get("incoming")));
        return supply;
    }
}
