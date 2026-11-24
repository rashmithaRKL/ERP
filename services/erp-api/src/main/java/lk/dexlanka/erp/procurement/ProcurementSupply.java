package lk.dexlanka.erp.procurement;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Open purchase supply for material planning: per item, the base quantity still expected on lines of approved
 * ({@code APPROVED}, {@code PARTIAL}) purchase orders, i.e. ordered minus received (QC rejections reopen it, closed
 * and cancelled orders no longer count). {@code reporting.PlanningStateSections} mirrors {@link #OPEN_SUPPLY_SQL} in
 * its {@code incoming} sub-query.
 */
@Component
public class ProcurementSupply {
    /** One parameter: the tenant. Columns {@code item_id}, {@code incoming}. */
    public static final String OPEN_SUPPLY_SQL = """
            SELECT l.item_id,SUM(l.base_quantity-l.received) AS incoming
            FROM purchase_order_lines l
            JOIN purchase_orders po ON po.tenant_id=l.tenant_id AND po.id=l.purchase_order_id
            WHERE l.tenant_id=? AND po.status IN ('APPROVED','PARTIAL')
            GROUP BY l.item_id""";

    private final Store s;

    ProcurementSupply(Store s) {
        this.s = s;
    }

    /** Item id → open base quantity on approved purchase orders (items without open supply are absent). */
    public Map<String, BigDecimal> openSupplyByItem(String tenant) {
        var out = new LinkedHashMap<String, BigDecimal>();
        for (var r : s.list(OPEN_SUPPLY_SQL, tenant))
            if (dec(r.get("incoming")).signum() > 0) out.put(str(r, "item_id"), dec(r.get("incoming")));
        return out;
    }
}
