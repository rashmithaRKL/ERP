package lk.dexlanka.erp.manufacturing;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/manufacturing/{id}/tree} (V7): an order with its ancestors (root first), its descendants (nested
 * child orders with status and progress) and its semi-finished shortages. Readable by every signed-in role, like the
 * order details.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class OrderTreeQuery {
    private static final int MAX_DEPTH = 12;
    private static final String COLUMNS = """
            o.id,o.number,o.status,o.product_id,o.parent_order_id,o.quantity,o.good_qty,o.scrap_qty,o.due_date,
            o.priority,o.machine_id,o.bom_revision,o.created_at,i.name AS product,i.sku,i.uom,i.type,m.code AS machine
            """;
    private static final String JOINS = """
            JOIN items i ON i.tenant_id=o.tenant_id AND i.id=o.product_id
            JOIN machines m ON m.tenant_id=o.tenant_id AND m.id=o.machine_id
            """;

    private final Store s;
    private final ChildPlanning planning;

    OrderTreeQuery(Store s, ChildPlanning planning) {
        this.s = s;
        this.planning = planning;
    }

    public Map<String, Object> tree(Actor a, String id) {
        var order = s.one(
                "SELECT " + COLUMNS + " FROM manufacturing_orders o " + JOINS + " WHERE o.tenant_id=? AND o.id=?",
                a.tenant(),
                id);
        var ancestors = new ArrayList<Map<String, Object>>();
        var cursor = order;
        while (cursor.get("parent_order_id") != null && ancestors.size() < MAX_DEPTH) {
            cursor = s.one(
                    "SELECT " + COLUMNS + " FROM manufacturing_orders o " + JOINS + " WHERE o.tenant_id=? AND o.id=?",
                    a.tenant(),
                    cursor.get("parent_order_id"));
            ancestors.addFirst(node(cursor));
        }
        var descendants = s.list("""
                WITH RECURSIVE tree AS (
                    SELECT o.id,1 AS depth FROM manufacturing_orders o WHERE o.tenant_id=? AND o.parent_order_id=?
                    UNION ALL
                    SELECT o.id,t.depth+1 FROM manufacturing_orders o JOIN tree t ON o.parent_order_id=t.id
                    WHERE o.tenant_id=? AND t.depth<?)
                SELECT t.depth,""" + COLUMNS + """
                FROM tree t JOIN manufacturing_orders o ON o.tenant_id=? AND o.id=t.id
                """ + JOINS + """
                ORDER BY t.depth,o.due_date,o.number""", a.tenant(), id, a.tenant(), MAX_DEPTH, a.tenant());
        var byParent = new HashMap<String, List<Map<String, Object>>>();
        for (var d : descendants) {
            var n = node(d);
            n.put("depth", d.get("depth"));
            byParent.computeIfAbsent(str(d, "parent_order_id"), k -> new ArrayList<>())
                    .add(n);
        }
        var shortages = planning.shortages(a.tenant(), order);
        var result = new LinkedHashMap<String, Object>();
        result.put("order", node(order));
        result.put("parent", ancestors.isEmpty() ? null : ancestors.getLast());
        result.put("ancestors", ancestors);
        result.put("children", nest(byParent, id));
        result.put("descendants", descendants.size());
        result.put("shortages", shortages);
        result.put("canPlanChildren", shortages.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("plannable"))));
        return result;
    }

    private static List<Map<String, Object>> nest(Map<String, List<Map<String, Object>>> byParent, String parent) {
        var children = byParent.getOrDefault(parent, List.of());
        for (var child : children) child.put("children", nest(byParent, str(child, "id")));
        return children;
    }

    private static Map<String, Object> node(Map<String, Object> row) {
        var n = new LinkedHashMap<String, Object>(row);
        n.remove("depth");
        n.put("due_date", str(row, "due_date"));
        BigDecimal quantity = dec(row.get("quantity"));
        BigDecimal done = dec(row.get("good_qty")).add(dec(row.get("scrap_qty")));
        n.put(
                "progress",
                quantity.signum() == 0
                        ? BigDecimal.ZERO
                        : done.multiply(BigDecimal.valueOf(100)).divide(quantity, 1, RoundingMode.HALF_UP));
        return n;
    }
}
