package lk.dexlanka.erp.admin;

import static lk.dexlanka.erp.admin.ImportKind.choice;
import static lk.dexlanka.erp.admin.ImportKind.decimal;
import static lk.dexlanka.erp.admin.ImportKind.integer;
import static lk.dexlanka.erp.admin.ImportKind.text;

import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.catalog.ItemService;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/** Items through {@link ItemService#create} (the same rules, idempotency and audit as {@code POST /api/items}). */
@Component
class ItemImport implements ImportKind {
    static final List<String> TYPES = List.of("RAW_MATERIAL", "PACKAGING", "SEMI_FINISHED", "FINISHED_GOOD", "SPARE_PART");
    private static final Map<String, String> COLUMNS = Map.of(
            "sku", "sku",
            "name", "name",
            "type", "type",
            "uom", "uom",
            "standardCost", "standard_cost",
            "reorderLevel", "reorder_level",
            "description", "description",
            "reorderQuantity", "reorder_quantity",
            "leadTimeDays", "lead_time_days");

    private final Store s;
    private final ItemService items;
    private final Validator validator;

    ItemImport(Store s, ItemService items, Validator validator) {
        this.s = s;
        this.items = items;
        this.validator = validator;
    }

    @Override
    public String kind() {
        return "ITEMS";
    }

    @Override
    public List<String> required() {
        return List.of("sku", "name", "type", "uom", "standard_cost");
    }

    @Override
    public List<String> optional() {
        return List.of("reorder_level", "reorder_quantity", "lead_time_days", "category", "description");
    }

    @Override
    public String template() {
        return """
                sku,name,type,uom,standard_cost,reorder_level,reorder_quantity,lead_time_days,category,description
                RM-101,PP resin · injection grade,RAW_MATERIAL,kg,410.00,1000,2000,14,,Natural polypropylene
                PK-201,Shrink film 500 mm,PACKAGING,roll,3200.00,10,20,7,,
                FG-301,1 L round bottle,FINISHED_GOOD,pcs,24.50,5000,10000,0,,Example finished good
                """;
    }

    @Override
    public Map<String, Object> row(Context c, CsvFile.Row row) {
        var a = c.actor;
        String sku = text(row, "sku", true, 50);
        String name = text(row, "name", true, 160);
        String type = choice(row, "type", TYPES, null);
        String uom = text(row, "uom", true, 12);
        BigDecimal cost = decimal(row, "standard_cost", true, BigDecimal.ZERO, false);
        BigDecimal reorderLevel = decimal(row, "reorder_level", false, BigDecimal.ZERO, false);
        BigDecimal reorderQty = decimal(row, "reorder_quantity", false, BigDecimal.ZERO, false);
        Integer lead = integer(row, "lead_time_days", 0, 365);
        String description = text(row, "description", false, 2000);
        String categoryName = text(row, "category", false, 80);
        if (!c.seen.add(sku.toLowerCase(Locale.ROOT)))
            throw new RowError("sku", "SKU " + sku + " appears more than once in this file");
        String category = null;
        if (!categoryName.isEmpty()) {
            var rows = s.list(
                    "SELECT id FROM item_categories WHERE tenant_id=? AND lower(name)=lower(?) AND active",
                    a.tenant(),
                    categoryName);
            if (rows.isEmpty()) throw new RowError("category", "No active category named " + categoryName);
            category = rows.getFirst().get("id").toString();
        }
        var command = new CatalogCommands.Item(
                sku,
                name,
                type,
                uom,
                cost,
                reorderLevel == null ? BigDecimal.ZERO : reorderLevel,
                description.isEmpty() ? null : description,
                category,
                reorderQty,
                lead);
        for (var v : validator.validate(command))
            throw new RowError(COLUMNS.get(v.getPropertyPath().toString()), v.getMessage());
        var created = items.create(a, command, c.rowKey(row));
        var preview = new LinkedHashMap<String, Object>();
        preview.put("id", created.get("id"));
        preview.put("sku", sku);
        preview.put("name", name);
        preview.put("type", type);
        preview.put("uom", uom);
        preview.put("standard_cost", cost);
        preview.put("category", categoryName.isEmpty() ? null : categoryName);
        return preview;
    }
}
