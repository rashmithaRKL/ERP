package lk.dexlanka.erp.routing;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Routing read model (V10): the keyset-paged routing list ({@code created_at DESC, id DESC}), one revision with its
 * operations, and the operation-by-operation comparison of two revisions. Every role may read routings (operators see
 * instructions and skills on the shop floor).
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RoutingQuery {
    private static final String FROM = """
             FROM routings r
            JOIN items i ON i.tenant_id=r.tenant_id AND i.id=r.product_id
            LEFT JOIN routing_defaults d ON d.tenant_id=r.tenant_id AND d.routing_id=r.id
            WHERE r.tenant_id=?
              AND (?::text IS NULL OR (? = 'CURRENT' AND d.routing_id IS NOT NULL) OR r.status = ?)
              AND (?::text IS NULL OR r.product_id = ?)
              AND (?::text IS NULL OR r.number ILIKE ? OR r.revision ILIKE ? OR i.sku ILIKE ? OR i.name ILIKE ?)
            """;

    private final Store s;

    RoutingQuery(Store s) {
        this.s = s;
    }

    /**
     * One page of routing revisions, newest first: {@code {items, next, total, counts}}.
     *
     * @param status {@code all} (or blank), {@code DRAFT}, {@code APPROVED} or {@code CURRENT}
     */
    public Map<String, Object> page(Actor a, String status, String productId, String q, int limit, String before) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        String st = status == null || status.isBlank() || status.equalsIgnoreCase("all")
                ? null
                : status.toUpperCase(Locale.ROOT);
        require(st == null || List.of("DRAFT", "APPROVED", "CURRENT").contains(st), "Unknown routing status filter");
        String product = productId == null || productId.isBlank() ? null : productId;
        String like = like(q);
        String cursor = before == null || before.isBlank() ? null : before;
        Object[] filter = {a.tenant(), st, st, st, product, product, like, like, like, like, like};
        var rows = s.list("""
                SELECT r.*,i.sku,i.name AS product,i.type AS product_type,i.uom,(d.routing_id IS NOT NULL) AS current,
                       (SELECT COUNT(*) FROM routing_operations o WHERE o.tenant_id=r.tenant_id AND o.routing_id=r.id) AS operation_count,
                       (SELECT COALESCE(SUM(o.setup_minutes),0) FROM routing_operations o WHERE o.tenant_id=r.tenant_id AND o.routing_id=r.id) AS setup_minutes,
                       (SELECT COALESCE(SUM(o.run_minutes_per_unit),0) FROM routing_operations o WHERE o.tenant_id=r.tenant_id AND o.routing_id=r.id) AS run_minutes_per_unit,
                       (SELECT name FROM users u WHERE u.id=r.created_by) AS created_by_name,
                       (SELECT name FROM users u WHERE u.id=r.approved_by) AS approved_by_name""" + FROM + """
                  AND (?::text IS NULL OR (r.created_at,r.id) <
                       (SELECT c.created_at,c.id FROM routings c WHERE c.tenant_id=r.tenant_id AND c.id=?))
                ORDER BY r.created_at DESC,r.id DESC
                LIMIT ?""", concat(filter, cursor, cursor, limit + 1));
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        long total = s.db().queryForObject("SELECT COUNT(*)" + FROM, Long.class, filter);
        var counts = s.one("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE r.status='DRAFT') AS draft,
                       COUNT(*) FILTER (WHERE r.status='APPROVED') AS approved,
                       (SELECT COUNT(*) FROM routing_defaults d WHERE d.tenant_id=?) AS current
                FROM routings r WHERE r.tenant_id=?""", a.tenant(), a.tenant());
        var out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("next", more ? items.getLast().get("id") : "");
        out.put("total", total);
        out.put("counts", counts);
        return out;
    }

    /**
     * One revision: {@code routing} (with product, authors and whether it is current), {@code operations} (work center,
     * alternates, skill), the product's other {@code revisions}, the number of manufacturing {@code orders} that
     * snapshotted it, and its document {@code events}.
     */
    public Map<String, Object> detail(Actor a, String id) {
        var routing = s.one("""
                SELECT r.*,i.sku,i.name AS product,i.type AS product_type,i.uom,i.active AS product_active,
                       (d.routing_id IS NOT NULL) AS current,
                       (SELECT number FROM routings x WHERE x.tenant_id=r.tenant_id AND x.id=r.source_routing_id) AS source_number,
                       (SELECT revision FROM routings x WHERE x.tenant_id=r.tenant_id AND x.id=r.source_routing_id) AS source_revision,
                       (SELECT name FROM users u WHERE u.id=r.created_by) AS created_by_name,
                       (SELECT name FROM users u WHERE u.id=r.updated_by) AS updated_by_name,
                       (SELECT name FROM users u WHERE u.id=r.approved_by) AS approved_by_name
                FROM routings r
                JOIN items i ON i.tenant_id=r.tenant_id AND i.id=r.product_id
                LEFT JOIN routing_defaults d ON d.tenant_id=r.tenant_id AND d.routing_id=r.id
                WHERE r.tenant_id=? AND r.id=?""", a.tenant(), id);
        var out = new LinkedHashMap<String, Object>();
        out.put("routing", routing);
        out.put("operations", operations(a.tenant(), id));
        out.put("revisions", s.list("""
                        SELECT r.id,r.number,r.revision,r.status,r.created_at,r.approved_at,(d.routing_id IS NOT NULL) AS current
                        FROM routings r
                        LEFT JOIN routing_defaults d ON d.tenant_id=r.tenant_id AND d.routing_id=r.id
                        WHERE r.tenant_id=? AND r.product_id=?
                        ORDER BY r.created_at DESC,r.id DESC
                        LIMIT 100""", a.tenant(), routing.get("product_id")));
        out.put(
                "orders",
                s.db().queryForObject(
                                "SELECT COUNT(*) FROM manufacturing_orders WHERE tenant_id=? AND routing_id=?",
                                Long.class,
                                a.tenant(),
                                id));
        out.put(
                "events",
                s.list(
                        "SELECT * FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at,id LIMIT 200",
                        a.tenant(),
                        routing.get("number")));
        return out;
    }

    /** The operations of a routing in sequence, each with its work center, skill and {@code alternates} list. */
    List<Map<String, Object>> operations(String tenant, String routing) {
        var ops = s.list("""
                SELECT o.*,m.code AS machine_code,m.name AS machine_name,m.kind AS machine_kind,m.status AS machine_status,
                       sk.code AS skill_code,sk.name AS skill_name
                FROM routing_operations o
                JOIN machines m ON m.tenant_id=o.tenant_id AND m.id=o.machine_id
                LEFT JOIN skills sk ON sk.tenant_id=o.tenant_id AND sk.id=o.skill_id
                WHERE o.tenant_id=? AND o.routing_id=?
                ORDER BY o.seq""", tenant, routing);
        var alternates = new TreeMap<String, List<Map<String, Object>>>();
        for (var alt : s.list("""
                SELECT x.operation_id,m.id,m.code,m.name,m.status
                FROM routing_operation_alternates x
                JOIN routing_operations o ON o.tenant_id=x.tenant_id AND o.id=x.operation_id
                JOIN machines m ON m.tenant_id=x.tenant_id AND m.id=x.machine_id
                WHERE x.tenant_id=? AND o.routing_id=?
                ORDER BY m.code""", tenant, routing)) {
            var machine = new LinkedHashMap<String, Object>(alt);
            machine.remove("operation_id");
            alternates
                    .computeIfAbsent(str(alt, "operation_id"), k -> new ArrayList<>())
                    .add(machine);
        }
        var out = new ArrayList<Map<String, Object>>();
        for (var op : ops) {
            var row = new LinkedHashMap<String, Object>(op);
            row.put("alternates", alternates.getOrDefault(str(op, "id"), List.of()));
            out.add(row);
        }
        return out;
    }

    /**
     * Operation-by-operation comparison of two revisions of the same product, matched by sequence number: each entry is
     * {@code ADDED}, {@code REMOVED}, {@code CHANGED} (with the changed field names) or {@code SAME}.
     */
    public Map<String, Object> compare(Actor a, String fromId, String toId) {
        var from = s.one(
                "SELECT r.*,i.sku,i.name AS product FROM routings r JOIN items i ON i.tenant_id=r.tenant_id AND i.id=r.product_id WHERE r.tenant_id=? AND r.id=?",
                a.tenant(),
                fromId);
        var to = s.one(
                "SELECT r.*,i.sku,i.name AS product FROM routings r JOIN items i ON i.tenant_id=r.tenant_id AND i.id=r.product_id WHERE r.tenant_id=? AND r.id=?",
                a.tenant(),
                toId);
        require(
                Objects.equals(from.get("product_id"), to.get("product_id")),
                "Compare two revisions of the same product");
        var before = bySeq(operations(a.tenant(), fromId));
        var after = bySeq(operations(a.tenant(), toId));
        var seqs = new java.util.TreeSet<Integer>();
        seqs.addAll(before.keySet());
        seqs.addAll(after.keySet());
        var rows = new ArrayList<Map<String, Object>>();
        int added = 0, removed = 0, changed = 0;
        for (int seq : seqs) {
            var x = before.get(seq);
            var y = after.get(seq);
            var row = new LinkedHashMap<String, Object>();
            row.put("seq", seq);
            row.put("from", x);
            row.put("to", y);
            List<String> changes = List.of();
            String state;
            if (x == null) {
                state = "ADDED";
                added++;
            } else if (y == null) {
                state = "REMOVED";
                removed++;
            } else {
                changes = changes(x, y);
                state = changes.isEmpty() ? "SAME" : "CHANGED";
                if (!changes.isEmpty()) changed++;
            }
            row.put("status", state);
            row.put("changes", changes);
            rows.add(row);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("from", from);
        out.put("to", to);
        out.put("operations", rows);
        out.put("summary", Map.of("added", added, "removed", removed, "changed", changed));
        return out;
    }

    private static TreeMap<Integer, Map<String, Object>> bySeq(List<Map<String, Object>> operations) {
        var out = new TreeMap<Integer, Map<String, Object>>();
        for (var op : operations) out.put(((Number) op.get("seq")).intValue(), op);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> changes(Map<String, Object> x, Map<String, Object> y) {
        var out = new ArrayList<String>();
        for (String field : List.of("name", "machine_id", "skill_id", "instructions"))
            if (!Objects.equals(str(x, field), str(y, field))) out.add(field);
        for (String field : List.of("setup_minutes", "run_minutes_per_unit", "crew_size"))
            if (dec(x.get(field)).compareTo(dec(y.get(field))) != 0) out.add(field);
        for (String field : List.of("skill_level", "inspection_point"))
            if (!Objects.equals(str(x, field), str(y, field))) out.add(field);
        var altX = ((List<Map<String, Object>>) x.get("alternates"))
                .stream().map(m -> str(m, "id")).sorted().toList();
        var altY = ((List<Map<String, Object>>) y.get("alternates"))
                .stream().map(m -> str(m, "id")).sorted().toList();
        if (!altX.equals(altY)) out.add("alternates");
        return out;
    }

    static String like(String q) {
        if (q == null || q.isBlank()) return null;
        return "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    static Object[] concat(Object[] head, Object... tail) {
        var out = new Object[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
