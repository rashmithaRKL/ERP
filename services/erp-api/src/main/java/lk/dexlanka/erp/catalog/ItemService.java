package lk.dexlanka.erp.catalog;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Item master commands: creation, editing and lifecycle, item unit conversions and barcodes. Item master changes are
 * for PLANNER (and ADMIN).
 *
 * <p>Rules: standard-cost changes only affect estimates and future snapshots (posted lot, ledger and order values are
 * never rewritten); an item's SKU, base unit and type are fixed once it has stock movements or document references
 * (also enforced by the {@code item_identity_guard} trigger); inactive items stay visible in history but cannot be used
 * on new documents or BOMs.
 */
@Service
public class ItemService {
    private final Store s;
    private final UnitService units;

    ItemService(Store s, UnitService units) {
        this.s = s;
        this.units = units;
    }

    // ------------------------------------------------------------------ create / edit / lifecycle

    /** {@code POST /api/items} (routed through {@link CatalogService#item}). */
    @Transactional
    public Map<String, Object> create(Actor a, CatalogCommands.Item c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "item", c, () -> {
            var unit = units.activeUnit(a, c.uom());
            BigDecimal reorderQty = c.reorderQuantity() == null ? BigDecimal.ZERO : c.reorderQuantity();
            UnitService.precision(unit, c.reorderLevel(), "Safety stock");
            UnitService.precision(unit, reorderQty, "Reorder quantity");
            String sku = c.sku().trim();
            requireNewSku(a, sku, null);
            String category = blankToNull(c.categoryId());
            if (category != null) activeCategory(a, category);
            String id = id();
            insert(
                    a,
                    id,
                    sku,
                    c.name().trim(),
                    c.type(),
                    c.uom(),
                    c.standardCost(),
                    c.reorderLevel(),
                    c.description(),
                    category,
                    reorderQty,
                    c.leadTimeDays() == null ? 0 : c.leadTimeDays(),
                    null,
                    null,
                    null);
            s.audit(a, "ITEM_CREATED", sku, c.name().trim());
            return Map.of("id", id);
        });
    }

    @Transactional
    public Map<String, Object> edit(Actor a, String id, ItemCommands.EditItem c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "edit-item:" + id, c, () -> {
            var item = item(a, id);
            String sku = c.sku().trim();
            String name = c.name().trim();
            String description = c.description() == null ? "" : c.description().trim();
            String oldSku = str(item, "sku");
            boolean skuChanged = !sku.equals(oldSku);
            boolean unitChanged = !c.uom().equals(item.get("uom"));
            if (skuChanged || unitChanged) {
                boolean moved = moved(a, id);
                boolean used = moved || referenced(a, id);
                require(
                        !skuChanged || !used,
                        "SKU " + oldSku + " is locked because the item already has stock movements or document"
                                + " references");
                require(!unitChanged || !moved, "The base unit cannot change after stock movements exist");
                require(!unitChanged || !used, "The base unit is locked because documents or BOMs already use it");
            }
            if (skuChanged) requireNewSku(a, sku, id);
            var unit = unitChanged ? units.activeUnit(a, c.uom()) : units.unit(a, c.uom());
            if (unitChanged)
                require(
                        s.list("""
                                        SELECT 1 FROM item_unit_conversions WHERE tenant_id=? AND item_id=?
                                        UNION ALL
                                        SELECT 1 FROM item_barcodes WHERE tenant_id=? AND item_id=? AND unit_code IS NOT NULL
                                        LIMIT 1""", a.tenant(), id, a.tenant(), id).isEmpty(),
                        "Remove this item's unit conversions and unit barcodes before changing its base unit");
            UnitService.precision(unit, c.reorderLevel(), "Safety stock");
            UnitService.precision(unit, c.reorderQuantity(), "Reorder quantity");
            String category = blankToNull(c.categoryId());
            if (category != null && !category.equals(item.get("category_id"))) activeCategory(a, category);

            var changes = new ArrayList<String>();
            if (skuChanged) changes.add("SKU " + oldSku + " → " + sku);
            if (!name.equals(item.get("name"))) changes.add("name " + item.get("name") + " → " + name);
            if (!description.equals(str(item, "description"))) changes.add("description updated");
            if (!Objects.equals(category, item.get("category_id")))
                changes.add("category " + categoryName(a, item.get("category_id")) + " → " + categoryName(a, category));
            if (unitChanged) changes.add("base unit " + item.get("uom") + " → " + c.uom());
            if (dec(item.get("standard_cost")).compareTo(c.standardCost()) != 0)
                changes.add("standard cost " + plain(item.get("standard_cost")) + " → " + plain(c.standardCost())
                        + " (estimates and new snapshots only; posted values unchanged)");
            if (dec(item.get("reorder_level")).compareTo(c.reorderLevel()) != 0)
                changes.add("safety stock " + plain(item.get("reorder_level")) + " → " + plain(c.reorderLevel()));
            if (dec(item.get("reorder_qty")).compareTo(c.reorderQuantity()) != 0)
                changes.add("reorder quantity " + plain(item.get("reorder_qty")) + " → " + plain(c.reorderQuantity()));
            if (((Number) item.get("lead_time_days")).intValue() != c.leadTimeDays())
                changes.add("lead time " + item.get("lead_time_days") + " → " + c.leadTimeDays() + " days");

            s.db().update(
                            "UPDATE items SET sku=?,name=?,description=?,category_id=?,uom=?,standard_cost=?,reorder_level=?,reorder_qty=?,lead_time_days=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            sku,
                            name,
                            description,
                            category,
                            c.uom(),
                            c.standardCost(),
                            c.reorderLevel(),
                            c.reorderQuantity(),
                            c.leadTimeDays(),
                            a.tenant(),
                            id);
            s.audit(a, "ITEM_UPDATED", sku, changes.isEmpty() ? "Saved without changes" : String.join("; ", changes));
            return Map.of("id", id, "status", changes.isEmpty() ? "UNCHANGED" : "UPDATED", "changes", changes);
        });
    }

    @Transactional
    public Map<String, Object> deactivate(Actor a, String id, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "deactivate-item:" + id, Map.of(), () -> {
            var item = item(a, id);
            require(Boolean.TRUE.equals(item.get("active")), "This item is already inactive");
            s.db().update("UPDATE items SET active=false,updated_at=now() WHERE tenant_id=? AND id=?", a.tenant(), id);
            s.audit(
                    a,
                    "ITEM_DEACTIVATED",
                    str(item, "sku"),
                    "Blocked for new documents and BOMs; stock, open orders and history are unchanged");
            return Map.of("id", id, "active", false);
        });
    }

    @Transactional
    public Map<String, Object> activate(Actor a, String id, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "activate-item:" + id, Map.of(), () -> {
            var item = item(a, id);
            require(!Boolean.TRUE.equals(item.get("active")), "This item is already active");
            s.db().update("UPDATE items SET active=true,updated_at=now() WHERE tenant_id=? AND id=?", a.tenant(), id);
            s.audit(a, "ITEM_REACTIVATED", str(item, "sku"), "Available for new documents and BOMs again");
            return Map.of("id", id, "active", true);
        });
    }

    // ------------------------------------------------------------------ unit conversions

    @Transactional
    public Map<String, Object> setConversion(Actor a, String id, ItemCommands.Conversion c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "item-conversion:" + id, c, () -> {
            var item = item(a, id);
            String baseCode = str(item, "uom");
            require(!c.unit().equals(baseCode), "The base unit " + baseCode + " needs no conversion");
            var unit = units.unit(a, c.unit());
            var base = units.unit(a, baseCode);
            require(
                    UnitService.standardFactor(unit, base) == null,
                    c.unit() + " converts to " + baseCode + " by a fixed ratio; no item conversion is needed");
            require(
                    ((Number) base.get("decimals")).intValue() > 0
                            || c.factor().stripTrailingZeros().scale() <= 0,
                    "One " + c.unit() + " must hold a whole number of " + baseCode);
            BigDecimal factor = c.factor().stripTrailingZeros();
            var existing = s.list(
                    "SELECT * FROM item_unit_conversions WHERE tenant_id=? AND item_id=? AND unit_code=?",
                    a.tenant(),
                    id,
                    c.unit());
            String usage = usage(c.purchase(), c.sales());
            String conversionId;
            if (existing.isEmpty()) {
                require(Boolean.TRUE.equals(unit.get("active")), "Unit " + c.unit() + " is inactive");
                conversionId = id();
                s.db().update(
                                "INSERT INTO item_unit_conversions(id,tenant_id,item_id,unit_code,factor,purchase,sales) VALUES(?,?,?,?,?,?,?)",
                                conversionId,
                                a.tenant(),
                                id,
                                c.unit(),
                                factor,
                                c.purchase(),
                                c.sales());
                s.audit(
                        a,
                        "ITEM_CONVERSION_SET",
                        str(item, "sku"),
                        "1 " + c.unit() + " = " + factor.toPlainString() + " " + baseCode + " · " + usage);
            } else {
                var old = existing.getFirst();
                conversionId = str(old, "id");
                s.db().update(
                                "UPDATE item_unit_conversions SET factor=?,purchase=?,sales=?,updated_at=now() WHERE tenant_id=? AND id=?",
                                factor,
                                c.purchase(),
                                c.sales(),
                                a.tenant(),
                                conversionId);
                s.audit(
                        a,
                        "ITEM_CONVERSION_SET",
                        str(item, "sku"),
                        "1 " + c.unit() + ": " + plain(old.get("factor")) + " → " + factor.toPlainString() + " "
                                + baseCode + " · " + usage + " (posted base quantities unchanged)");
            }
            return Map.of("id", conversionId, "unit", c.unit(), "factor", factor);
        });
    }

    @Transactional
    public Map<String, Object> removeConversion(Actor a, String id, String unit, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "remove-item-conversion:" + id + ":" + unit, Map.of(), () -> {
            var item = item(a, id);
            var conversion = s.one(
                    "SELECT * FROM item_unit_conversions WHERE tenant_id=? AND item_id=? AND unit_code=?",
                    a.tenant(),
                    id,
                    unit);
            require(
                    s.list(
                                    "SELECT 1 FROM item_barcodes WHERE tenant_id=? AND item_id=? AND unit_code=?",
                                    a.tenant(),
                                    id,
                                    unit)
                            .isEmpty(),
                    "A barcode of this item scans as " + unit + "; remove that barcode first");
            s.db().update(
                            "DELETE FROM item_unit_conversions WHERE tenant_id=? AND id=?",
                            a.tenant(),
                            conversion.get("id"));
            s.audit(
                    a,
                    "ITEM_CONVERSION_REMOVED",
                    str(item, "sku"),
                    "1 " + unit + " = " + plain(conversion.get("factor")) + " " + item.get("uom") + " removed");
            return Map.of("id", conversion.get("id"), "status", "REMOVED");
        });
    }

    // ------------------------------------------------------------------ barcodes

    @Transactional
    public Map<String, Object> addBarcode(Actor a, String id, ItemCommands.Barcode c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "item-barcode:" + id, c, () -> {
            var item = item(a, id);
            var taken = s.list(
                    "SELECT i.sku FROM item_barcodes b JOIN items i ON i.tenant_id=b.tenant_id AND i.id=b.item_id WHERE b.tenant_id=? AND b.barcode=?",
                    a.tenant(),
                    c.barcode());
            require(
                    taken.isEmpty(),
                    "Barcode " + c.barcode() + " is already assigned to SKU "
                            + (taken.isEmpty() ? "" : str(taken.getFirst(), "sku")));
            String unit = blankToNull(c.unit());
            BigDecimal factor = BigDecimal.ONE;
            if (unit != null) factor = units.factor(a, item, units.unit(a, unit), units.unit(a, str(item, "uom")));
            String barcodeId = id();
            s.db().update(
                            "INSERT INTO item_barcodes(id,tenant_id,item_id,barcode,unit_code) VALUES(?,?,?,?,?)",
                            barcodeId,
                            a.tenant(),
                            id,
                            c.barcode(),
                            unit);
            s.audit(
                    a,
                    "ITEM_BARCODE_ADDED",
                    str(item, "sku"),
                    c.barcode() + " = "
                            + (unit == null
                                    ? "1 " + item.get("uom")
                                    : "1 " + unit + " (" + factor.toPlainString() + " " + item.get("uom") + ")"));
            return Map.of("id", barcodeId, "barcode", c.barcode());
        });
    }

    @Transactional
    public Map<String, Object> removeBarcode(Actor a, String id, String barcodeId, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "remove-item-barcode:" + barcodeId, Map.of(), () -> {
            var item = item(a, id);
            var barcode = s.one(
                    "SELECT * FROM item_barcodes WHERE tenant_id=? AND item_id=? AND id=?", a.tenant(), id, barcodeId);
            s.db().update("DELETE FROM item_barcodes WHERE tenant_id=? AND id=?", a.tenant(), barcodeId);
            s.audit(a, "ITEM_BARCODE_REMOVED", str(item, "sku"), str(barcode, "barcode") + " removed");
            return Map.of("id", barcodeId, "status", "REMOVED");
        });
    }

    // ------------------------------------------------------------------ shared helpers (also used by VariantService)

    Map<String, Object> item(Actor a, String id) {
        return s.one("SELECT * FROM items WHERE tenant_id=? AND id=?", a.tenant(), id);
    }

    /** True when stock has moved for the item (the ledger stores base units). */
    boolean moved(Actor a, String id) {
        return !s.list("SELECT 1 FROM stock_ledger WHERE tenant_id=? AND item_id=? LIMIT 1", a.tenant(), id)
                .isEmpty();
    }

    /** True when any stock or document table references the item (see {@code item_is_referenced} in V4). */
    boolean referenced(Actor a, String id) {
        return Boolean.TRUE.equals(
                s.db().queryForObject("SELECT item_is_referenced(?,?)", Boolean.class, a.tenant(), id));
    }

    void requireNewSku(Actor a, String sku, String except) {
        var rows = s.list(
                "SELECT id FROM items WHERE tenant_id=? AND lower(sku)=lower(?) AND id IS DISTINCT FROM ?",
                a.tenant(),
                sku,
                except);
        require(rows.isEmpty(), "SKU " + sku + " already exists");
    }

    Map<String, Object> activeCategory(Actor a, String categoryId) {
        var category = s.one("SELECT * FROM item_categories WHERE tenant_id=? AND id=?", a.tenant(), categoryId);
        require(Boolean.TRUE.equals(category.get("active")), "Category " + category.get("name") + " is inactive");
        return category;
    }

    void insert(
            Actor a,
            String id,
            String sku,
            String name,
            String type,
            String uom,
            BigDecimal standardCost,
            BigDecimal reorderLevel,
            String description,
            String categoryId,
            BigDecimal reorderQty,
            int leadTimeDays,
            String templateId,
            String variantKey,
            String variantLabel) {
        s.db().update(
                        "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level,description,category_id,reorder_qty,lead_time_days,template_id,variant_key,variant_label) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id,
                        a.tenant(),
                        sku,
                        name,
                        type,
                        uom,
                        standardCost,
                        reorderLevel,
                        description == null ? "" : description.trim(),
                        categoryId,
                        reorderQty,
                        leadTimeDays,
                        templateId,
                        variantKey,
                        variantLabel);
    }

    private String categoryName(Actor a, Object categoryId) {
        if (categoryId == null) return "none";
        var rows = s.list("SELECT name FROM item_categories WHERE tenant_id=? AND id=?", a.tenant(), categoryId);
        return rows.isEmpty() ? "none" : str(rows.getFirst(), "name");
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String plain(Object value) {
        return dec(value).stripTrailingZeros().toPlainString();
    }

    private static String usage(boolean purchase, boolean sales) {
        var uses = new ArrayList<String>(List.of());
        if (purchase) uses.add("purchasing");
        if (sales) uses.add("sales");
        return uses.isEmpty() ? "internal use only" : String.join(" and ", uses);
    }
}
