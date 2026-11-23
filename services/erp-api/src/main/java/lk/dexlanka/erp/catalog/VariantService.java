package lk.dexlanka.erp.catalog;

import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Variant families. A family ({@code item_templates}) holds the shared type, base unit, category and the attribute
 * value lists; it is never stocked or used on documents. Each generated variant is an ordinary item with its own SKU,
 * cost and stock, linked by {@code items.template_id} and identified by its attribute values ({@code variant_key}).
 * PLANNER.
 */
@Service
public class VariantService {
    private final Store s;
    private final ItemService items;
    private final UnitService units;
    private final ObjectMapper json;

    VariantService(Store s, ItemService items, UnitService units, ObjectMapper json) {
        this.s = s;
        this.items = items;
        this.units = units;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> create(Actor a, ItemCommands.Template c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "item-template", c, () -> {
            String code = c.code().trim();
            require(
                    s.list("SELECT 1 FROM item_templates WHERE tenant_id=? AND lower(code)=lower(?)", a.tenant(), code)
                            .isEmpty(),
                    "Variant family " + code + " already exists");
            units.activeUnit(a, c.uom());
            String category = ItemService.blankToNull(c.categoryId());
            if (category != null) items.activeCategory(a, category);
            var attributes = normalize(c.attributes());
            String id = id();
            s.db().update(
                            "INSERT INTO item_templates(id,tenant_id,code,name,description,type,uom,category_id,attributes) VALUES(?,?,?,?,?,?,?,?,?::jsonb)",
                            id,
                            a.tenant(),
                            code,
                            c.name().trim(),
                            c.description() == null ? "" : c.description().trim(),
                            c.type(),
                            c.uom(),
                            category,
                            s.encode(attributes));
            s.audit(a, "ITEM_TEMPLATE_CREATED", code, c.name().trim() + " · " + describe(attributes));
            return Map.of("id", id);
        });
    }

