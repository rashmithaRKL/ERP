package lk.dexlanka.erp.reporting;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/** {@code /api/state} keys for lots and the stock ledger (posted through {@code Store.ledger}). */
@Component
class InventoryStateSections implements StateContributor {
    private final Store s;

    InventoryStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(StateSection.of("lots", 220, this::lots), StateSection.of("ledger", 250, this::ledger));
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

    private List<Map<String, Object>> ledger(StateRequest r) {
        return s.list("""
                SELECT e.*,i.name AS item,l.number AS lot,w.name AS warehouse
                FROM stock_ledger e
                JOIN items i ON i.tenant_id=e.tenant_id AND i.id=e.item_id
                JOIN lots l ON l.tenant_id=e.tenant_id AND l.id=e.lot_id
                JOIN warehouses w ON w.tenant_id=e.tenant_id AND w.id=e.warehouse_id
                WHERE e.tenant_id=?
                ORDER BY e.created_at DESC,e.id
                LIMIT 250""", r.tenant());
    }
}
