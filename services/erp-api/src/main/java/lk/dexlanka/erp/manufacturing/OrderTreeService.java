package lk.dexlanka.erp.manufacturing;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parent/child manufacturing orders (V7). {@link #createChildren} plans one child order per short semi-finished
 * component of a draft or approved parent: quantity = the shortage ({@link ChildPlanning}), the component's latest
 * approved BOM revision, the parent's (or a chosen) work center, due {@code lead_time_days} before the parent. Children
 * are ordinary draft orders: they need an independent approval, and their released output lots satisfy the parent's
 * normal FIFO reservation.
 */
@Service
public class OrderTreeService {
    private final Store s;
    private final ManufacturingService mo;
    private final ChildPlanning planning;

    OrderTreeService(Store s, ManufacturingService mo, ChildPlanning planning) {
        this.s = s;
        this.mo = mo;
        this.planning = planning;
    }

    @Transactional
    public Map<String, Object> createChildren(
            Actor a, String parentId, ManufacturingCommands.ChildOrders c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "mo-children:" + parentId, c, () -> {
            var parent = mo.order(a, parentId);
            require(
                    ChildPlanning.PLANNABLE.contains(str(parent, "status")),
                    "Child orders can be planned only for a draft or approved order");
            String machine =
                    c.machineId() == null || c.machineId().isBlank() ? str(parent, "machine_id") : c.machineId();
            String priority = c.priority() == null ? str(parent, "priority") : c.priority();
            var shortages = planning.shortages(a.tenant(), parent);
            if (c.itemIds() != null && !c.itemIds().isEmpty()) {
                for (String item : c.itemIds())
                    require(
                            shortages.stream().anyMatch(r -> item.equals(r.get("item_id"))),
                            "Only semi-finished materials of " + str(parent, "number") + " can get child orders");
                shortages = shortages.stream()
                        .filter(r -> c.itemIds().contains(str(r, "item_id")))
                        .toList();
            }
            var created = new ArrayList<Map<String, Object>>();
            var skipped = new ArrayList<Map<String, Object>>();
            for (var r : shortages) {
                if (dec(r.get("shortage")).signum() <= 0) continue;
                String reason = r.get("bom_id") == null
                        ? "no approved BOM revision"
                        : Boolean.TRUE.equals(r.get("active")) ? null : "inactive item";
                if (reason != null) {
                    skipped.add(Map.of("item_id", r.get("item_id"), "sku", r.get("sku"), "reason", reason));
                    continue;
                }
                var child = mo.insert(
                        a,
                        new ManufacturingCommands.Manufacture(
                                str(r, "bom_id"),
                                machine,
                                null,
                                dec(r.get("shortage")),
                                priority,
                                str(r, "suggested_due_date"),
                                null,
                                parentId));
                var summary = new LinkedHashMap<String, Object>();
                summary.put("id", child.get("id"));
                summary.put("number", child.get("number"));
                summary.put("item_id", r.get("item_id"));
                summary.put("sku", r.get("sku"));
                summary.put("quantity", child.get("quantity"));
                summary.put("due_date", str(child, "due_date"));
                created.add(summary);
            }
            require(
                    !created.isEmpty(),
                    skipped.isEmpty()
                            ? "No semi-finished material of this order is short: released stock and open child orders cover it"
                            : "No child order could be planned: "
                                    + String.join(
                                            "; ",
                                            skipped.stream()
                                                    .map(x -> x.get("sku") + " has " + x.get("reason"))
                                                    .toList()));
            s.audit(
                    a,
                    "MO_CHILDREN_CREATED",
                    str(parent, "number"),
                    String.join(
                            ", ",
                            created.stream()
                                    .map(x -> x.get("number") + " (" + x.get("sku") + " × " + x.get("quantity") + ")")
                                    .toList()));
            var result = new LinkedHashMap<String, Object>();
            result.put("parentId", parentId);
            result.put("created", created);
            result.put("skipped", skipped);
            return result;
        });
    }
}