    @Transactional
    public Map<String, Object> update(Actor a, String id, ItemCommands.EditTemplate c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "edit-item-template:" + id, c, () -> {
            var template = template(a, id);
            var attributes = normalize(c.attributes());
            var variants = s.list(
                    "SELECT sku,variant_key FROM items WHERE tenant_id=? AND template_id=? ORDER BY sku",
                    a.tenant(),
                    id);
            if (!variants.isEmpty()) {
                var oldNames = names(attributes(str(template, "attributes_json")));
                require(
                        oldNames.equals(names(attributes)),
                        "Attributes are fixed once variants exist; you can add values but not attributes");
                var allowed = new LinkedHashMap<String, Set<String>>();
                for (var attr : attributes) allowed.put(str(attr, "name"), new HashSet<>(values(attr)));
                for (var v : variants)
                    for (var e : values(str(v, "variant_key")).entrySet())
                        require(
                                allowed.get(e.getKey()).contains(e.getValue()),
                                e.getKey() + " " + e.getValue() + " is used by SKU " + v.get("sku")
                                        + "; it cannot be removed");
            }
            String category = ItemService.blankToNull(c.categoryId());
            if (category != null && !category.equals(template.get("category_id"))) items.activeCategory(a, category);
            s.db().update(
                            "UPDATE item_templates SET name=?,description=?,category_id=?,attributes=?::jsonb,updated_at=now() WHERE tenant_id=? AND id=?",
                            c.name().trim(),
                            c.description() == null ? "" : c.description().trim(),
                            category,
                            s.encode(attributes),
                            a.tenant(),
                            id);
            s.audit(a, "ITEM_TEMPLATE_UPDATED", str(template, "code"), describe(attributes));
            return Map.of("id", id);
        });
    }

    /** Creates variant items for the given attribute combinations (each must be new for the family). */
    @Transactional
    public Map<String, Object> generate(Actor a, String id, ItemCommands.GenerateVariants c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "generate-variants:" + id, c, () -> {
            var template = template(a, id);
            var attributes = attributes(str(template, "attributes_json"));
            var unit = units.unit(a, str(template, "uom"));
            var existing = new HashSet<String>();
            for (var v : s.list("SELECT variant_key FROM items WHERE tenant_id=? AND template_id=?", a.tenant(), id))
                existing.add(str(v, "variant_key"));
            var skus = new HashSet<String>();
            var created = new ArrayList<Map<String, Object>>();
            for (var v : c.variants()) {
                var chosen = new TreeMap<String, String>();
                var label = new ArrayList<String>();
                for (var attr : attributes) {
                    String name = str(attr, "name");
                    String value = v.values().get(name);
                    require(
                            value != null && values(attr).contains(value.trim()),
                            "Choose one of " + String.join(", ", values(attr)) + " for " + name);
                    chosen.put(name, value.trim());
                    label.add(value.trim());
                }
                require(
                        v.values().size() == attributes.size(),
                        "A variant has one value per family attribute: " + String.join(", ", names(attributes)));
                String variantKey = s.encode(chosen);
                String variantLabel = String.join(" · ", label);
                require(existing.add(variantKey), "Variant " + variantLabel + " already exists in this family");
                String sku = v.sku().trim();
                require(skus.add(sku.toLowerCase(Locale.ROOT)), "SKU " + sku + " appears twice in this request");
                items.requireNewSku(a, sku, null);
                BigDecimal safety = v.reorderLevel() == null ? BigDecimal.ZERO : v.reorderLevel();
                UnitService.precision(unit, safety, "Safety stock");
                String name = ItemService.blankToNull(v.name());
                if (name == null) name = str(template, "name") + " · " + variantLabel;
                String itemId = id();
                items.insert(
                        a,
                        itemId,
                        sku,
                        name,
                        str(template, "type"),
                        str(template, "uom"),
                        v.standardCost(),
                        safety,
                        str(template, "description"),
                        (String) template.get("category_id"),
                        BigDecimal.ZERO,
                        0,
                        id,
                        variantKey,
                        variantLabel);
                s.audit(a, "ITEM_CREATED", sku, name + " · variant of " + template.get("code"));
                created.add(Map.of("id", itemId, "sku", sku));
            }
            s.audit(
                    a,
                    "ITEM_VARIANTS_GENERATED",
                    str(template, "code"),
                    created.size() + " variant" + (created.size() == 1 ? "" : "s") + " created");
            return Map.of("id", id, "created", created);
        });
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> template(Actor a, String id) {
        return s.one(
                "SELECT t.*,t.attributes::text AS attributes_json FROM item_templates t WHERE t.tenant_id=? AND t.id=?",
                a.tenant(),
                id);
    }

    /** Trims names/values and rejects duplicates (case-insensitive). */
    private static List<Map<String, Object>> normalize(List<ItemCommands.Attribute> input) {
        var names = new HashSet<String>();
        var result = new ArrayList<Map<String, Object>>();
        for (var attr : input) {
            String name = attr.name().trim();
            require(names.add(name.toLowerCase(Locale.ROOT)), "Attribute " + name + " appears twice");
            var seen = new HashSet<String>();
            var values = new ArrayList<String>();
            for (var raw : attr.values()) {
                String value = raw.trim();
                require(seen.add(value.toLowerCase(Locale.ROOT)), name + " value " + value + " appears twice");
                values.add(value);
            }
            var m = new LinkedHashMap<String, Object>();
            m.put("name", name);
            m.put("values", values);
            result.add(m);
        }
        return result;
    }

    List<Map<String, Object>> attributes(String text) {
        try {
            return json.readValue(text, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, String> values(String variantKey) {
        try {
            return json.readValue(variantKey, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> values(Map<String, Object> attribute) {
        return (List<String>) attribute.get("values");
    }

    private static List<String> names(List<Map<String, Object>> attributes) {
        return attributes.stream().map(x -> str(x, "name")).sorted().toList();
    }

    private static String describe(List<Map<String, Object>> attributes) {
        var parts = new ArrayList<String>();
        for (var attr : attributes) parts.add(str(attr, "name") + ": " + String.join("/", values(attr)));
        return String.join("; ", parts);
    }
}
