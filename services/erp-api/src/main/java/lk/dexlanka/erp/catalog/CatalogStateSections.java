package lk.dexlanka.erp.catalog;

import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.reporting.StateContributor;
import lk.dexlanka.erp.reporting.StateRequest;
import lk.dexlanka.erp.reporting.StateSection;
import org.springframework.stereotype.Component;

/**
 * {@code /api/state} keys owned by the catalog: organization, plant, partners, work centers, items and BOMs. (The
 * {@code users} key was removed in V9; administrators read {@code GET /api/admin/users}.)
 */
@Component
class CatalogStateSections implements StateContributor {
    private final Store s;

    CatalogStateSections(Store s) {
        this.s = s;
    }

    @Override
    public List<StateSection> sections() {
        return List.of(
                StateSection.of("company", 100, this::company),
                StateSection.of("factories", 120, this::factories),
                StateSection.of("warehouses", 130, this::warehouses),
                StateSection.of("partners", 140, this::partners),
                StateSection.of("machines", 150, this::machines),
                StateSection.of("items", 160, this::items),
                StateSection.of("boms", 180, this::boms),
                StateSection.of("bomLines", 190, this::bomLines));
    }

    private Map<String, Object> company(StateRequest r) {
        return s.one("SELECT id,name,currency,demo FROM tenants WHERE id=?", r.tenant());
    }

    private List<Map<String, Object>> factories(StateRequest r) {
        return s.list("SELECT * FROM factories WHERE tenant_id=? ORDER BY name", r.tenant());
    }

    private List<Map<String, Object>> warehouses(StateRequest r) {
        return s.list("SELECT * FROM warehouses WHERE tenant_id=? ORDER BY name", r.tenant());
    }

    private List<Map<String, Object>> partners(StateRequest r) {
        return s.list("SELECT * FROM partners WHERE tenant_id=? ORDER BY name LIMIT 250", r.tenant());
    }

    private List<Map<String, Object>> machines(StateRequest r) {
        return s.list("""
                SELECT m.*,
                       COALESCE((SELECT SUM(o.good_qty) FROM manufacturing_orders o
                                 WHERE o.tenant_id=m.tenant_id AND o.machine_id=m.id),0) AS output
                FROM machines m
                WHERE m.tenant_id=?
                ORDER BY code""", r.tenant());
    }

    private List<Map<String, Object>> items(StateRequest r) {
        return s.list("""
                SELECT i.*,
                       COALESCE(SUM(b.quantity),0) AS on_hand,
                       COALESCE(SUM(b.reserved),0) AS reserved,
                       COALESCE(SUM(CASE WHEN l.status='RELEASED' THEN b.quantity-b.reserved ELSE 0 END),0) AS available,
                       COALESCE(SUM(b.value_amount),0) AS stock_value
                FROM items i
                LEFT JOIN lots l ON l.tenant_id=i.tenant_id AND l.item_id=i.id
                LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                WHERE i.tenant_id=?
                GROUP BY i.id
                ORDER BY i.sku
                LIMIT 250""", r.tenant());
    }

    private List<Map<String, Object>> boms(StateRequest r) {
        return s.list("""
                SELECT b.*,i.name AS product,i.sku,
                       (SELECT COALESCE(SUM(bl.quantity*mi.standard_cost),0)
                        FROM bom_lines bl
                        JOIN items mi ON mi.tenant_id=bl.tenant_id AND mi.id=bl.item_id
                        WHERE bl.tenant_id=b.tenant_id AND bl.bom_id=b.id)/b.output_qty/b.yield_factor AS unit_cost
                FROM boms b
                JOIN items i ON i.tenant_id=b.tenant_id AND i.id=b.product_id
                WHERE b.tenant_id=?
                ORDER BY i.name,b.revision DESC
                LIMIT 250""", r.tenant());
    }

    private List<Map<String, Object>> bomLines(StateRequest r) {
        return s.list("""
                SELECT l.*,i.name,i.sku,i.uom,i.standard_cost
                FROM bom_lines l
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                WHERE l.tenant_id=?
                ORDER BY l.bom_id,i.name
                LIMIT 1000""", r.tenant());
    }
}
