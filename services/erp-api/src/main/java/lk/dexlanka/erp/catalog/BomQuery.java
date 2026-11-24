package lk.dexlanka.erp.catalog;

import static lk.dexlanka.erp.catalog.BomGraph.plain;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Multi-level BOM read model (V7): indented explosion, multi-level where-used, revision comparison and standard-cost
 * roll-up. Readable by ADMIN, PLANNER and WAREHOUSE. Every response is computed from one {@link BomGraph} snapshot.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class BomQuery {
    private static final String[] READERS = {"PLANNER", "WAREHOUSE"};
    private static final int MAX_NODES = 2000;

    private final Store s;

    BomQuery(Store s) {
        this.s = s;
    }

    // ------------------------------------------------------------------ explosion (downward)

    /**
     * Indented explosion of a revision for {@code quantity} units of its product (default: the base output). Each level
     * uses its own revision's output and yield and the snapshot rounding; semi-finished components expand through their
     * active (latest approved) revision.
     */
    public Map<String, Object> explode(Actor a, String bomId, BigDecimal quantity) {
        a.allow(READERS);
        var g = BomGraph.load(s, a.tenant());
        var bom = bom(g, bomId);
        return explosion(g, bom, quantity == null ? bom.outputQty() : quantity);
    }

    /** Explosion of a product's active (latest approved) revision. */
    public Map<String, Object> explodeProduct(Actor a, String productId, BigDecimal quantity) {
        a.allow(READERS);
        var g = BomGraph.load(s, a.tenant());
        if (!g.items.containsKey(productId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found in your organization");
        var bom = g.active.get(productId);
        require(bom != null, g.sku(productId) + " has no approved BOM revision");
        return explosion(g, bom, quantity == null ? bom.outputQty() : quantity);
    }

    private Map<String, Object> explosion(BomGraph g, BomGraph.Bom bom, BigDecimal quantity) {
        require(quantity.signum() > 0, "Enter a quantity greater than zero");
        var product = g.item(bom.productId());
        s.quantity(product.uom(), quantity);
        var warnings = new LinkedHashSet<String>();
        var leaves = new TreeMap<String, BigDecimal[]>(); // item id -> {quantity, cost}
        int[] counters = {0, 0}; // nodes, depth
        var path = new HashSet<String>();
        path.add(product.id());
        var children = explodeLines(g, bom, quantity, 1, path, warnings, leaves, counters);
        BigDecimal cost =
                children.stream().map(n -> (BigDecimal) n.get("extendedCost")).reduce(BigDecimal.ZERO, BigDecimal::add);
        var root = node(product, 0, quantity, quantity, BigDecimal.ONE);
        root.put("bomId", bom.id());
        root.put("revision", bom.revision());
        root.put("status", bom.status());
        root.put("outputQty", plain(bom.outputQty()));
        root.put("yieldFactor", plain(bom.yieldFactor()));
        root.put("extendedCost", money(cost));
        root.put("children", children);

        var materials = new ArrayList<Map<String, Object>>();
        leaves.forEach((itemId, v) -> {
            var item = g.item(itemId);
            var m = new LinkedHashMap<String, Object>();
            m.put("itemId", itemId);
            m.put("sku", item.sku());
            m.put("name", item.name());
            m.put("type", item.type());
            m.put("uom", item.uom());
            m.put("quantity", plain(v[0]));
            m.put("extendedCost", money(v[1]));
            materials.add(m);
        });
        materials.sort((x, y) -> String.valueOf(x.get("sku")).compareTo(String.valueOf(y.get("sku"))));

        var result = new LinkedHashMap<String, Object>();
        result.put("bom", header(g, bom));
        result.put("quantity", plain(quantity));
        result.put("depth", counters[1]);
        result.put("materialCost", money(cost));
        result.put("unitCost", money(cost.divide(quantity, BomGraph.SCALE, RoundingMode.HALF_UP)));
        result.put("root", root);
        result.put("materials", materials);
        result.put("warnings", List.copyOf(warnings));
        return result;
    }

    private List<Map<String, Object>> explodeLines(
            BomGraph g,
            BomGraph.Bom bom,
            BigDecimal parentQty,
            int level,
            Set<String> path,
            Set<String> warnings,
            Map<String, BigDecimal[]> leaves,
            int[] counters) {
        var nodes = new ArrayList<Map<String, Object>>();
        for (var line : g.lines(bom.id())) {
            var item = g.item(line.itemId());
            if (item == null) continue;
            require(++counters[0] <= MAX_NODES, "This structure is too large to explode in one request");
            counters[1] = Math.max(counters[1], level);
            BigDecimal exact =
                    line.quantity().multiply(parentQty).divide(bom.outputBase(), BomGraph.SCALE, RoundingMode.HALF_UP);
            BigDecimal required = BomGraph.requirement(line, item.uom(), parentQty, bom);
            var n = node(item, level, required, exact, BomGraph.perUnit(line, bom));
            n.put("lineQty", plain(line.quantity()));
            var sub = g.subBom(item.id());
            boolean cycle = sub != null && path.contains(item.id());
            if (cycle) warnings.add("Cycle at " + item.sku() + ": the structure below it is not expanded");
            if (sub != null && !cycle && level < BomGraph.MAX_DEPTH) {
                path.add(item.id());
                var children = explodeLines(g, sub, required, level + 1, path, warnings, leaves, counters);
                path.remove(item.id());
                n.put("bomId", sub.id());
                n.put("revision", sub.revision());
                n.put("outputQty", plain(sub.outputQty()));
                n.put("yieldFactor", plain(sub.yieldFactor()));
                n.put(
                        "extendedCost",
                        money(children.stream()
                                .map(c -> (BigDecimal) c.get("extendedCost"))
                                .reduce(BigDecimal.ZERO, BigDecimal::add)));
                n.put("children", children);
            } else {
                if (sub != null && !cycle)
                    warnings.add("Deeper than " + BomGraph.MAX_DEPTH + " levels below " + item.sku());
                if (sub == null && "SEMI_FINISHED".equals(item.type())) {
                    n.put("missingBom", true);
                    warnings.add(item.sku() + " is semi-finished but has no approved BOM; valued at standard cost");
                }
                if (cycle) n.put("cycle", true);
                BigDecimal cost = required.multiply(item.standardCost());
                n.put("extendedCost", money(cost));
                n.put("children", List.of());
                var leaf = leaves.computeIfAbsent(item.id(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                leaf[0] = leaf[0].add(required);
                leaf[1] = leaf[1].add(cost);
            }
            nodes.add(n);
        }
        return nodes;
    }

    private static LinkedHashMap<String, Object> node(
            BomGraph.Item item, int level, BigDecimal quantity, BigDecimal exact, BigDecimal perUnit) {
        var n = new LinkedHashMap<String, Object>();
        n.put("level", level);
        n.put("itemId", item.id());
        n.put("sku", item.sku());
        n.put("name", item.name());
        n.put("type", item.type());
        n.put("uom", item.uom());
        n.put("active", item.active());
        n.put("quantity", plain(quantity));
        n.put("exactQuantity", plain(exact));
        n.put("perUnit", plain(perUnit));
        n.put("standardCost", plain(item.standardCost()));
        n.put("leadTimeDays", item.leadTimeDays());
        return n;
    }

    // ------------------------------------------------------------------ where-used (upward)

    /**
     * Multi-level where-used: every revision (draft or approved) that consumes the item, then every revision that consumes
     * those products, up to the end products.
     */
    public Map<String, Object> whereUsed(Actor a, String itemId) {
        a.allow(READERS);
        var g = BomGraph.load(s, a.tenant());
        var item = g.item(itemId);
        if (item == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found in your organization");
        var usedBy = new java.util.HashMap<String, List<BomGraph.Line>>();
        g.lines
                .values()
                .forEach(ls -> ls.forEach(l -> usedBy.computeIfAbsent(l.itemId(), k -> new ArrayList<>())
                        .add(l)));
        var ends = new LinkedHashMap<String, Map<String, Object>>();
        int[] counters = {0, 0};
        var path = new HashSet<String>();
        path.add(itemId);
        var parents = parents(g, usedBy, itemId, 1, path, ends, counters);
        var result = new LinkedHashMap<String, Object>();
        var head = new LinkedHashMap<String, Object>();
        head.put("id", item.id());
        head.put("sku", item.sku());
        head.put("name", item.name());
        head.put("type", item.type());
        head.put("uom", item.uom());
        result.put("item", head);
        result.put("depth", counters[1]);
        result.put("parents", parents);
        result.put("endProducts", List.copyOf(ends.values()));
        return result;
    }

    private List<Map<String, Object>> parents(
            BomGraph g,
            Map<String, List<BomGraph.Line>> usedBy,
            String itemId,
            int level,
            Set<String> path,
            Map<String, Map<String, Object>> ends,
            int[] counters) {
        var nodes = new ArrayList<Map<String, Object>>();
        var uses = new ArrayList<>(usedBy.getOrDefault(itemId, List.of()));
        uses.sort((x, y) -> {
            var bx = g.boms.get(x.bomId());
            var by = g.boms.get(y.bomId());
            int c = g.sku(bx.productId()).compareTo(g.sku(by.productId()));
            return c != 0 ? c : by.revision().compareTo(bx.revision());
        });
        for (var line : uses) {
            require(++counters[0] <= MAX_NODES, "This structure is too large to show in one request");
            counters[1] = Math.max(counters[1], level);
            var bom = g.boms.get(line.bomId());
            var product = g.item(bom.productId());
            var n = new LinkedHashMap<String, Object>();
            n.put("level", level);
            n.put("bomId", bom.id());
            n.put("revision", bom.revision());
            n.put("status", bom.status());
            n.put("active", bom.approved() && g.active.get(bom.productId()) == bom);
            n.put("productId", product.id());
            n.put("sku", product.sku());
            n.put("name", product.name());
            n.put("type", product.type());
            n.put("uom", product.uom());
            n.put("productActive", product.active());
            n.put("lineQty", plain(line.quantity()));
            n.put("outputQty", plain(bom.outputQty()));
            n.put("yieldFactor", plain(bom.yieldFactor()));
            n.put("perUnit", plain(BomGraph.perUnit(line, bom)));
            boolean cycle = path.contains(product.id());
            List<Map<String, Object>> above = List.of();
            if (cycle) n.put("cycle", true);
            else if (level < BomGraph.MAX_DEPTH) {
                path.add(product.id());
                above = parents(g, usedBy, product.id(), level + 1, path, ends, counters);
                path.remove(product.id());
            }
            n.put("parents", above);
            if (above.isEmpty() && !cycle) {
                var end = new LinkedHashMap<String, Object>();
                end.put("productId", product.id());
                end.put("sku", product.sku());
                end.put("name", product.name());
                end.put("type", product.type());
                ends.putIfAbsent(product.id(), end);
            }
            nodes.add(n);
        }
        return nodes;
    }

    // ------------------------------------------------------------------ compare

    /**
     * Component differences between two revisions of the same product. Quantities are compared per unit of product
     * (output and yield applied), so a changed base output alone is not reported as a component change; costs use the
     * rolled-up standard cost of each component.
     */
    public Map<String, Object> compare(Actor a, String fromId, String toId) {
        a.allow(READERS);
        var g = BomGraph.load(s, a.tenant());
        var from = bom(g, fromId);
        var to = bom(g, toId);
        require(from.productId().equals(to.productId()), "Compare two revisions of the same product");
        var fromLines = byItem(g.lines(from.id()));
        var toLines = byItem(g.lines(to.id()));
        var union = new LinkedHashSet<String>(fromLines.keySet());
        union.addAll(toLines.keySet());
        var ids = new ArrayList<>(union);
        ids.sort((x, y) -> g.sku(x).compareTo(g.sku(y)));
        var rows = new ArrayList<Map<String, Object>>();
        var counts = new LinkedHashMap<String, Integer>();
        for (String change : List.of("ADDED", "REMOVED", "CHANGED", "UNCHANGED")) counts.put(change, 0);
        BigDecimal fromTotal = BigDecimal.ZERO;
        BigDecimal toTotal = BigDecimal.ZERO;
        var unitCosts = new java.util.HashMap<String, BigDecimal>();
        for (String itemId : ids) {
            var item = g.item(itemId);
            var fl = fromLines.get(itemId);
            var tl = toLines.get(itemId);
            BigDecimal unitCost = unitCosts.computeIfAbsent(itemId, k -> componentCost(g, item));
            BigDecimal fromPer = fl == null ? BigDecimal.ZERO : BomGraph.perUnit(fl, from);
            BigDecimal toPer = tl == null ? BigDecimal.ZERO : BomGraph.perUnit(tl, to);
            BigDecimal fromCost = fromPer.multiply(unitCost);
            BigDecimal toCost = toPer.multiply(unitCost);
            fromTotal = fromTotal.add(fromCost);
            toTotal = toTotal.add(toCost);
            String change = fl == null
                    ? "ADDED"
                    : tl == null ? "REMOVED" : fromPer.compareTo(toPer) == 0 ? "UNCHANGED" : "CHANGED";
            counts.merge(change, 1, Integer::sum);
            var r = new LinkedHashMap<String, Object>();
            r.put("itemId", itemId);
            r.put("sku", item.sku());
            r.put("name", item.name());
            r.put("type", item.type());
            r.put("uom", item.uom());
            r.put("change", change);
            r.put("fromQty", fl == null ? null : plain(fl.quantity()));
            r.put("toQty", tl == null ? null : plain(tl.quantity()));
            r.put("fromPerUnit", plain(fromPer));
            r.put("toPerUnit", plain(toPer));
            r.put("perUnitDelta", plain(toPer.subtract(fromPer)));
            r.put("unitCost", money(unitCost));
            r.put("fromCost", money(fromCost));
            r.put("toCost", money(toCost));
            r.put("costDelta", money(toCost.subtract(fromCost)));
            rows.add(r);
        }
        var summary = new LinkedHashMap<String, Object>(counts);
        summary.put("fromUnitCost", money(fromTotal));
        summary.put("toUnitCost", money(toTotal));
        summary.put("unitCostDelta", money(toTotal.subtract(fromTotal)));
        summary.put("outputChanged", from.outputQty().compareTo(to.outputQty()) != 0);
        summary.put("yieldChanged", from.yieldFactor().compareTo(to.yieldFactor()) != 0);
        var result = new LinkedHashMap<String, Object>();
        result.put("from", header(g, from));
        result.put("to", header(g, to));
        result.put("lines", rows);
        result.put("summary", summary);
        return result;
    }

    private static Map<String, BomGraph.Line> byItem(List<BomGraph.Line> lines) {
        var map = new LinkedHashMap<String, BomGraph.Line>();
        for (var l : lines) map.put(l.itemId(), l);
        return map;
    }

    /** Standard cost of one unit of a component: rolled up through its active revision when it has one. */
    private static BigDecimal componentCost(BomGraph g, BomGraph.Item item) {
        var sub = g.subBom(item.id());
        return sub == null ? item.standardCost() : g.rollup(sub).unitCost();
    }

    // ------------------------------------------------------------------ cost roll-up

    /** Standard-cost roll-up of one revision: per-unit cost by level, total, and each direct component's share. */
    public Map<String, Object> cost(Actor a, String bomId) {
        a.allow(READERS);
        var g = BomGraph.load(s, a.tenant());
        var bom = bom(g, bomId);
        var rollup = g.rollup(bom);
        var components = new ArrayList<Map<String, Object>>();
        for (var line : g.lines(bom.id())) {
            var item = g.item(line.itemId());
            if (item == null) continue;
            var sub = g.subBom(item.id());
            BigDecimal unitCost = componentCost(g, item);
            BigDecimal per = BomGraph.perUnit(line, bom);
            var c = new LinkedHashMap<String, Object>();
            c.put("itemId", item.id());
            c.put("sku", item.sku());
            c.put("name", item.name());
            c.put("type", item.type());
            c.put("uom", item.uom());
            c.put("perUnit", plain(per));
            c.put("standardCost", money(item.standardCost()));
            c.put("unitCost", money(unitCost));
            c.put("rolledUp", sub != null);
            c.put("bomId", sub == null ? null : sub.id());
            c.put("revision", sub == null ? null : sub.revision());
            c.put("cost", money(per.multiply(unitCost)));
            components.add(c);
        }
        var result = new LinkedHashMap<String, Object>(header(g, bom));
        result.put("standardCost", money(g.item(bom.productId()).standardCost()));
        result.putAll(rollupMap(rollup));
        result.put("components", components);
        return result;
    }

    /** {@code /api/state} {@code bomCosts}: the roll-up of every revision (BOM cards). */
    public List<Map<String, Object>> rollups(String tenant) {
        var g = BomGraph.load(s, tenant);
        var rows = new ArrayList<Map<String, Object>>();
        for (var bom : g.boms.values()) {
            if (rows.size() >= 250) break;
            var r = new LinkedHashMap<String, Object>();
            r.put("id", bom.id());
            r.put("bom_id", bom.id());
            r.putAll(rollupMap(g.rollup(bom)));
            r.put("active", bom.approved() && g.active.get(bom.productId()) == bom);
            r.put("source_bom_id", bom.sourceBomId());
            r.put(
                    "source_revision",
                    bom.sourceBomId() == null || !g.boms.containsKey(bom.sourceBomId())
                            ? null
                            : g.boms.get(bom.sourceBomId()).revision());
            r.put(
                    "semi_finished",
                    g.lines(bom.id()).stream()
                            .filter(l -> g.item(l.itemId()) != null
                                    && "SEMI_FINISHED".equals(g.item(l.itemId()).type()))
                            .count());
            rows.add(r);
        }
        return rows;
    }

    private static Map<String, Object> rollupMap(BomGraph.Rollup r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("unitCost", r.unitCost());
        m.put("singleLevelCost", r.singleLevelCost());
        var levels = new ArrayList<Map<String, Object>>();
        r.levelCosts().forEach((level, cost) -> levels.add(Map.of("level", level, "cost", cost)));
        m.put("levels", levels);
        m.put("depth", r.depth());
        m.put("missingBoms", r.missingBoms());
        m.put("cycle", r.cycle());
        m.put("truncated", r.truncated());
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private static BomGraph.Bom bom(BomGraph g, String id) {
        var bom = g.boms.get(id);
        if (bom == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found in your organization");
        return bom;
    }

    private static Map<String, Object> header(BomGraph g, BomGraph.Bom bom) {
        var product = g.item(bom.productId());
        var h = new LinkedHashMap<String, Object>();
        h.put("bomId", bom.id());
        h.put("revision", bom.revision());
        h.put("status", bom.status());
        h.put("productId", product.id());
        h.put("sku", product.sku());
        h.put("name", product.name());
        h.put("type", product.type());
        h.put("uom", product.uom());
        h.put("outputQty", plain(bom.outputQty()));
        h.put("yieldFactor", plain(bom.yieldFactor()));
        h.put("sourceBomId", bom.sourceBomId());
        return h;
    }
}
