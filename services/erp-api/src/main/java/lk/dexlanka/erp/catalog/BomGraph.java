package lk.dexlanka.erp.catalog;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Store;

/**
 * The multi-level BOM structure of one tenant, loaded in four queries: items, revisions, lines and each product's
 * active revision (its latest approved one). A {@code SEMI_FINISHED} component whose active revision exists is the next
 * level down. Shared by the approval rules ({@link BomService}) and the read model ({@link BomQuery}).
 *
 * <p>Quantities follow the manufacturing snapshot exactly: a component requirement is {@code line × parent quantity /
 * (output × yield)}, rounded up to whole pieces for {@code pcs} and to 6 decimals otherwise, at every level.
 */
final class BomGraph {
    /** Deeper structures are truncated (and flagged) rather than followed. */
    static final int MAX_DEPTH = 12;

    static final int SCALE = 12;

    record Item(
            String id,
            String sku,
            String name,
            String type,
            String uom,
            boolean active,
            BigDecimal standardCost,
            int leadTimeDays) {}

    record Bom(
            String id,
            String productId,
            String revision,
            String status,
            BigDecimal outputQty,
            BigDecimal yieldFactor,
            String sourceBomId,
            Object createdAt) {
        BigDecimal outputBase() {
            return outputQty.multiply(yieldFactor);
        }

        boolean approved() {
            return "APPROVED".equals(status);
        }
    }

    record Line(String bomId, String itemId, BigDecimal quantity) {}

    final Map<String, Item> items = new HashMap<>();
    final Map<String, Bom> boms = new LinkedHashMap<>();
    final Map<String, List<Line>> lines = new HashMap<>();
    /** product id -> latest approved revision. */
    final Map<String, Bom> active = new HashMap<>();
    /** product id -> every approved revision (cycle checks consider all of them). */
    final Map<String, List<Bom>> approvedByProduct = new HashMap<>();

    private BomGraph() {}

    static BomGraph load(Store s, String tenant) {
        var g = new BomGraph();
        for (var r : s.list(
                "SELECT id,sku,name,type,uom,active,standard_cost,lead_time_days FROM items WHERE tenant_id=?", tenant))
            g.items.put(
                    str(r, "id"),
                    new Item(
                            str(r, "id"),
                            str(r, "sku"),
                            str(r, "name"),
                            str(r, "type"),
                            str(r, "uom"),
                            Boolean.TRUE.equals(r.get("active")),
                            dec(r.get("standard_cost")),
                            ((Number) r.get("lead_time_days")).intValue()));
        for (var r : s.list(
                "SELECT id,product_id,revision,status,output_qty,yield_factor,source_bom_id,created_at FROM boms WHERE tenant_id=? ORDER BY product_id,created_at DESC,revision DESC",
                tenant)) {
            var b = new Bom(
                    str(r, "id"),
                    str(r, "product_id"),
                    str(r, "revision"),
                    str(r, "status"),
                    dec(r.get("output_qty")),
                    dec(r.get("yield_factor")),
                    (String) r.get("source_bom_id"),
                    r.get("created_at"));
            g.boms.put(b.id(), b);
            if (b.approved()) {
                g.active.putIfAbsent(b.productId(), b); // rows are newest first
                g.approvedByProduct
                        .computeIfAbsent(b.productId(), k -> new ArrayList<>())
                        .add(b);
            }
        }
        for (var r : s.list(
                "SELECT l.bom_id,l.item_id,l.quantity FROM bom_lines l JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id WHERE l.tenant_id=? ORDER BY l.bom_id,i.sku",
                tenant))
            g.lines
                    .computeIfAbsent(str(r, "bom_id"), k -> new ArrayList<>())
                    .add(new Line(str(r, "bom_id"), str(r, "item_id"), dec(r.get("quantity"))));
        return g;
    }

    List<Line> lines(String bomId) {
        return lines.getOrDefault(bomId, List.of());
    }

    Item item(String id) {
        return items.get(id);
    }

    /** The revision used to explode {@code itemId} one level further, or null for bought-in items. */
    Bom subBom(String itemId) {
        var item = items.get(itemId);
        if (item == null || !("SEMI_FINISHED".equals(item.type()) || "FINISHED_GOOD".equals(item.type()))) return null;
        return active.get(itemId);
    }

