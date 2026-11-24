package lk.dexlanka.erp.reporting;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Multi-level material requirements planning (V7). Infinite capacity, lot-for-lot, one netting bucket per item.
 *
 * <ol>
 *   <li>Low-level codes: level 0 for end products; a component sits one level below the deepest product that uses it,
 *       through active (latest approved) BOM revisions and open order snapshots.
 *   <li>Gross requirements: the unreserved material snapshot of every DRAFT/APPROVED manufacturing order (need date =
 *       the order's due date), plus the dependent demand of planned production from higher levels.
 *   <li>Netting, level by level: {@code net = gross + safety stock − released unreserved stock − stock in quality
 *       inspection − open manufacturing orders still to produce (child or standalone) − incoming purchase supply}.
 *       Open demand is covered first, in need-date order; safety stock last (a pure safety-stock shortfall is needed
 *       today).
 *   <li>A semi-finished net requirement with an approved BOM becomes planned production: start date = need date − lead
 *       time; its components are required on the start date (their gross demand at the next level). A bought-in net
 *       requirement becomes a purchase recommendation: order date = need date − lead time.
 * </ol>
 *
 * Every requirement is pegged to the orders that drive it (directly, or through planned production above it).
 */
@Component
class MrpPlanner {
    private static final int MAX_LEVEL = 30;

    private final Store s;

    MrpPlanner(Store s) {
        this.s = s;
    }

    /** One gross requirement of an item. */
    record Req(
            BigDecimal quantity,
            LocalDate needDate,
            String source,
            String orderId,
            String orderNumber,
            String orderStatus,
            String productId,
            String productSku,
            String product,
            Set<String> drivers) {}

    record Plan(
            List<Map<String, Object>> rows,
            List<Map<String, Object>> plannedProduction,
            List<Map<String, Object>> purchases,
            LocalDate today) {

        /** {@code /api/state} {@code mrp}: the original columns plus the multi-level ones, without pegging lists. */
        List<Map<String, Object>> stateRows() {
            var out = new ArrayList<Map<String, Object>>();
            for (var r : rows) {
                var copy = new LinkedHashMap<String, Object>(r);
                copy.remove("pegging");
                out.add(copy);
            }
            out.sort(Comparator.comparing((Map<String, Object> r) -> dec(r.get("shortage")))
                    .reversed()
                    .thenComparing(r -> str(r, "name")));
            return out.size() > 250 ? out.subList(0, 250) : out;
        }

        Map<String, Object> response() {
            var summary = new LinkedHashMap<String, Object>();
            summary.put(
                    "levels",
                    rows.stream().mapToInt(r -> (Integer) r.get("level")).max().orElse(0));
            summary.put(
                    "shortages",
                    rows.stream()
                            .filter(r -> dec(r.get("shortage")).signum() > 0)
                            .count());
            summary.put("plannedProduction", plannedProduction.size());
            summary.put("purchases", purchases.size());
            summary.put(
                    "late",
                    plannedProduction.stream()
                                    .filter(r -> Boolean.TRUE.equals(r.get("late")))
                                    .count()
                            + purchases.stream()
                                    .filter(r -> Boolean.TRUE.equals(r.get("late")))
                                    .count());
            var result = new LinkedHashMap<String, Object>();
            result.put("today", today.toString());
            result.put("summary", summary);
            result.put("rows", rows);
            result.put("plannedProduction", plannedProduction);
            result.put("purchases", purchases);
            return result;
        }
    }

    Plan plan(String tenant, Map<String, BigDecimal> incoming) {
        LocalDate today = LocalDate.now();
        var items = new LinkedHashMap<String, Map<String, Object>>();
        for (var r : s.list(
                "SELECT id,sku,name,type,uom,active,reorder_level,reorder_qty,lead_time_days,standard_cost FROM items WHERE tenant_id=? ORDER BY sku",
                tenant)) items.put(str(r, "id"), r);

        // Active (latest approved) revision per product and its lines.
        var activeBom = new HashMap<String, Map<String, Object>>();
        for (var r : s.list("""
                SELECT DISTINCT ON (product_id) id,product_id,revision,output_qty,yield_factor
                FROM boms WHERE tenant_id=? AND status='APPROVED'
                ORDER BY product_id,created_at DESC,revision DESC""", tenant)) activeBom.put(str(r, "product_id"), r);
        var bomLines = new HashMap<String, List<Map<String, Object>>>();
        for (var r : s.list(
                "SELECT l.bom_id,l.item_id,l.quantity FROM bom_lines l JOIN boms b ON b.tenant_id=l.tenant_id AND b.id=l.bom_id WHERE l.tenant_id=? AND b.status='APPROVED'",
                tenant))
            bomLines.computeIfAbsent(str(r, "bom_id"), k -> new ArrayList<>()).add(r);

        // Open (unreserved) order demand.
        var demand = s.list("""
                SELECT om.item_id,om.required_qty,o.id AS order_id,o.number,o.status,o.due_date,o.product_id,
                       p.sku AS product_sku,p.name AS product
                FROM order_materials om
                JOIN manufacturing_orders o ON o.tenant_id=om.tenant_id AND o.id=om.order_id
                JOIN items p ON p.tenant_id=o.tenant_id AND p.id=o.product_id
                WHERE om.tenant_id=? AND o.status IN ('DRAFT','APPROVED')
                ORDER BY o.due_date,o.number""", tenant);

        var stock = new HashMap<String, Map<String, Object>>();
        for (var r : s.list("""
                SELECT l.item_id,
                       SUM(CASE WHEN l.status='RELEASED' THEN b.quantity-b.reserved ELSE 0 END) AS available,
                       SUM(CASE WHEN l.status='QUARANTINE' THEN b.quantity ELSE 0 END) AS inspection
                FROM lots l JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                WHERE l.tenant_id=? GROUP BY l.item_id""", tenant)) stock.put(str(r, "item_id"), r);

        var onOrder = new HashMap<String, BigDecimal>();
        var supplyOrders = new HashMap<String, List<String>>();
        for (var r : s.list("""
                SELECT o.product_id,o.number,GREATEST(0,o.quantity-o.good_qty-o.scrap_qty) AS remaining
                FROM manufacturing_orders o
                WHERE o.tenant_id=? AND o.status NOT IN ('CANCELLED','CLOSED','COMPLETED')
                  AND o.quantity-o.good_qty-o.scrap_qty>0
                ORDER BY o.due_date,o.number""", tenant)) {
            onOrder.merge(str(r, "product_id"), dec(r.get("remaining")), BigDecimal::add);
            supplyOrders
                    .computeIfAbsent(str(r, "product_id"), k -> new ArrayList<>())
                    .add(str(r, "number"));
        }

        // Low-level codes.
        var edges = new ArrayList<String[]>();
        activeBom.forEach((product, bom) -> {
            for (var l : bomLines.getOrDefault(str(bom, "id"), List.of()))
                edges.add(new String[] {product, str(l, "item_id")});
        });
        for (var d : demand) edges.add(new String[] {str(d, "product_id"), str(d, "item_id")});
        var level = new HashMap<String, Integer>();
        items.keySet().forEach(id -> level.put(id, 0));
        for (int pass = 0; pass < MAX_LEVEL; pass++) {
            boolean changed = false;
            for (var e : edges) {
                int candidate = level.getOrDefault(e[0], 0) + 1;
                if (candidate <= MAX_LEVEL && candidate > level.getOrDefault(e[1], 0)) {
                    level.put(e[1], candidate);
                    changed = true;
                }
            }
            if (!changed) break;
        }

        var requirements = new HashMap<String, List<Req>>();
        for (var d : demand)
            requirements
                    .computeIfAbsent(str(d, "item_id"), k -> new ArrayList<>())
                    .add(new Req(
                            dec(d.get("required_qty")),
                            LocalDate.parse(str(d, "due_date")),
                            "ORDER",
                            str(d, "order_id"),
                            str(d, "number"),
                            str(d, "status"),
                            str(d, "product_id"),
                            str(d, "product_sku"),
                            str(d, "product"),
                            new TreeSet<>(Set.of(str(d, "number")))));

        var order = new ArrayList<>(items.keySet());
        order.sort(Comparator.comparing((String id) -> level.getOrDefault(id, 0))
                .thenComparing(id -> str(items.get(id), "sku")));

        var rows = new ArrayList<Map<String, Object>>();
        var planned = new ArrayList<Map<String, Object>>();
        var purchases = new ArrayList<Map<String, Object>>();
        for (String itemId : order) {
            var item = items.get(itemId);
            String type = str(item, "type");
            if ("FINISHED_GOOD".equals(type)) continue;
            boolean active = Boolean.TRUE.equals(item.get("active"));
            String uom = str(item, "uom");
            int lead = ((Number) item.get("lead_time_days")).intValue();
            var reqs = new ArrayList<>(requirements.getOrDefault(itemId, List.of()));
            reqs.sort(Comparator.comparing(Req::needDate)
                    .thenComparing(r -> r.source().equals("ORDER") ? 0 : 1)
                    .thenComparing(r -> r.orderNumber() == null ? r.productSku() : r.orderNumber()));
            BigDecimal independent = sum(reqs, "ORDER");
            BigDecimal dependent = sum(reqs, "PLANNED");
            BigDecimal gross = independent.add(dependent);
            BigDecimal safety = active ? dec(item.get("reorder_level")) : BigDecimal.ZERO;
            var st = stock.getOrDefault(itemId, Map.of());
            BigDecimal available = dec(st.get("available"));
            BigDecimal inspection = dec(st.get("inspection"));
            BigDecimal mo = onOrder.getOrDefault(itemId, BigDecimal.ZERO);
            BigDecimal po = incoming.getOrDefault(itemId, BigDecimal.ZERO);
            BigDecimal supply = available.add(inspection).add(mo).add(po);
            BigDecimal shortage = gross.add(safety).subtract(supply).max(BigDecimal.ZERO);
            if ("pcs".equals(uom)) shortage = shortage.setScale(0, RoundingMode.CEILING);

            // Pegging: open demand first (need-date order), safety stock last.
            BigDecimal pool = supply;
            LocalDate need = null;
            var drivers = new TreeSet<String>();
            var parents = new LinkedHashMap<String, Map<String, Object>>();
            var pegging = new ArrayList<Map<String, Object>>();
            for (var r : reqs) {
                BigDecimal covered = pool.max(BigDecimal.ZERO).min(r.quantity());
                BigDecimal uncovered = r.quantity().subtract(covered);
                pool = pool.subtract(r.quantity());
                if (uncovered.signum() > 0) {
                    if (need == null) need = r.needDate();
                    drivers.addAll(r.drivers());
                    if (r.source().equals("ORDER")
                            && Set.of("DRAFT", "APPROVED").contains(r.orderStatus()))
                        parents.putIfAbsent(
                                r.orderId(),
                                Map.of(
                                        "order_id",
                                        r.orderId(),
                                        "order_number",
                                        r.orderNumber(),
                                        "status",
                                        r.orderStatus(),
                                        "due_date",
                                        r.needDate().toString()));
                }
                pegging.add(peg(r, uncovered));
            }
            // What is left after open demand does not cover the safety stock.
            boolean safetyShort = shortage.signum() > 0 && safety.signum() > 0 && pool.compareTo(safety) < 0;
            if (shortage.signum() > 0 && need == null) need = today;

            var bom = activeBom.get(itemId);
            String action = "NONE";
            if (shortage.signum() > 0) {
                if (!active) action = "REVIEW";
                else if ("SEMI_FINISHED".equals(type)) action = bom == null ? "NO_BOM" : "PRODUCE";
                else action = "PURCHASE";
            }
            LocalDate start = need == null ? null : need.minusDays(lead);
            boolean late = start != null && start.isBefore(today);

            var row = new LinkedHashMap<String, Object>();
            row.put("id", itemId);
            row.put("sku", item.get("sku"));
            row.put("name", item.get("name"));
            row.put("uom", uom);
            row.put("type", type);
            row.put("active", active);
            row.put("reorder_level", item.get("reorder_level"));
            row.put("reorder_qty", item.get("reorder_qty"));
            row.put("lead_time_days", lead);
            row.put("level", level.getOrDefault(itemId, 0));
            row.put("gross", gross);
            row.put("independent_demand", independent);
            row.put("dependent_demand", dependent);
            row.put("available", available);
            row.put("inspection", inspection);
            row.put("incoming", po);
            row.put("on_order", mo);
            row.put("safety", safety);
            row.put("shortage", shortage);
            row.put("action", action);
            row.put("bom_id", bom == null ? null : bom.get("id"));
            row.put("bom_revision", bom == null ? null : bom.get("revision"));
            row.put("need_date", need == null ? null : need.toString());
            row.put("start_date", start == null ? null : start.toString());
            row.put("late", late);
            row.put("drivers", String.join(", ", drivers));
            row.put("safety_shortfall", safetyShort);
            row.put("supply_orders", String.join(", ", supplyOrders.getOrDefault(itemId, List.of())));
            row.put("pegging", pegging);
            rows.add(row);

            if (action.equals("PRODUCE")) {
                var p = recommendation(row, shortage, drivers, late);
                p.put("start_date", start.toString());
                p.put("bom_id", bom.get("id"));
                p.put("bom_revision", bom.get("revision"));
                p.put("parent_orders", List.copyOf(parents.values()));
                planned.add(p);
                // Dependent demand one level down, required when the planned production starts.
                BigDecimal base = dec(bom.get("output_qty")).multiply(dec(bom.get("yield_factor")));
                for (var l : bomLines.getOrDefault(str(bom, "id"), List.of())) {
                    var component = items.get(str(l, "item_id"));
                    if (component == null) continue;
                    BigDecimal qty = dec(l.get("quantity"))
                            .multiply(shortage)
                            .divide(base, "pcs".equals(component.get("uom")) ? 0 : 6, RoundingMode.CEILING);
                    requirements
                            .computeIfAbsent(str(l, "item_id"), k -> new ArrayList<>())
                            .add(new Req(
                                    qty,
                                    start,
                                    "PLANNED",
                                    null,
                                    null,
                                    null,
                                    itemId,
                                    str(item, "sku"),
                                    str(item, "name"),
                                    drivers.isEmpty() ? new TreeSet<>(Set.of("safety stock")) : drivers));
                }
            } else if (action.equals("PURCHASE") || action.equals("NO_BOM")) {
                var p = recommendation(row, shortage, drivers, late);
                p.put("order_date", start.toString());
                p.put("shortage", shortage);
                p.put("reorder_qty", item.get("reorder_qty"));
                purchases.add(p);
            }
        }
        return new Plan(rows, planned, purchases, today);
    }

    private static BigDecimal sum(List<Req> reqs, String source) {
        return reqs.stream()
                .filter(r -> r.source().equals(source))
                .map(Req::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, Object> peg(Req r, BigDecimal uncovered) {
        var p = new LinkedHashMap<String, Object>();
        p.put("source", r.source());
        p.put("order_id", r.orderId());
        p.put("order_number", r.orderNumber());
        p.put("order_status", r.orderStatus());
        p.put("product_id", r.productId());
        p.put("product_sku", r.productSku());
        p.put("product", r.product());
        p.put("quantity", r.quantity());
        p.put("uncovered", uncovered);
        p.put("need_date", r.needDate().toString());
        p.put("drivers", List.copyOf(new LinkedHashSet<>(r.drivers())));
        return p;
    }

    private static LinkedHashMap<String, Object> recommendation(
            Map<String, Object> row, BigDecimal quantity, Set<String> drivers, boolean late) {
        var p = new LinkedHashMap<String, Object>();
        p.put("id", row.get("id"));
        p.put("item_id", row.get("id"));
        p.put("sku", row.get("sku"));
        p.put("name", row.get("name"));
        p.put("uom", row.get("uom"));
        p.put("type", row.get("type"));
        p.put("level", row.get("level"));
        p.put("lead_time_days", row.get("lead_time_days"));
        p.put("quantity", quantity);
        p.put("need_date", row.get("need_date"));
        p.put("late", late);
        p.put("drivers", List.copyOf(drivers));
        p.put("safety_shortfall", row.get("safety_shortfall"));
        return p;
    }
}
