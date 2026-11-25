package lk.dexlanka.erp.catalog;

import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Item master read model: the server-paged item list, item detail (units, conversions, barcodes, variants, stock,
 * where-used, movements), units, categories, variant families and barcode lookup. All signed-in roles may read.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ItemQuery {
    private static final Set<String> TYPES = Set.of("RAW_MATERIAL", "SEMI_FINISHED", "FINISHED_GOOD", "PACKAGING", "SPARE_PART");

    /** Stock figures per item: on hand, reserved, released-and-unreserved, value. */
    private static final String STOCK = """
            LEFT JOIN LATERAL (
                SELECT COALESCE(SUM(b.quantity),0) AS on_hand,
                       COALESCE(SUM(b.reserved),0) AS reserved,
                       COALESCE(SUM(CASE WHEN l.status='RELEASED' THEN b.quantity-b.reserved ELSE 0 END),0) AS available,
                       COALESCE(SUM(b.value_amount),0) AS stock_value
                FROM lots l
                JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                WHERE l.tenant_id=i.tenant_id AND l.item_id=i.id) st ON true
            """;

    private static final String COLUMNS = """
            i.id,i.sku,i.name,i.description,i.type,i.uom,i.standard_cost,i.reorder_level,i.reorder_qty,
            i.lead_time_days,i.active,i.category_id,i.template_id,i.variant_label,i.created_at,i.updated_at,
            u.name AS unit_name,u.decimals AS unit_decimals,c.name AS category,t.code AS template_code,
            t.name AS template_name,st.on_hand,st.reserved,st.available,st.stock_value,
            (SELECT COUNT(*) FROM item_barcodes x WHERE x.tenant_id=i.tenant_id AND x.item_id=i.id) AS barcodes,
            (SELECT COUNT(*) FROM item_unit_conversions x WHERE x.tenant_id=i.tenant_id AND x.item_id=i.id) AS conversions
            """;

    private static final String JOINS = """
            JOIN units u ON u.tenant_id=i.tenant_id AND u.code=i.uom
            LEFT JOIN item_categories c ON c.tenant_id=i.tenant_id AND c.id=i.category_id
            LEFT JOIN item_templates t ON t.tenant_id=i.tenant_id AND t.id=i.template_id
            """;

    private final Store s;
    private final UnitService units;
    private final ItemService items;
    private final VariantService variants;

    ItemQuery(Store s, UnitService units, ItemService items, VariantService variants) {
        this.s = s;
        this.units = units;
        this.items = items;
        this.variants = variants;
    }

    // ------------------------------------------------------------------ item list

    /**
     * One page of items ordered by SKU (keyset cursor {@code after} = last item id of the previous page). Filters:
     * free text over SKU/name/description plus exact barcode, type, category (including sub-categories; {@code none} =
     * uncategorized) and active state ({@code true}/{@code false}, omitted = all).
     */
    public Map<String, Object> page(
            Actor a, String q, String type, String category, String active, int limit, String after) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        if (type != null && !type.isBlank() && !TYPES.contains(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown item type filter");
        if (active != null && !active.isBlank() && !Set.of("true", "false").contains(active))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use active=true or active=false");
        var where = new StringBuilder("i.tenant_id=?");
        var args = new ArrayList<Object>(List.of(a.tenant()));
        if (q != null && !q.isBlank()) {
            String pattern =
                    "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (i.sku ILIKE ? OR i.name ILIKE ? OR i.description ILIKE ? OR EXISTS (SELECT 1 FROM"
                    + " item_barcodes x WHERE x.tenant_id=i.tenant_id AND x.item_id=i.id AND x.barcode=?))");
            args.addAll(List.of(pattern, pattern, pattern, q.trim()));
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND i.type=?");
            args.add(type);
        }
        if ("none".equals(category)) where.append(" AND i.category_id IS NULL");
        else if (category != null && !category.isBlank()) {
            where.append("""
                     AND i.category_id IN (
                        WITH RECURSIVE tree AS (
                            SELECT id FROM item_categories WHERE tenant_id=? AND id=?
                            UNION
                            SELECT c.id FROM item_categories c JOIN tree ON c.parent_id=tree.id WHERE c.tenant_id=?)
                        SELECT id FROM tree)""");
            args.addAll(List.of(a.tenant(), category, a.tenant()));
        }
        if (active != null && !active.isBlank()) {
            where.append(" AND i.active=?");
            args.add(Boolean.parseBoolean(active));
        }
        long total = s.db().queryForObject("SELECT COUNT(*) FROM items i WHERE " + where, Long.class, args.toArray());
        var pageArgs = new ArrayList<>(args);
        String cursor = "";
        if (after != null && !after.isBlank()) {
            cursor = " AND (i.sku,i.id) > (SELECT k.sku,k.id FROM items k WHERE k.tenant_id=? AND k.id=?)";
            pageArgs.addAll(List.of(a.tenant(), after));
        }
        pageArgs.add(limit + 1);
        var rows = s.list(
                "SELECT " + COLUMNS + " FROM items i " + JOINS + STOCK + " WHERE " + where + cursor
                        + " ORDER BY i.sku,i.id LIMIT ?",
                pageArgs.toArray());
        boolean more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        var facets = new LinkedHashMap<String, Object>();
        long activeCount = 0;
        long inactiveCount = 0;
        for (var t : TYPES) facets.put(t, 0L);
        for (var r : s.list(
                "SELECT type,active,COUNT(*) AS n FROM items WHERE tenant_id=? GROUP BY type,active", a.tenant())) {
            long n = ((Number) r.get("n")).longValue();
            facets.merge(str(r, "type"), n, (x, y) -> (Long) x + (Long) y);
            if (Boolean.TRUE.equals(r.get("active"))) activeCount += n;
            else inactiveCount += n;
        }
        facets.put("active", activeCount);
        facets.put("inactive", inactiveCount);
        var result = new LinkedHashMap<String, Object>();
        result.put("items", page);
        result.put("total", total);
        result.put("next", more ? page.getLast().get("id") : "");
        result.put("facets", facets);
        return result;
    }

    // ------------------------------------------------------------------ item detail

    public Map<String, Object> detail(Actor a, String id) {
        var t = a.tenant();
        var item = s.one(
                "SELECT " + COLUMNS + ",u.kind AS unit_kind FROM items i " + JOINS + STOCK
                        + " WHERE i.tenant_id=? AND i.id=?",
                t,
                id);
        boolean moved = items.moved(a, id);
        boolean referenced = moved || items.referenced(a, id);
        var usage = new LinkedHashMap<String, Object>();
        usage.put("movements", moved);
        usage.put("referenced", referenced);
        usage.put("skuLocked", referenced);
        usage.put("unitLocked", referenced);
        usage.put("typeLocked", true);

        var result = new LinkedHashMap<String, Object>();
        result.put("item", item);
        result.put("usage", usage);
        result.put("categoryPath", categoryPath(a, (String) item.get("category_id")));
        result.put("units", units.itemUnits(a, item));
        result.put("conversions", s.list("""
                SELECT c.id,c.unit_code,c.factor,c.purchase,c.sales,c.created_at,c.updated_at,u.name AS unit_name,
                       u.kind AS unit_kind,u.decimals AS unit_decimals,u.active AS unit_active
                FROM item_unit_conversions c
                JOIN units u ON u.tenant_id=c.tenant_id AND u.code=c.unit_code
                WHERE c.tenant_id=? AND c.item_id=?
                ORDER BY c.factor,c.unit_code""", t, id));
        result.put("barcodes", barcodes(a, id, str(item, "uom")));
        result.put("variants", family(a, item));
        result.put("stock", stock(a, id));
        result.put("whereUsed", s.list("""
                SELECT b.id AS bom_id,b.revision,b.status,b.output_qty,l.quantity,p.id AS product_id,p.sku AS product_sku,
                       p.name AS product,p.uom AS product_uom,p.active AS product_active
                FROM bom_lines l
                JOIN boms b ON b.tenant_id=l.tenant_id AND b.id=l.bom_id
                JOIN items p ON p.tenant_id=b.tenant_id AND p.id=b.product_id
                WHERE l.tenant_id=? AND l.item_id=?
                ORDER BY p.sku,b.revision DESC
                LIMIT 100""", t, id));
        result.put("producedBy", s.list("""
                SELECT b.id AS bom_id,b.revision,b.status,b.output_qty,b.yield_factor,b.created_at,
                       (SELECT COUNT(*) FROM bom_lines l WHERE l.tenant_id=b.tenant_id AND l.bom_id=b.id) AS components
                FROM boms b
                WHERE b.tenant_id=? AND b.product_id=?
                ORDER BY b.created_at DESC
                LIMIT 50""", t, id));
        result.put("movements", movements(a, id, 15, null).get("items"));
        result.put("activity", s.list("""
                SELECT id,actor,action,reference,detail,created_at FROM audit_events
                WHERE tenant_id=? AND reference=? AND (action LIKE 'ITEM%')
                ORDER BY created_at DESC,id DESC
                LIMIT 25""", t, item.get("sku")));
        return result;
    }

    /** Ledger movements of the item, newest first (keyset cursor {@code before} = last ledger id of the page). */
    public Map<String, Object> movements(Actor a, String id, int limit, String before) {
        require(limit >= 1 && limit <= 200, "Choose a page size between 1 and 200");
        s.one("SELECT id FROM items WHERE tenant_id=? AND id=?", a.tenant(), id);
        var rows = s.list("""
                SELECT e.id,e.delta,e.uom,e.value_delta,e.unit_cost,e.reference,e.reason,e.actor,e.created_at,
                       l.number AS lot,l.status AS lot_status,w.name AS warehouse
                FROM stock_ledger e
                JOIN lots l ON l.tenant_id=e.tenant_id AND l.id=e.lot_id
                JOIN warehouses w ON w.tenant_id=e.tenant_id AND w.id=e.warehouse_id
                WHERE e.tenant_id=? AND e.item_id=?
                  AND (?::text IS NULL OR (e.created_at,e.id) <
                       (SELECT c.created_at,c.id FROM stock_ledger c WHERE c.tenant_id=e.tenant_id AND c.id=?))
                ORDER BY e.created_at DESC,e.id DESC
                LIMIT ?""", a.tenant(), id, before, before, limit + 1);
        boolean more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        return Map.of("items", page, "next", more ? page.getLast().get("id") : "");
    }

    /** Units a quantity of the item can be entered in, with factors to the base unit. */
    public Map<String, Object> itemUnits(Actor a, String id) {
        var item = s.one(
                "SELECT i.id,i.sku,i.uom,u.decimals,u.name AS unit_name FROM items i JOIN units u ON u.tenant_id=i.tenant_id AND u.code=i.uom WHERE i.tenant_id=? AND i.id=?",
                a.tenant(),
                id);
        return Map.of(
                "itemId",
                id,
                "baseUnit",
                item.get("uom"),
                "baseDecimals",
                item.get("decimals"),
                "units",
                units.itemUnits(a, item));
    }

    private Map<String, Object> stock(Actor a, String id) {
        var t = a.tenant();
        var result = new LinkedHashMap<String, Object>();
        result.put("warehouses", s.list("""
                SELECT w.id,w.name AS warehouse,w.kind,
                       SUM(b.quantity) AS on_hand,SUM(b.reserved) AS reserved,
                       SUM(CASE WHEN l.status='RELEASED' THEN b.quantity-b.reserved ELSE 0 END) AS available,
                       SUM(CASE WHEN l.status='QUARANTINE' THEN b.quantity ELSE 0 END) AS quarantine,
                       SUM(b.value_amount) AS stock_value
                FROM lots l
                JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                JOIN warehouses w ON w.tenant_id=l.tenant_id AND w.id=l.warehouse_id
                WHERE l.tenant_id=? AND l.item_id=?
                GROUP BY w.id,w.name,w.kind
                HAVING SUM(b.quantity)<>0 OR SUM(b.reserved)<>0
                ORDER BY w.name""", t, id));
        result.put("lots", s.list("""
                SELECT l.id,l.number,l.status,l.source_type,l.unit_cost,l.created_at,w.name AS warehouse,
                       b.quantity,b.reserved,b.value_amount
                FROM lots l
                JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id
                JOIN warehouses w ON w.tenant_id=l.tenant_id AND w.id=l.warehouse_id
                WHERE l.tenant_id=? AND l.item_id=? AND b.quantity>0
                ORDER BY l.created_at,l.id
                LIMIT 100""", t, id));
        return result;
    }

    private List<Map<String, Object>> barcodes(Actor a, String id, String baseUnit) {
        var rows = s.list("""
                SELECT b.id,b.barcode,b.unit_code,b.created_at,u.name AS unit_name
                FROM item_barcodes b
                LEFT JOIN units u ON u.tenant_id=b.tenant_id AND u.code=b.unit_code
                WHERE b.tenant_id=? AND b.item_id=?
                ORDER BY b.created_at,b.barcode""", a.tenant(), id);
        for (var r : rows) {
            String unit = r.get("unit_code") == null ? baseUnit : str(r, "unit_code");
            r.put("base_quantity", scanQuantity(a, id, unit));
        }
        return rows;
    }

    /** Base units represented by one scan in {@code unit}, or null when the conversion was removed. */
    private Object scanQuantity(Actor a, String itemId, String unit) {
        try {
            return units.factor(a, itemId, unit);
        } catch (ResponseStatusException e) {
            return null;
        }
    }

    private Map<String, Object> family(Actor a, Map<String, Object> item) {
        if (item.get("template_id") == null) return null;
        var template = variants.template(a, str(item, "template_id"));
        var result = new LinkedHashMap<String, Object>();
        result.put("templateId", template.get("id"));
        result.put("code", template.get("code"));
        result.put("name", template.get("name"));
        result.put("attributes", variants.attributes(str(template, "attributes_json")));
        result.put("items", variantRows(a, str(template, "id")));
        return result;
    }

    private List<Map<String, Object>> variantRows(Actor a, String templateId) {
        var rows = s.list(
                "SELECT i.id,i.sku,i.name,i.active,i.standard_cost,i.uom,i.variant_key,i.variant_label,st.on_hand,st.available,st.stock_value FROM items i "
                        + STOCK
                        + " WHERE i.tenant_id=? AND i.template_id=? ORDER BY i.sku",
                a.tenant(),
                templateId);
        for (var r : rows) r.put("values", variants.values(str(r, "variant_key")));
        return rows;
    }

    private List<Map<String, Object>> categoryPath(Actor a, String categoryId) {
        var path = new ArrayList<Map<String, Object>>();
        String cursor = categoryId;
        for (int depth = 0; cursor != null && depth < 50; depth++) {
            var rows = s.list(
                    "SELECT id,name,parent_id,active FROM item_categories WHERE tenant_id=? AND id=?",
                    a.tenant(),
                    cursor);
            if (rows.isEmpty()) break;
            path.addFirst(rows.getFirst());
            cursor = (String) rows.getFirst().get("parent_id");
        }
        return path;
    }

    // ------------------------------------------------------------------ barcode lookup

    /** The item and quantity one scan of {@code code} represents (404 when no item of the tenant has it). */
    public Map<String, Object> barcode(Actor a, String code) {
        var rows = s.list(
                "SELECT b.id,b.barcode,b.unit_code,b.item_id FROM item_barcodes b WHERE b.tenant_id=? AND b.barcode=?",
                a.tenant(),
                code == null ? "" : code.trim());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No item has barcode " + code);
        var b = rows.getFirst();
        var item = s.one(
                "SELECT " + COLUMNS + " FROM items i " + JOINS + STOCK + " WHERE i.tenant_id=? AND i.id=?",
                a.tenant(),
                b.get("item_id"));
        String unit = b.get("unit_code") == null ? str(item, "uom") : str(b, "unit_code");
        var result = new LinkedHashMap<String, Object>();
        result.put("barcode", b.get("barcode"));
        result.put("unit", unit);
        result.put("baseQuantity", scanQuantity(a, str(item, "id"), unit));
        result.put("baseUnit", item.get("uom"));
        result.put("item", item);
        return result;
    }

    // ------------------------------------------------------------------ units, categories, families

    public Map<String, Object> units(Actor a) {
        return Map.of("items", s.list("""
                SELECT u.code,u.name,u.kind,u.decimals,u.ratio,u.system,u.active,
                       (SELECT COUNT(*) FROM items i WHERE i.tenant_id=u.tenant_id AND i.uom=u.code) AS base_items,
                       (SELECT COUNT(*) FROM item_unit_conversions c
                        WHERE c.tenant_id=u.tenant_id AND c.unit_code=u.code) AS conversions
                FROM units u
                WHERE u.tenant_id=?
                ORDER BY CASE u.kind WHEN 'COUNT' THEN 0 WHEN 'MASS' THEN 1 WHEN 'VOLUME' THEN 2 WHEN 'LENGTH' THEN 3
                         WHEN 'AREA' THEN 4 ELSE 5 END,u.ratio NULLS LAST,u.code
                LIMIT 500""", a.tenant()));
    }

    /** Categories in tree order with depth, full path and item counts (direct and including sub-categories). */
    public Map<String, Object> categories(Actor a) {
        return Map.of("items", s.list("""
                WITH RECURSIVE tree AS (
                    SELECT c.id,c.tenant_id,c.name,c.description,c.parent_id,c.active,c.created_at,0 AS depth,
                           c.name::text AS path,ARRAY[lower(c.name)] AS sort_path,ARRAY[c.id] AS trail
                    FROM item_categories c
                    WHERE c.tenant_id=? AND c.parent_id IS NULL
                    UNION ALL
                    SELECT c.id,c.tenant_id,c.name,c.description,c.parent_id,c.active,c.created_at,t.depth+1,
                           t.path || ' / ' || c.name,t.sort_path || lower(c.name),t.trail || c.id
                    FROM item_categories c
                    JOIN tree t ON t.tenant_id=c.tenant_id AND c.parent_id=t.id
                    WHERE NOT c.id = ANY(t.trail))
                SELECT t.id,t.name,t.description,t.parent_id,t.active,t.created_at,t.depth,t.path,
                       p.name AS parent,
                       (SELECT COUNT(*) FROM items i WHERE i.tenant_id=t.tenant_id AND i.category_id=t.id) AS items,
                       (SELECT COUNT(*) FROM items i WHERE i.tenant_id=t.tenant_id AND i.category_id IN
                            (SELECT d.id FROM tree d WHERE t.id = ANY(d.trail))) AS total_items
                FROM tree t
                LEFT JOIN item_categories p ON p.tenant_id=t.tenant_id AND p.id=t.parent_id
                ORDER BY t.sort_path
                LIMIT 500""", a.tenant()));
    }

    public Map<String, Object> templates(Actor a) {
        var rows = s.list("""
                SELECT t.id,t.code,t.name,t.description,t.type,t.uom,t.category_id,t.created_at,t.updated_at,
                       t.attributes::text AS attributes_json,c.name AS category,
                       (SELECT COUNT(*) FROM items i WHERE i.tenant_id=t.tenant_id AND i.template_id=t.id) AS variants,
                       (SELECT COUNT(*) FROM items i WHERE i.tenant_id=t.tenant_id AND i.template_id=t.id
                          AND i.active) AS active_variants
                FROM item_templates t
                LEFT JOIN item_categories c ON c.tenant_id=t.tenant_id AND c.id=t.category_id
                WHERE t.tenant_id=?
                ORDER BY t.code
                LIMIT 250""", a.tenant());
        for (var r : rows) r.put("attributes", variants.attributes(Objects.toString(r.remove("attributes_json"))));
        return Map.of("items", rows);
    }

    public Map<String, Object> template(Actor a, String id) {
        var template = s.one("""
                SELECT t.id,t.code,t.name,t.description,t.type,t.uom,t.category_id,t.created_at,t.updated_at,
                       t.attributes::text AS attributes_json,c.name AS category,u.name AS unit_name
                FROM item_templates t
                JOIN units u ON u.tenant_id=t.tenant_id AND u.code=t.uom
                LEFT JOIN item_categories c ON c.tenant_id=t.tenant_id AND c.id=t.category_id
                WHERE t.tenant_id=? AND t.id=?""", a.tenant(), id);
        template.put("attributes", variants.attributes(Objects.toString(template.remove("attributes_json"))));
        return Map.of("template", template, "variants", variantRows(a, id));
    }
}
