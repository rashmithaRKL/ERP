package lk.dexlanka.erp.traceability;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The genealogy (trace) engine of V14 and the one public read service recalls use.
 *
 * <p>Lots are linked through the {@code lot_genealogy_links} view (material issued to a manufacturing order → its output
 * lots, transfers, completed rework, customer returns). {@link #forward} and {@link #backward} walk that view with a
 * recursive CTE that keeps one row per (lot, depth): duplicates are discarded by {@code UNION}, so the walk is
 * cycle-safe and bounded by lots × depth even when paths converge. {@link #trace} turns the reached lots into a graph of
 * lots and documents (receipts, manufacturing and rework orders, deliveries, terminal outflows such as scrap, supplier
 * returns, maintenance issues and stock adjustments) with quantities and dates on every edge, plus an indented tree.
 *
 * <p>Every query is scoped by the actor's tenant. The closure methods carry no transaction of their own: they run in
 * the caller's transaction (a recall command) or in {@link #trace}'s read-only snapshot.
 */
@Service
public class TraceService {
    /** Default lot-to-lot hops shown per direction. */
    public static final int DEFAULT_DEPTH = 12;
    /** Largest depth a caller may ask for. */
    public static final int MAX_DEPTH = 30;
    /** Most lots one direction of a trace returns; beyond that the result is marked truncated. */
    public static final int MAX_LOTS = 2000;

    static final List<String> ROOT_TYPES = List.of("LOT", "MO", "RECEIPT", "PO", "SO", "DELIVERY", "SUPPLIER");

    private final Store s;

    TraceService(Store s) {
        this.s = s;
    }

    /** Lots reached from the seeds with their smallest depth (seeds = 0); {@code truncated} when capped. */
    public record Closure(Map<String, Integer> lots, boolean truncated) {
        public Set<String> ids() {
            return lots.keySet();
        }
    }

    // ------------------------------------------------------------------ closures (no own transaction)

    /** The seeds and every lot made from them (material flow direction), at most {@code depth} hops away. */
    public Closure forward(String tenant, Collection<String> seeds, int depth) {
        return closure(tenant, seeds, depth, true);
    }

    /** The seeds and every lot they were made from, at most {@code depth} hops away. */
    public Closure backward(String tenant, Collection<String> seeds, int depth) {
        return closure(tenant, seeds, depth, false);
    }

    /**
     * The origin lots of a backward closure: lots without any incoming genealogy link (purchased, opening or found
     * stock, or produced without recorded material).
     */
    public List<String> origins(String tenant, Collection<String> lots) {
        if (lots.isEmpty()) return List.of();
        return s
                .list(
                        """
                        SELECT l.id FROM lots l
                        WHERE l.tenant_id=? AND l.id IN (SELECT jsonb_array_elements_text(?::jsonb))
                          AND NOT EXISTS (SELECT 1 FROM lot_genealogy_links g
                                          WHERE g.tenant_id=l.tenant_id AND g.to_lot_id=l.id)
                        ORDER BY l.created_at,l.id""",
                        tenant,
                        s.encode(lots))
                .stream()
                .map(r -> str(r, "id"))
                .toList();
    }

    /** Lots received from {@code supplier} (optionally one item) on Asia/Colombo dates {@code from}..{@code to}. */
    public List<String> supplierLots(String tenant, String supplier, String item, LocalDate from, LocalDate to) {
        return s
                .list(
                        """
                        SELECT r.lot_id,MIN(r.created_at) AS received_at FROM receipts r
                        JOIN purchase_orders po ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id
                        JOIN lots l ON l.tenant_id=r.tenant_id AND l.id=r.lot_id
                        WHERE r.tenant_id=? AND po.supplier_id=? AND (?::text IS NULL OR l.item_id=?)
                          AND r.created_at >= (?::date)::timestamp AT TIME ZONE 'Asia/Colombo'
                          AND r.created_at < (?::date + 1)::timestamp AT TIME ZONE 'Asia/Colombo'
                        GROUP BY r.lot_id ORDER BY MIN(r.created_at),r.lot_id""",
                        tenant,
                        supplier,
                        item,
                        item,
                        from.toString(),
                        to.toString())
                .stream()
                .map(r -> str(r, "lot_id"))
                .toList();
    }

    private Closure closure(String tenant, Collection<String> seeds, int depth, boolean forward) {
        if (seeds.isEmpty()) return new Closure(Map.of(), false);
        String next = forward ? "g.to_lot_id" : "g.from_lot_id";
        String join = forward ? "g.from_lot_id=w.lot_id" : "g.to_lot_id=w.lot_id";
        var rows = s.list(
                """
                WITH RECURSIVE walk(lot_id,depth) AS (
                    SELECT l.id,0 FROM lots l
                    WHERE l.tenant_id=? AND l.id IN (SELECT jsonb_array_elements_text(?::jsonb))
                  UNION
                    SELECT %s,w.depth+1 FROM walk w
                    JOIN lot_genealogy_links g ON g.tenant_id=? AND %s
                    WHERE w.depth < ?
                )
                SELECT lot_id,MIN(depth) AS depth FROM walk GROUP BY lot_id ORDER BY MIN(depth),lot_id LIMIT ?"""
                        .formatted(next, join),
                tenant,
                s.encode(seeds),
                tenant,
                depth,
                MAX_LOTS + 1);
        var lots = new LinkedHashMap<String, Integer>();
        for (var r : rows.subList(0, Math.min(rows.size(), MAX_LOTS)))
            lots.put(str(r, "lot_id"), ((Number) r.get("depth")).intValue());
        return new Closure(lots, rows.size() > MAX_LOTS);
    }

    // ------------------------------------------------------------------ search

    /** Entry points matching {@code q}: lots, manufacturing orders, goods receipts, purchase and sales orders, deliveries, suppliers. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> search(Actor a, String q) {
        String text = q == null ? "" : q.strip();
        require(text.length() >= 2, "Type at least two characters to search");
        String like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        var items = new ArrayList<Map<String, Object>>();
        String t = a.tenant();
        for (var r : s.list(
                """
                SELECT l.id,l.number,l.status,i.sku,i.name AS item FROM lots l
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                WHERE l.tenant_id=? AND l.number ILIKE ? ORDER BY l.number LIMIT 8""",
                t,
                like))
            items.add(hit("LOT", str(r, "id"), str(r, "number"), r.get("sku") + " · " + r.get("item") + " · "
                    + str(r, "status").toLowerCase(Locale.ROOT)));
        for (var r : s.list(
                """
                SELECT m.id,m.number,m.status,i.sku,i.name AS item FROM manufacturing_orders m
                JOIN items i ON i.tenant_id=m.tenant_id AND i.id=m.product_id
                WHERE m.tenant_id=? AND m.number ILIKE ? ORDER BY m.created_at DESC LIMIT 6""",
                t,
                like))
            items.add(hit("MO", str(r, "id"), str(r, "number"), r.get("sku") + " · " + r.get("item")));
        for (var r : s.list(
                """
                SELECT r.number,MIN(po.number) AS po,MIN(p.name) AS supplier,COUNT(*) AS lines FROM receipts r
                JOIN purchase_orders po ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id
                LEFT JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id
                WHERE r.tenant_id=? AND r.number ILIKE ? GROUP BY r.number ORDER BY r.number DESC LIMIT 6""",
                t,
                like))
            items.add(hit("RECEIPT", str(r, "number"), str(r, "number"), r.get("po") + " · " + r.get("supplier")));
        for (var r : s.list(
                """
                SELECT po.id,po.number,p.name AS supplier FROM purchase_orders po
                LEFT JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id
                WHERE po.tenant_id=? AND po.number ILIKE ? AND po.status NOT IN ('DRAFT','SUBMITTED','CANCELLED')
                ORDER BY po.created_at DESC LIMIT 6""",
                t,
                like))
            items.add(hit("PO", str(r, "id"), str(r, "number"), str(r, "supplier")));
        for (var r : s.list(
                """
                SELECT so.id,so.number,p.name AS customer FROM sales_orders so
                JOIN partners p ON p.tenant_id=so.tenant_id AND p.id=so.customer_id
                WHERE so.tenant_id=? AND (so.number ILIKE ? OR so.customer_reference ILIKE ?)
                  AND so.status NOT IN ('DRAFT','CANCELLED')
                ORDER BY so.created_at DESC LIMIT 6""",
                t,
                like,
                like))
            items.add(hit("SO", str(r, "id"), str(r, "number"), str(r, "customer")));
        for (var r : s.list(
                """
                SELECT d.reference,MIN(so.number) AS so,MIN(p.name) AS customer FROM deliveries d
                JOIN sales_orders so ON so.tenant_id=d.tenant_id AND so.id=d.sales_order_id
                JOIN partners p ON p.tenant_id=so.tenant_id AND p.id=so.customer_id
                WHERE d.tenant_id=? AND d.reference ILIKE ? GROUP BY d.reference ORDER BY MAX(d.created_at) DESC
                LIMIT 6""",
                t,
                like))
            items.add(hit("DELIVERY", str(r, "reference"), str(r, "reference"), r.get("so") + " · " + r.get("customer")));
        for (var r : s.list(
                """
                SELECT id,name FROM partners WHERE tenant_id=? AND type='SUPPLIER' AND name ILIKE ?
                ORDER BY name LIMIT 6""",
                t,
                like))
            items.add(hit("SUPPLIER", str(r, "id"), str(r, "name"), "Supplier · lots received in a date range"));
        return Map.of("items", items);
    }

    private static Map<String, Object> hit(String type, String id, String label, String detail) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("id", id);
        m.put("label", label);
        m.put("detail", detail);
        return m;
    }

    // ------------------------------------------------------------------ trace

    /**
     * The genealogy graph around one entry point.
     *
     * @param type LOT, MO, RECEIPT (goods-receipt number), PO, SO, DELIVERY (delivery reference) or SUPPLIER
     * @param direction BOTH (default), BACKWARD or FORWARD
     * @param depth lot-to-lot hops per direction (1–{@value #MAX_DEPTH}, default {@value #DEFAULT_DEPTH})
     * @param from first receipt date (SUPPLIER only, Asia/Colombo; default 90 days before {@code to})
     * @param to last receipt date (SUPPLIER only; default today)
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> trace(
            Actor a, String type, String id, String direction, Integer depth, String from, String to) {
        String kind = type == null ? "LOT" : type.strip().toUpperCase(Locale.ROOT);
        require(ROOT_TYPES.contains(kind), "Trace from a lot, MO, receipt, PO, SO, delivery or supplier");
        require(id != null && !id.isBlank(), "Choose what to trace");
        String dir = direction == null || direction.isBlank() ? "BOTH" : direction.strip().toUpperCase(Locale.ROOT);
        require(List.of("BOTH", "BACKWARD", "FORWARD").contains(dir), "Direction is BOTH, BACKWARD or FORWARD");
        int hops = depth == null ? DEFAULT_DEPTH : depth;
        require(hops >= 1 && hops <= MAX_DEPTH, "Choose a depth between 1 and " + MAX_DEPTH);
        var g = new Graph(a.tenant());
        var root = root(g, a.tenant(), kind, id.strip(), from, to);
        boolean back = !dir.equals("FORWARD");
        boolean fwd = !dir.equals("BACKWARD");
        if (back) expandBackward(g, root.backwardSeeds(), hops);
        if (fwd) expandForward(g, root.forwardSeeds(), hops);
        g.loadLots();
        g.dropDangling();
        var levels = g.levels(root.nodeId(), back, fwd);
        var nodes = new ArrayList<Map<String, Object>>();
        for (var n : g.nodes.values()) {
            Integer level = levels.get(str(n, "id"));
            if (level == null) continue;
            n.put("level", level);
            n.put("side", level == 0 ? "ROOT" : level < 0 ? "BACKWARD" : "FORWARD");
            nodes.add(n);
        }
        var kept = new HashSet<String>(levels.keySet());
        var edges = g.edges.stream()
                .filter(e -> kept.contains(str(e, "from")) && kept.contains(str(e, "to")))
                .toList();
        var out = new LinkedHashMap<String, Object>();
        out.put("root", g.nodes.get(root.nodeId()));
        out.put("direction", dir);
        out.put("depth", hops);
        out.put("truncated", g.truncated);
        out.put("nodes", nodes);
        out.put("edges", edges);
        var tree = new LinkedHashMap<String, Object>();
        tree.put("backward", back ? tree(root.nodeId(), edges, false) : List.of());
        tree.put("forward", fwd ? tree(root.nodeId(), edges, true) : List.of());
        out.put("tree", tree);
        out.put("summary", summary(nodes, edges));
        return out;
    }

    private record Root(String nodeId, Set<String> backwardSeeds, Set<String> forwardSeeds) {}

    private Root root(Graph g, String t, String kind, String id, String from, String to) {
        switch (kind) {
            case "LOT" -> {
                var lot = s.one("SELECT id FROM lots WHERE tenant_id=? AND (id=? OR number=?) LIMIT 1", t, id, id);
                String lotId = str(lot, "id");
                g.lot(lotId);
                return new Root("lot:" + lotId, Set.of(lotId), Set.of(lotId));
            }
            case "MO" -> {
                var mo = s.one("SELECT id FROM manufacturing_orders WHERE tenant_id=? AND (id=? OR number=?)", t, id, id);
                String moId = str(mo, "id");
                g.orders(List.of(moId));
                var back = new LinkedHashSet<String>();
                for (var r : g.issuesToOrders(List.of(moId))) back.add(str(r, "lot_id"));
                var fwd = new LinkedHashSet<String>();
                for (var r : g.orderOutputs(List.of(moId))) fwd.add(str(r, "lot_id"));
                return new Root("mo:" + moId, back, fwd);
            }
            case "RECEIPT", "PO", "SUPPLIER" -> {
                String supplierFilter = null;
                LocalDate first = null;
                LocalDate last = null;
                String nodeId;
                if (kind.equals("SUPPLIER")) {
                    var p = s.one("SELECT * FROM partners WHERE tenant_id=? AND id=?", t, id);
                    require("SUPPLIER".equals(p.get("type")), "Choose a supplier, not a customer");
                    last = date(to, LocalDate.now(java.time.ZoneId.of("Asia/Colombo")));
                    first = date(from, last.minusDays(90));
                    require(!first.isAfter(last), "The first receipt date must not be after the last one");
                    supplierFilter = id;
                    nodeId = "supplier:" + id;
                    var n = g.node(nodeId, "SUPPLIER", str(p, "name"), "Receipts " + first + " – " + last);
                    n.put("ref", id);
                    n.put("from", first.toString());
                    n.put("to", last.toString());
                } else if (kind.equals("PO")) {
                    var po = s.one(
                            """
                            SELECT po.id,po.number,po.status,p.id AS supplier_id,p.name AS supplier FROM purchase_orders po
                            LEFT JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id
                            WHERE po.tenant_id=? AND (po.id=? OR po.number=?)""",
                            t,
                            id,
                            id);
                    nodeId = "po:" + po.get("id");
                    var n = g.node(nodeId, "PURCHASE_ORDER", str(po, "number"), str(po, "supplier"));
                    n.put("ref", po.get("id"));
                    n.put("status", po.get("status"));
                    n.put("supplierId", po.get("supplier_id"));
                    n.put("supplier", po.get("supplier"));
                    id = str(po, "id");
                } else {
                    var grn = s.one(
                            """
                            SELECT r.number,MIN(r.created_at) AS created_at,MIN(po.id) AS po_id,MIN(po.number) AS po,
                                   MIN(p.id) AS supplier_id,MIN(p.name) AS supplier,MIN(r.delivery_note) AS delivery_note
                            FROM receipts r
                            JOIN purchase_orders po ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id
                            LEFT JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id
                            WHERE r.tenant_id=? AND (r.number=? OR r.id=?) GROUP BY r.number""",
                            t,
                            id,
                            id);
                    nodeId = "grn:" + grn.get("number");
                    var n = g.node(nodeId, "GOODS_RECEIPT", str(grn, "number"), grn.get("po") + " · " + grn.get("supplier"));
                    n.put("ref", grn.get("number"));
                    n.put("at", grn.get("created_at"));
                    n.put("purchaseOrderId", grn.get("po_id"));
                    n.put("purchaseOrder", grn.get("po"));
                    n.put("supplierId", grn.get("supplier_id"));
                    n.put("supplier", grn.get("supplier"));
                    n.put("deliveryNote", grn.get("delivery_note"));
                    id = str(grn, "number");
                }
                var seeds = new LinkedHashSet<String>();
                for (var r : s.list(
                        """
                        SELECT r.id,r.lot_id,r.number,r.quantity,r.created_at,i.uom FROM receipts r
                        JOIN purchase_orders po ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id
                        JOIN lots l ON l.tenant_id=r.tenant_id AND l.id=r.lot_id
                        JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                        WHERE r.tenant_id=?
                          AND (?::text IS NULL OR r.number=?)
                          AND (?::text IS NULL OR r.purchase_order_id=?)
                          AND (?::text IS NULL OR (po.supplier_id=?
                               AND r.created_at >= (?::date)::timestamp AT TIME ZONE 'Asia/Colombo'
                               AND r.created_at < (?::date + 1)::timestamp AT TIME ZONE 'Asia/Colombo'))
                        ORDER BY r.created_at,r.id LIMIT ?""",
                        t,
                        kind.equals("RECEIPT") ? id : null,
                        kind.equals("RECEIPT") ? id : null,
                        kind.equals("PO") ? id : null,
                        kind.equals("PO") ? id : null,
                        supplierFilter,
                        supplierFilter,
                        first == null ? null : first.toString(),
                        last == null ? null : last.toString(),
                        MAX_LOTS)) {
                    String lot = str(r, "lot_id");
                    seeds.add(lot);
                    g.lot(lot);
                    g.edge(nodeId, "lot:" + lot, "RECEIVED", r.get("quantity"), r.get("uom"), r.get("created_at"), str(r, "number"));
                }
                return new Root(nodeId, Set.of(), seeds);
            }
            default -> {
                // SO or DELIVERY reference: delivered lots behind, customer-return lots ahead.
                String nodeId;
                String soId = null;
                String reference = null;
                if (kind.equals("SO")) {
                    var so = s.one(
                            """
                            SELECT so.id,so.number,so.status,p.id AS customer_id,p.name AS customer FROM sales_orders so
                            JOIN partners p ON p.tenant_id=so.tenant_id AND p.id=so.customer_id
                            WHERE so.tenant_id=? AND (so.id=? OR so.number=?)""",
                            t,
                            id,
                            id);
                    soId = str(so, "id");
                    nodeId = "so:" + soId;
                    var n = g.node(nodeId, "SALES_ORDER", str(so, "number"), str(so, "customer"));
                    n.put("ref", soId);
                    n.put("status", so.get("status"));
                    n.put("customerId", so.get("customer_id"));
                    n.put("customer", so.get("customer"));
                } else {
                    var d = s.one(
                            """
                            SELECT d.reference,MIN(d.created_at) AS created_at,MIN(so.id) AS so_id,MIN(so.number) AS so,
                                   MIN(p.id) AS customer_id,MIN(p.name) AS customer
                            FROM deliveries d
                            JOIN sales_orders so ON so.tenant_id=d.tenant_id AND so.id=d.sales_order_id
                            JOIN partners p ON p.tenant_id=so.tenant_id AND p.id=so.customer_id
                            WHERE d.tenant_id=? AND (d.reference=? OR d.id=?) GROUP BY d.reference""",
                            t,
                            id,
                            id);
                    reference = str(d, "reference");
                    nodeId = "dref:" + reference;
                    var n = g.node(nodeId, "DELIVERY_NOTE", reference, d.get("so") + " · " + d.get("customer"));
                    n.put("ref", reference);
                    n.put("at", d.get("created_at"));
                    n.put("salesOrderId", d.get("so_id"));
                    n.put("salesOrder", d.get("so"));
                    n.put("customerId", d.get("customer_id"));
                    n.put("customer", d.get("customer"));
                }
                var back = new LinkedHashSet<String>();
                var fwd = new LinkedHashSet<String>();
                for (var r : s.list(
                        """
                        SELECT d.id,d.lot_id,d.quantity,d.reference,d.created_at,i.uom FROM deliveries d
                        JOIN lots l ON l.tenant_id=d.tenant_id AND l.id=d.lot_id
                        JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                        WHERE d.tenant_id=? AND (?::text IS NULL OR d.sales_order_id=?) AND (?::text IS NULL OR d.reference=?)
                        ORDER BY d.created_at,d.id LIMIT ?""",
                        t,
                        soId,
                        soId,
                        reference,
                        reference,
                        MAX_LOTS)) {
                    String lot = str(r, "lot_id");
                    back.add(lot);
                    g.lot(lot);
                    g.edge("lot:" + lot, nodeId, "DELIVERED", r.get("quantity"), r.get("uom"), r.get("created_at"), str(r, "reference"));
                }
                for (var r : s.list(
                        """
                        SELECT cr.lot_id,cr.number,cr.quantity,cr.created_at,i.uom FROM customer_returns cr
                        JOIN deliveries d ON d.tenant_id=cr.tenant_id AND d.id=cr.delivery_id
                        JOIN lots l ON l.tenant_id=cr.tenant_id AND l.id=cr.lot_id
                        JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                        WHERE cr.tenant_id=? AND (?::text IS NULL OR cr.sales_order_id=?) AND (?::text IS NULL OR d.reference=?)
                        ORDER BY cr.created_at,cr.id""",
                        t,
                        soId,
                        soId,
                        reference,
                        reference)) {
                    String lot = str(r, "lot_id");
                    fwd.add(lot);
                    g.lot(lot);
                    g.edge(nodeId, "lot:" + lot, "RETURNED", r.get("quantity"), r.get("uom"), r.get("created_at"), str(r, "number"));
                }
                return new Root(nodeId, back, fwd);
            }
        }
    }

    private static LocalDate date(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value.strip());
        } catch (java.time.DateTimeException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Use yyyy-MM-dd dates");
        }
    }

    // ------------------------------------------------------------------ expansion

    /** Origins of every reached lot: receipts, producing orders (with their issued lots), transfers, rework, returns. */
    private void expandBackward(Graph g, Set<String> seeds, int depth) {
        var closure = backward(g.tenant, seeds, depth);
        if (closure.truncated()) g.truncated = true;
        var inner = new ArrayList<String>();
        for (var e : closure.lots().entrySet()) {
            g.lot(e.getKey());
            if (e.getValue() < depth) inner.add(e.getKey());
            else g.frontier(e.getKey(), false);
        }
        if (inner.isEmpty()) return;
        String t = g.tenant;
        String lots = s.encode(inner);
        var sources = s.list(
                "SELECT id,source_type,source_id FROM lots WHERE tenant_id=? AND id IN (SELECT jsonb_array_elements_text(?::jsonb))",
                t,
                lots);
        var orders = new LinkedHashSet<String>();
        var reworks = new LinkedHashSet<String>();
        var others = new ArrayList<Map<String, Object>>();
        for (var r : sources) {
            switch (str(r, "source_type")) {
                case "MANUFACTURING" -> orders.add(str(r, "source_id"));
                case "REWORK" -> reworks.add(str(r, "source_id"));
                case "PURCHASE", "TRANSFER", "CUSTOMER_RETURN" -> {}
                default -> others.add(r);
            }
        }
        // Purchase receipts.
        for (var r : s.list(
                """
                SELECT r.id,r.lot_id,r.number,r.quantity,r.created_at,r.delivery_note,po.id AS po_id,po.number AS po,
                       p.id AS supplier_id,p.name AS supplier,i.uom
                FROM receipts r
                JOIN purchase_orders po ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id
                LEFT JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id
                JOIN lots l ON l.tenant_id=r.tenant_id AND l.id=r.lot_id
                JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                WHERE r.tenant_id=? AND r.lot_id IN (SELECT jsonb_array_elements_text(?::jsonb))""",
                t,
                lots)) {
            String nodeId = "receipt:" + r.get("id");
            var n = g.node(nodeId, "RECEIPT", str(r, "number"), r.get("po") + " · " + r.get("supplier"));
            n.put("ref", r.get("number"));
            n.put("at", r.get("created_at"));
            n.put("purchaseOrderId", r.get("po_id"));
            n.put("purchaseOrder", r.get("po"));
            n.put("supplierId", r.get("supplier_id"));
            n.put("supplier", r.get("supplier"));
            n.put("deliveryNote", r.get("delivery_note"));
            g.edge(nodeId, "lot:" + r.get("lot_id"), "RECEIVED", r.get("quantity"), r.get("uom"), r.get("created_at"), str(r, "number"));
        }
        // Manufacturing orders: output edge into the lot and the material issued to the order.
        if (!orders.isEmpty()) {
            g.orders(orders);
            g.orderOutputs(orders);
            g.issuesToOrders(orders);
        }
        // Transfers into the lots.
        for (var r : s.list(
                """
                SELECT tl.source_lot_id,tl.destination_lot_id,tl.quantity,t.number,t.posted_at,i.uom
                FROM stock_transfer_lines tl
                JOIN stock_transfers t ON t.tenant_id=tl.tenant_id AND t.id=tl.transfer_id
                JOIN items i ON i.tenant_id=tl.tenant_id AND i.id=tl.item_id
                WHERE tl.tenant_id=? AND tl.destination_lot_id IN (SELECT jsonb_array_elements_text(?::jsonb))""",
                t,
                lots))
            g.edge(
                    "lot:" + r.get("source_lot_id"),
                    "lot:" + r.get("destination_lot_id"),
                    "TRANSFERRED",
                    r.get("quantity"),
                    r.get("uom"),
                    r.get("posted_at"),
                    str(r, "number"));
        // Rework orders that produced the lots, with their input and material lots.
        if (!reworks.isEmpty()) {
            g.reworks(reworks);
            g.reworkInputs(reworks);
        }
        // Customer returns: delivered lot -> delivery -> return lot.
        g.deliveriesWithReturns("cr.lot_id IN (SELECT jsonb_array_elements_text(?::jsonb))", lots);
        // Opening, found or other stock without a source document.
        g.origins(others);
    }

    /** Everything made from, moved out of or shipped from every reached lot. */
    private void expandForward(Graph g, Set<String> seeds, int depth) {
        var closure = forward(g.tenant, seeds, depth);
        if (closure.truncated()) g.truncated = true;
        var inner = new ArrayList<String>();
        for (var e : closure.lots().entrySet()) {
            g.lot(e.getKey());
            if (e.getValue() < depth) inner.add(e.getKey());
            else g.frontier(e.getKey(), true);
        }
        if (inner.isEmpty()) return;
        String t = g.tenant;
        String lots = s.encode(inner);
        // Material issued to manufacturing orders and the orders' output lots.
        var orders = new LinkedHashSet<String>();
        for (var r : s.list(
                "SELECT DISTINCT order_id FROM reservations WHERE tenant_id=? AND status='ISSUED' AND lot_id IN (SELECT jsonb_array_elements_text(?::jsonb))",
                t,
                lots))
            orders.add(str(r, "order_id"));
        if (!orders.isEmpty()) {
            g.orders(orders);
            g.issues(lots);
            g.orderOutputs(orders);
        }
        // Transfers out of the lots.
        for (var r : s.list(
                """
                SELECT tl.source_lot_id,tl.destination_lot_id,tl.quantity,t.number,t.posted_at,i.uom
                FROM stock_transfer_lines tl
                JOIN stock_transfers t ON t.tenant_id=tl.tenant_id AND t.id=tl.transfer_id
                JOIN items i ON i.tenant_id=tl.tenant_id AND i.id=tl.item_id
                WHERE tl.tenant_id=? AND tl.destination_lot_id IS NOT NULL
                  AND tl.source_lot_id IN (SELECT jsonb_array_elements_text(?::jsonb))""",
                t,
                lots))
            g.edge(
                    "lot:" + r.get("source_lot_id"),
                    "lot:" + r.get("destination_lot_id"),
                    "TRANSFERRED",
                    r.get("quantity"),
                    r.get("uom"),
                    r.get("posted_at"),
                    str(r, "number"));
        // Rework orders consuming the lots (open ones have no output yet).
        var reworks = new LinkedHashSet<String>();
        for (var r : s.list(
                """
                SELECT id FROM rework_orders WHERE tenant_id=? AND input_lot_id IN (SELECT jsonb_array_elements_text(?::jsonb))
                UNION
                SELECT rework_order_id FROM rework_materials WHERE tenant_id=? AND lot_id IN (SELECT jsonb_array_elements_text(?::jsonb))""",
                t,
                lots,
                t,
                lots))
            reworks.add(str(r, "id"));
        if (!reworks.isEmpty()) {
            g.reworks(reworks);
            g.reworkInputs(reworks);
        }
        // Deliveries to customers and the customer returns of those deliveries.
        g.deliveriesWithReturns("d.lot_id IN (SELECT jsonb_array_elements_text(?::jsonb))", lots);
        // Terminal outflows: supplier returns, scrap, maintenance issues (net of returns), stock adjustments.
        g.outflows(lots);
    }

    // ------------------------------------------------------------------ tree and summary

    /** Pre-order rows of the indented tree from the root; a node reached again is listed once more as repeated. */
    private static List<Map<String, Object>> tree(String root, List<Map<String, Object>> edges, boolean forward) {
        var adjacency = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var e : edges) {
            String from = forward ? str(e, "from") : str(e, "to");
            adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(e);
        }
        var rows = new ArrayList<Map<String, Object>>();
        var expanded = new HashSet<String>();
        expanded.add(root);
        var stack = new ArrayDeque<Object[]>();
        var children = adjacency.getOrDefault(root, List.of());
        for (int i = children.size() - 1; i >= 0; i--) stack.push(new Object[] {children.get(i), 1});
        while (!stack.isEmpty() && rows.size() < 5000) {
            var top = stack.pop();
            @SuppressWarnings("unchecked")
            var e = (Map<String, Object>) top[0];
            int indent = (Integer) top[1];
            String node = forward ? str(e, "to") : str(e, "from");
            boolean repeated = !expanded.add(node);
            var row = new LinkedHashMap<String, Object>();
            row.put("node", node);
            row.put("indent", indent);
            row.put("edge", e.get("kind"));
            row.put("quantity", e.get("quantity"));
            row.put("uom", e.get("uom"));
            row.put("at", e.get("at"));
            row.put("reference", e.get("reference"));
            row.put("repeated", repeated);
            rows.add(row);
            if (repeated) continue;
            var next = adjacency.getOrDefault(node, List.of());
            for (int i = next.size() - 1; i >= 0; i--) stack.push(new Object[] {next.get(i), indent + 1});
        }
        return rows;
    }

    private static Map<String, Object> summary(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        var out = new LinkedHashMap<String, Object>();
        long lots = nodes.stream().filter(n -> "LOT".equals(n.get("kind"))).count();
        long inStock = nodes.stream()
                .filter(n -> "LOT".equals(n.get("kind")) && dec(n.get("onHand")).signum() > 0)
                .count();
        var customers = new HashSet<Object>();
        var suppliers = new HashSet<Object>();
        for (var n : nodes) {
            if ("DELIVERY".equals(n.get("kind")) && n.get("customerId") != null) customers.add(n.get("customerId"));
            if ("RECEIPT".equals(n.get("kind")) && n.get("supplierId") != null) suppliers.add(n.get("supplierId"));
        }
        out.put("lots", lots);
        out.put("inStockLots", inStock);
        out.put("orders", nodes.stream().filter(n -> "MANUFACTURING_ORDER".equals(n.get("kind"))).count());
        out.put("deliveries", nodes.stream().filter(n -> "DELIVERY".equals(n.get("kind"))).count());
        out.put("customers", customers.size());
        out.put("suppliers", suppliers.size());
        out.put("edges", edges.size());
        return out;
    }

    // ------------------------------------------------------------------ graph builder

    /** Nodes (by id) and edges of one trace; lot nodes are filled in bulk by {@link #loadLots}. */
    private final class Graph {
        final String tenant;
        final Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        final List<Map<String, Object>> edges = new ArrayList<>();
        final Map<String, Map<String, Object>> edgeByKey = new HashMap<>();
        final Set<String> lotIds = new LinkedHashSet<>();
        final Set<String> frontierForward = new HashSet<>();
        final Set<String> frontierBackward = new HashSet<>();
        boolean truncated;

        Graph(String tenant) {
            this.tenant = tenant;
        }

        Map<String, Object> node(String id, String kind, String label, String detail) {
            return nodes.computeIfAbsent(id, k -> {
                var n = new LinkedHashMap<String, Object>();
                n.put("id", id);
                n.put("kind", kind);
                n.put("label", label);
                n.put("detail", detail);
                return n;
            });
        }

        void lot(String lotId) {
            lotIds.add(lotId);
            nodes.computeIfAbsent("lot:" + lotId, k -> {
                var n = new LinkedHashMap<String, Object>();
                n.put("id", k);
                n.put("kind", "LOT");
                n.put("ref", lotId);
                return n;
            });
        }

        /** A lot at the depth limit: {@code more} when it has links beyond it in that direction. */
        void frontier(String lotId, boolean forward) {
            (forward ? frontierForward : frontierBackward).add(lotId);
        }

        void edge(String from, String to, String kind, Object quantity, Object uom, Object at, String reference) {
            String key = from + "|" + to + "|" + kind + "|" + reference;
            var existing = edgeByKey.get(key);
            if (existing != null) {
                // The same document moved the same lot twice (e.g. two scrap dispositions): add the quantities.
                if (existing.get("quantity") != null && quantity != null)
                    existing.put("quantity", dec(existing.get("quantity")).add(dec(quantity)));
                return;
            }
            var e = new LinkedHashMap<String, Object>();
            edgeByKey.put(key, e);
            e.put("from", from);
            e.put("to", to);
            e.put("kind", kind);
            e.put("quantity", quantity == null ? null : dec(quantity));
            e.put("uom", uom);
            e.put("at", at);
            e.put("reference", reference);
            edges.add(e);
        }

        void orders(Collection<String> ids) {
            for (var r : s.list(
                    """
                    SELECT m.id,m.number,m.status,m.quantity,m.good_qty,m.scrap_qty,m.created_at,m.started_at,
                           m.parent_order_id,i.sku,i.name AS item,i.uom,w.code AS machine,so.number AS sales_order
                    FROM manufacturing_orders m
                    JOIN items i ON i.tenant_id=m.tenant_id AND i.id=m.product_id
                    JOIN machines w ON w.tenant_id=m.tenant_id AND w.id=m.machine_id
                    LEFT JOIN sales_orders so ON so.tenant_id=m.tenant_id AND so.id=m.sales_order_id
                    WHERE m.tenant_id=? AND m.id IN (SELECT jsonb_array_elements_text(?::jsonb))""",
                    tenant,
                    s.encode(ids))) {
                var n = node("mo:" + r.get("id"), "MANUFACTURING_ORDER", str(r, "number"), r.get("sku") + " · " + r.get("item"));
                n.put("ref", r.get("id"));
                n.put("status", r.get("status"));
                n.put("quantity", r.get("quantity"));
                n.put("goodQty", r.get("good_qty"));
                n.put("scrapQty", r.get("scrap_qty"));
                n.put("uom", r.get("uom"));
                n.put("at", r.get("started_at") == null ? r.get("created_at") : r.get("started_at"));
                n.put("machine", r.get("machine"));
                n.put("salesOrder", r.get("sales_order"));
                n.put("parentOrderId", r.get("parent_order_id"));
            }
        }

        /** Material issued to the orders: lot → order edges; returns the rows. */
        List<Map<String, Object>> issuesToOrders(Collection<String> orderIds) {
            return addIssues(
                    "r.order_id IN (SELECT jsonb_array_elements_text(?::jsonb))", s.encode(orderIds));
        }

        /** Material issued from the lots: lot → order edges. */
        List<Map<String, Object>> issues(String lotsJson) {
            return addIssues("r.lot_id IN (SELECT jsonb_array_elements_text(?::jsonb))", lotsJson);
        }

        private List<Map<String, Object>> addIssues(String filter, String json) {
            var rows = s.list(
                    """
                    SELECT r.lot_id,r.order_id,SUM(r.quantity) AS quantity,m.number,i.uom,
                           (SELECT MIN(e.created_at) FROM stock_ledger e WHERE e.tenant_id=r.tenant_id AND e.lot_id=r.lot_id
                              AND e.reference=m.number AND e.reason='MATERIAL_ISSUE') AS issued_at
                    FROM reservations r
                    JOIN manufacturing_orders m ON m.tenant_id=r.tenant_id AND m.id=r.order_id
                    JOIN items i ON i.tenant_id=r.tenant_id AND i.id=r.item_id
                    WHERE r.tenant_id=? AND r.status='ISSUED' AND """
                            + filter + " GROUP BY r.tenant_id,r.lot_id,r.order_id,m.number,i.uom",
                    tenant,
                    json);
            for (var r : rows) {
                lotIds.add(str(r, "lot_id"));
                lot(str(r, "lot_id"));
                edge(
                        "lot:" + r.get("lot_id"),
                        "mo:" + r.get("order_id"),
                        "ISSUED",
                        r.get("quantity"),
                        r.get("uom"),
                        r.get("issued_at"),
                        str(r, "number"));
            }
            return rows;
        }

        /** Output lots of the orders: order → lot edges (quantity and time of the production receipt). */
        List<Map<String, Object>> orderOutputs(Collection<String> orderIds) {
            var rows = s.list(
                    """
                    SELECT l.id AS lot_id,l.source_id AS order_id,m.number,i.uom,
                           COALESCE((SELECT SUM(pa.good_qty) FROM production_actuals pa
                                     WHERE pa.tenant_id=l.tenant_id AND pa.lot_id=l.id),0) AS quantity,
                           l.created_at
                    FROM lots l
                    JOIN manufacturing_orders m ON m.tenant_id=l.tenant_id AND m.id=l.source_id
                    JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                    WHERE l.tenant_id=? AND l.source_type='MANUFACTURING'
                      AND l.source_id IN (SELECT jsonb_array_elements_text(?::jsonb))
                    ORDER BY l.created_at,l.id""",
                    tenant,
                    s.encode(orderIds));
            for (var r : rows) {
                lot(str(r, "lot_id"));
                edge(
                        "mo:" + r.get("order_id"),
                        "lot:" + r.get("lot_id"),
                        "PRODUCED",
                        r.get("quantity"),
                        r.get("uom"),
                        r.get("created_at"),
                        str(r, "number"));
            }
            return rows;
        }

        void reworks(Collection<String> ids) {
            for (var r : s.list(
                    """
                    SELECT w.*,i.sku,i.name AS item,i.uom,n.number AS ncr FROM rework_orders w
                    JOIN items i ON i.tenant_id=w.tenant_id AND i.id=w.item_id
                    JOIN ncrs n ON n.tenant_id=w.tenant_id AND n.id=w.ncr_id
                    WHERE w.tenant_id=? AND w.id IN (SELECT jsonb_array_elements_text(?::jsonb))""",
                    tenant,
                    s.encode(ids))) {
                String nodeId = "rw:" + r.get("id");
                var n = node(nodeId, "REWORK_ORDER", str(r, "number"), r.get("sku") + " · " + r.get("item"));
                n.put("ref", r.get("id"));
                n.put("status", r.get("status"));
                n.put("at", r.get("created_at"));
                n.put("ncr", r.get("ncr"));
                n.put("ncrId", r.get("ncr_id"));
                if (r.get("output_lot_id") != null) {
                    lot(str(r, "output_lot_id"));
                    edge(
                            nodeId,
                            "lot:" + r.get("output_lot_id"),
                            "REWORKED",
                            r.get("output_quantity"),
                            r.get("uom"),
                            r.get("completed_at"),
                            str(r, "number"));
                }
            }
        }

        /** The reworked lot and the extra material of the orders: lot → rework order edges. */
        void reworkInputs(Collection<String> ids) {
            for (var r : s.list(
                    """
                    SELECT w.id,w.number,w.input_lot_id AS lot_id,w.input_quantity AS quantity,w.created_at,
                           'REWORK_INPUT' AS kind,i.uom
                    FROM rework_orders w JOIN items i ON i.tenant_id=w.tenant_id AND i.id=w.item_id
                    WHERE w.tenant_id=? AND w.id IN (SELECT jsonb_array_elements_text(?::jsonb))
                    UNION ALL
                    SELECT w.id,w.number,m.lot_id,m.quantity,m.created_at,'REWORK_MATERIAL',i.uom
                    FROM rework_materials m
                    JOIN rework_orders w ON w.tenant_id=m.tenant_id AND w.id=m.rework_order_id
                    JOIN items i ON i.tenant_id=m.tenant_id AND i.id=m.item_id
                    WHERE m.tenant_id=? AND m.rework_order_id IN (SELECT jsonb_array_elements_text(?::jsonb))""",
                    tenant,
                    s.encode(ids),
                    tenant,
                    s.encode(ids))) {
                lot(str(r, "lot_id"));
                edge(
                        "lot:" + r.get("lot_id"),
                        "rw:" + r.get("id"),
                        str(r, "kind"),
                        r.get("quantity"),
                        r.get("uom"),
                        r.get("created_at"),
                        str(r, "number"));
            }
        }

        /** Deliveries matched by {@code filter} (alias d, customer returns cr): lot → delivery → return lot. */
        List<Map<String, Object>> deliveriesWithReturns(String filter, String lotsJson) {
            boolean byReturn = filter.startsWith("cr.");
            var rows = s.list(
                    """
                    SELECT d.id,d.lot_id,d.quantity,d.returned,d.reference,d.created_at,so.id AS so_id,so.number AS so,
                           p.id AS customer_id,p.name AS customer,i.uom
                    FROM deliveries d
                    JOIN sales_orders so ON so.tenant_id=d.tenant_id AND so.id=d.sales_order_id
                    JOIN partners p ON p.tenant_id=so.tenant_id AND p.id=so.customer_id
                    JOIN lots l ON l.tenant_id=d.tenant_id AND l.id=d.lot_id
                    JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                    WHERE d.tenant_id=? AND """
                            + (byReturn
                                    ? "d.id IN (SELECT cr.delivery_id FROM customer_returns cr WHERE cr.tenant_id=d.tenant_id AND "
                                            + filter + ")"
                                    : filter)
                            + " ORDER BY d.created_at,d.id",
                    tenant,
                    lotsJson);
            var deliveryIds = new ArrayList<String>();
            for (var r : rows) {
                String nodeId = "delivery:" + r.get("id");
                deliveryIds.add(str(r, "id"));
                var n = node(nodeId, "DELIVERY", str(r, "reference"), r.get("so") + " · " + r.get("customer"));
                n.put("ref", r.get("id"));
                n.put("at", r.get("created_at"));
                n.put("reference", r.get("reference"));
                n.put("salesOrderId", r.get("so_id"));
                n.put("salesOrder", r.get("so"));
                n.put("customerId", r.get("customer_id"));
                n.put("customer", r.get("customer"));
                n.put("quantity", r.get("quantity"));
                n.put("returned", r.get("returned"));
                n.put("uom", r.get("uom"));
                lot(str(r, "lot_id"));
                edge(
                        "lot:" + r.get("lot_id"),
                        nodeId,
                        "DELIVERED",
                        r.get("quantity"),
                        r.get("uom"),
                        r.get("created_at"),
                        str(r, "reference"));
            }
            if (!deliveryIds.isEmpty())
                for (var r : s.list(
                        """
                        SELECT cr.delivery_id,cr.lot_id,cr.number,cr.quantity,cr.created_at,i.uom FROM customer_returns cr
                        JOIN lots l ON l.tenant_id=cr.tenant_id AND l.id=cr.lot_id
                        JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                        WHERE cr.tenant_id=? AND cr.delivery_id IN (SELECT jsonb_array_elements_text(?::jsonb))""",
                        tenant,
                        s.encode(deliveryIds))) {
                    lot(str(r, "lot_id"));
                    edge(
                            "delivery:" + r.get("delivery_id"),
                            "lot:" + r.get("lot_id"),
                            "RETURNED",
                            r.get("quantity"),
                            r.get("uom"),
                            r.get("created_at"),
                            str(r, "number"));
                }
            return rows;
        }

        /**
         * Quantity that left the lots for good, from the stock ledger: supplier returns ({@code SUPPLIER_RETURN},
         * failed incoming {@code SUPPLIER_RETURN_QC}), quality scrap, maintenance issues net of returns and stock
         * adjustments (decreases, count variances).
         */
        void outflows(String lotsJson) {
            for (var r : s.list(
                    """
                    SELECT e.lot_id,e.reference,i.uom,
                           CASE WHEN e.reason IN ('SUPPLIER_RETURN','SUPPLIER_RETURN_QC') THEN 'SUPPLIER_RETURN'
                                WHEN e.reason='QUALITY_SCRAP' THEN 'SCRAP'
                                WHEN e.reason IN ('MAINTENANCE_ISSUE','MAINTENANCE_RETURN') THEN 'MAINTENANCE'
                                ELSE 'ADJUSTMENT' END AS kind,
                           -SUM(e.delta) AS quantity,MAX(e.created_at) AS at,
                           MIN(CASE WHEN e.reason='SUPPLIER_RETURN_QC' THEN 'QC' END) AS qc
                    FROM stock_ledger e JOIN items i ON i.tenant_id=e.tenant_id AND i.id=e.item_id
                    WHERE e.tenant_id=? AND e.lot_id IN (SELECT jsonb_array_elements_text(?::jsonb))
                      AND (e.reason IN ('SUPPLIER_RETURN','SUPPLIER_RETURN_QC','QUALITY_SCRAP','MAINTENANCE_ISSUE',
                                        'MAINTENANCE_RETURN') OR e.reason LIKE 'ADJUSTMENT\\_%')
                    GROUP BY e.lot_id,e.reference,i.uom,4
                    HAVING SUM(e.delta) < 0
                    ORDER BY MAX(e.created_at)""",
                    tenant,
                    lotsJson)) {
                String kind = str(r, "kind");
                String reference = str(r, "reference");
                String label = reference;
                String detail =
                        switch (kind) {
                            case "SUPPLIER_RETURN" -> "Returned to supplier";
                            case "SCRAP" -> "Scrapped (nonconformance)";
                            case "MAINTENANCE" -> "Issued to maintenance";
                            default -> "Stock adjustment";
                        };
                if (r.get("qc") != null) {
                    // A failed incoming inspection returns the lot under the purchase order id.
                    var po = s.list(
                            "SELECT po.number,p.name FROM purchase_orders po LEFT JOIN partners p ON p.tenant_id=po.tenant_id AND p.id=po.supplier_id WHERE po.tenant_id=? AND po.id=?",
                            tenant,
                            reference);
                    if (!po.isEmpty()) {
                        label = str(po.getFirst(), "number");
                        detail = "Failed incoming inspection · returned to " + po.getFirst().get("name");
                    }
                } else if (kind.equals("SUPPLIER_RETURN")) {
                    var sr = s.list(
                            "SELECT p.name FROM supplier_returns x JOIN partners p ON p.tenant_id=x.tenant_id AND p.id=x.supplier_id WHERE x.tenant_id=? AND x.number=?",
                            tenant,
                            reference);
                    if (!sr.isEmpty()) detail = "Returned to " + sr.getFirst().get("name");
                }
                String nodeId = "out:" + kind + ":" + reference;
                var n = node(nodeId, kind, label, detail);
                n.put("ref", reference);
                n.put("at", r.get("at"));
                edge("lot:" + r.get("lot_id"), nodeId, kind, r.get("quantity"), r.get("uom"), r.get("at"), label);
            }
        }

        /** The start of lots without a source document (opening stock, found stock, legacy data). */
        void origins(List<Map<String, Object>> lots) {
            if (lots.isEmpty()) return;
            var first = new HashMap<String, Map<String, Object>>();
            for (var r : s.list(
                    """
                    SELECT DISTINCT ON (e.lot_id) e.lot_id,e.delta,e.created_at,i.uom FROM stock_ledger e
                    JOIN items i ON i.tenant_id=e.tenant_id AND i.id=e.item_id
                    WHERE e.tenant_id=? AND e.lot_id IN (SELECT jsonb_array_elements_text(?::jsonb))
                    ORDER BY e.lot_id,e.created_at,e.id""",
                    tenant,
                    s.encode(lots.stream().map(l -> str(l, "id")).toList())))
                first.put(str(r, "lot_id"), r);
            var adjustments = new HashMap<String, String>();
            for (var r : s.list(
                    """
                    SELECT a.id,a.number FROM stock_adjustments a
                    WHERE a.tenant_id=? AND a.id IN (SELECT jsonb_array_elements_text(?::jsonb))""",
                    tenant,
                    s.encode(lots.stream().map(l -> str(l, "source_id")).toList())))
                adjustments.put(str(r, "id"), str(r, "number"));
            for (var lot : lots) {
                String type = str(lot, "source_type");
                String lotId = str(lot, "id");
                String kind = type.equals("ADJUSTMENT") ? "FOUND" : type.equals("OPENING") ? "OPENING" : "ORIGIN";
                String label = adjustments.getOrDefault(str(lot, "source_id"), str(lot, "source_id"));
                String nodeId = "origin:" + lotId;
                var n = node(
                        nodeId,
                        kind,
                        label,
                        switch (kind) {
                            case "FOUND" -> "Found stock (adjustment)";
                            case "OPENING" -> "Opening stock";
                            default -> "Origin " + type.toLowerCase(Locale.ROOT);
                        });
                n.put("ref", label);
                var f = first.get(lotId);
                if (f != null) {
                    n.put("at", f.get("created_at"));
                    edge(nodeId, "lot:" + lotId, kind, f.get("delta"), f.get("uom"), f.get("created_at"), label);
                } else edge(nodeId, "lot:" + lotId, kind, null, null, null, label);
            }
        }

        /** Item, warehouse, stock, status and open NCR of every lot node; {@code more} on lots at the depth limit. */
        void loadLots() {
            if (lotIds.isEmpty()) return;
            for (var r : s.list(
                    """
                    SELECT l.id,l.number,l.status,l.source_type,l.created_at,l.unit_cost,i.id AS item_id,i.sku,
                           i.name AS item,i.type AS item_type,i.uom,w.id AS warehouse_id,w.name AS warehouse,
                           w.code AS warehouse_code,COALESCE(b.quantity,0) AS on_hand,COALESCE(b.reserved,0) AS reserved,
                           (SELECT n.number FROM ncrs n WHERE n.tenant_id=l.tenant_id AND n.lot_id=l.id AND n.status='OPEN'
                            LIMIT 1) AS open_ncr,
                           EXISTS (SELECT 1 FROM stock_ledger e WHERE e.tenant_id=l.tenant_id AND e.lot_id=l.id AND e.delta<0)
                             AS has_outflow
                    FROM lots l
                    JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id
                    JOIN warehouses w ON w.tenant_id=l.tenant_id AND w.id=l.warehouse_id
                    LEFT JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                    WHERE l.tenant_id=? AND l.id IN (SELECT jsonb_array_elements_text(?::jsonb))""",
                    tenant,
                    s.encode(lotIds))) {
                var n = nodes.get("lot:" + r.get("id"));
                if (n == null) continue;
                n.put("label", r.get("number"));
                n.put("detail", r.get("sku") + " · " + r.get("item"));
                n.put("number", r.get("number"));
                n.put("status", r.get("status"));
                n.put("sourceType", r.get("source_type"));
                n.put("at", r.get("created_at"));
                n.put("itemId", r.get("item_id"));
                n.put("sku", r.get("sku"));
                n.put("item", r.get("item"));
                n.put("itemType", r.get("item_type"));
                n.put("uom", r.get("uom"));
                n.put("warehouseId", r.get("warehouse_id"));
                n.put("warehouse", r.get("warehouse"));
                n.put("warehouseCode", r.get("warehouse_code"));
                n.put("onHand", r.get("on_hand"));
                n.put("reserved", r.get("reserved"));
                n.put("unitCost", r.get("unit_cost"));
                n.put("openNcr", r.get("open_ncr"));
                String id = str(r, "id");
                boolean more = (frontierForward.contains(id) && Boolean.TRUE.equals(r.get("has_outflow")))
                        || (frontierBackward.contains(id) && !"OPENING".equals(r.get("source_type")));
                n.put("more", more);
            }
        }

        /** Removes edges to lots that were never loaded (beyond the lot cap) and marks the trace truncated. */
        void dropDangling() {
            var it = edges.iterator();
            while (it.hasNext()) {
                var e = it.next();
                boolean ok = nodes.containsKey(str(e, "from"))
                        && nodes.containsKey(str(e, "to"))
                        && labelled(str(e, "from"))
                        && labelled(str(e, "to"));
                if (!ok) {
                    it.remove();
                    truncated = true;
                }
            }
            nodes.values().removeIf(n -> n.get("label") == null);
        }

        private boolean labelled(String nodeId) {
            var n = nodes.get(nodeId);
            return n != null && n.get("label") != null;
        }

        /**
         * Columns for a left-to-right drawing: the root is 0, forward nodes get their longest path from the root,
         * backward nodes minus their longest path to it (Kahn's order; a node on a cycle keeps its first distance).
         */
        Map<String, Integer> levels(String root, boolean backward, boolean forward) {
            var levels = new HashMap<String, Integer>();
            levels.put(root, 0);
            if (forward) longest(root, true, levels);
            if (backward) longest(root, false, levels);
            return levels;
        }

        private void longest(String root, boolean forward, Map<String, Integer> levels) {
            var out = new HashMap<String, List<String>>();
            for (var e : edges) {
                String a = forward ? str(e, "from") : str(e, "to");
                String b = forward ? str(e, "to") : str(e, "from");
                out.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
            }
            // Nodes reachable from the root on this side.
            var reach = new LinkedHashSet<String>();
            var queue = new ArrayDeque<String>();
            queue.add(root);
            reach.add(root);
            while (!queue.isEmpty())
                for (String b : out.getOrDefault(queue.poll(), List.of()))
                    if (reach.add(b)) queue.add(b);
            var indegree = new HashMap<String, Integer>();
            for (String a : reach)
                for (String b : out.getOrDefault(a, List.of()))
                    if (reach.contains(b) && !b.equals(root)) indegree.merge(b, 1, Integer::sum);
            var dist = new HashMap<String, Integer>();
            dist.put(root, 0);
            queue.add(root);
            while (!queue.isEmpty()) {
                String a = queue.poll();
                for (String b : out.getOrDefault(a, List.of())) {
                    if (!reach.contains(b) || b.equals(root)) continue;
                    dist.merge(b, dist.get(a) + 1, Math::max);
                    if (indegree.merge(b, -1, Integer::sum) == 0) queue.add(b);
                }
            }
            for (String n : reach) {
                if (n.equals(root) || levels.containsKey(n)) continue;
                int d = dist.getOrDefault(n, 1);
                levels.put(n, forward ? d : -d);
            }
        }
    }
}