    /** Component requirement for {@code parentQty} units of the BOM's product (the manufacturing snapshot rule). */
    static BigDecimal requirement(Line line, String uom, BigDecimal parentQty, Bom bom) {
        return line.quantity()
                .multiply(parentQty)
                .divide(bom.outputBase(), "pcs".equals(uom) ? 0 : 6, RoundingMode.CEILING);
    }

    /** Exact component quantity per one unit of the BOM's product (output and yield applied, no rounding). */
    static BigDecimal perUnit(Line line, Bom bom) {
        return line.quantity().divide(bom.outputBase(), SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal plain(BigDecimal value) {
        var stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /**
     * A path of approved revisions from {@code from} down to {@code target} (product ids, both included), or null. Every
     * approved revision counts, since any of them can still be used for a manufacturing order.
     */
    List<String> approvedPath(String from, String target) {
        return approvedPath(from, target, new java.util.HashSet<>(), 0);
    }

    private List<String> approvedPath(String from, String target, Set<String> seen, int depth) {
        if (from.equals(target)) return new ArrayList<>(List.of(from));
        if (depth > 64 || !seen.add(from)) return null;
        for (var bom : approvedByProduct.getOrDefault(from, List.of())) {
            for (var line : lines(bom.id())) {
                var rest = approvedPath(line.itemId(), target, seen, depth + 1);
                if (rest != null) {
                    rest.addFirst(from);
                    return rest;
                }
            }
        }
        return null;
    }

    String sku(String itemId) {
        var item = items.get(itemId);
        return item == null ? itemId : item.sku();
    }

    // ------------------------------------------------------------------ standard-cost roll-up

    /** Rolled-up standard material cost of one unit of a revision's product. */
    record Rollup(
            BigDecimal unitCost,
            BigDecimal singleLevelCost,
            Map<Integer, BigDecimal> levelCosts,
            int depth,
            List<String> missingBoms,
            boolean cycle,
            boolean truncated) {}

    /**
     * Standard-cost roll-up through all levels: bought-in components at their standard cost, semi-finished components
     * with an approved revision at that revision's rolled-up cost, semi-finished components without one at their item
     * standard cost (listed in {@code missingBoms}). {@code levelCosts} is the cost of the bought-in materials consumed
     * at each level, per unit of the top product; they add up to {@code unitCost}.
     */
    Rollup rollup(Bom bom) {
        var levels = new java.util.TreeMap<Integer, BigDecimal>();
        var missing = new java.util.TreeSet<String>();
        boolean[] flags = new boolean[2]; // cycle, truncated
        int[] depth = {0};
        var path = new java.util.HashSet<String>();
        path.add(bom.productId());
        accumulate(bom, BigDecimal.ONE, 1, path, levels, missing, flags, depth);
        BigDecimal total = levels.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal single = BigDecimal.ZERO;
        for (var line : lines(bom.id())) {
            var item = items.get(line.itemId());
            if (item != null) single = single.add(perUnit(line, bom).multiply(item.standardCost()));
        }
        var rounded = new LinkedHashMap<Integer, BigDecimal>();
        levels.forEach((k, v) -> rounded.put(k, Store.money(v)));
        return new Rollup(
                Store.money(total), Store.money(single), rounded, depth[0], List.copyOf(missing), flags[0], flags[1]);
    }

    private void accumulate(
            Bom bom,
            BigDecimal factor,
            int level,
            Set<String> path,
            Map<Integer, BigDecimal> levels,
            Set<String> missing,
            boolean[] flags,
            int[] depth) {
        depth[0] = Math.max(depth[0], level);
        for (var line : lines(bom.id())) {
            var item = items.get(line.itemId());
            if (item == null) continue;
            BigDecimal qty = factor.multiply(perUnit(line, bom)).setScale(SCALE, RoundingMode.HALF_UP);
            var sub = subBom(item.id());
            boolean cycle = sub != null && path.contains(item.id());
            if (cycle) flags[0] = true;
            if (sub != null && !cycle && level < MAX_DEPTH) {
                path.add(item.id());
                accumulate(sub, qty, level + 1, path, levels, missing, flags, depth);
                path.remove(item.id());
            } else {
                if (sub != null && level >= MAX_DEPTH) flags[1] = true;
                if (sub == null && "SEMI_FINISHED".equals(item.type())) missing.add(item.sku());
                levels.merge(level, qty.multiply(item.standardCost()), BigDecimal::add);
            }
        }
    }
}
