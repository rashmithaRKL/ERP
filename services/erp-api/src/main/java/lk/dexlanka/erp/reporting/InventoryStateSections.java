package lk.dexlanka.erp.reporting;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * {@code /api/state} key for lots. (The {@code ledger} key was removed in V9; the stock ledger is read server-paged from
 * {@code GET /api/inventory/ledger}.)
 */
@Component
class InventoryStateSections implements StateContributor {
    private final Store s;

    InventoryStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("lots", 220, this::lots));
    }

    private List<Map<String, Object>> lots(StateRequest r) {
        return s.list("""
                SELECT l.*,i.name AS item,i.sku,i.uom,b.quantity,b.reserved,b.value_amount,w.name AS warehouse
                FROM lots l
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                JOIN warehouses w ON w.tenant_id=l.tenant_id AND w.id=l.warehouse_id
                WHERE l.tenant_id=?
                ORDER BY l.created_at DESC
                LIMIT 250""", r.tenant());
    }
}
