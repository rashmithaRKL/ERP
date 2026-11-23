package lk.dexlanka.erp.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/** Item master (V4): editing, units and conversions, categories, barcodes, variants and SEMI_FINISHED items. */
class ItemMasterTest extends IntegrationTestBase {
    @Autowired
    ItemService items;

    @Autowired
    UnitService units;

    @Autowired
    VariantService variants;

    @Autowired
    CategoryService categories;

    @Autowired
    CatalogService catalog;

    @Autowired
    ManufacturingService mo;

    @Autowired
    QualityService quality;

    TestTenant tenant;
    String t;
    Actor admin, planner, warehouse, operator, viewer;

    @BeforeEach
    void fixture() {
        tenant = createTenant("Item master fixture");
        t = tenant.id();
        admin = createUser(t, "ADMIN");
        planner = createUser(t, "PLANNER");
        warehouse = createUser(t, "WAREHOUSE");
        operator = createUser(t, "OPERATOR");
        viewer = createUser(t, "VIEWER");
    }

    // ------------------------------------------------------------------ helpers

    String item(Actor actor, String sku, String type, String uom) throws Exception {
        return str(
                json(command(
                                actor,
                                "/api/items",
                                new CatalogCommands.Item(sku, sku + " item", type, uom, bd("10"), bd("0")))
                        .andExpect(status().isOk())),
                "id");
    }

    ItemCommands.EditItem edit(String id, Map<String, Object> overrides) {
        var i = row("SELECT * FROM items WHERE tenant_id=? AND id=?", t, id);
        return new ItemCommands.EditItem(
                (String) overrides.getOrDefault("sku", i.get("sku")),
                (String) overrides.getOrDefault("name", i.get("name")),
                (String) overrides.getOrDefault("description", i.get("description")),
                (String) overrides.getOrDefault("categoryId", i.get("category_id")),
                (String) overrides.getOrDefault("uom", i.get("uom")),
                (BigDecimal) overrides.getOrDefault("standardCost", dec(i.get("standard_cost"))),
                (BigDecimal) overrides.getOrDefault("reorderLevel", dec(i.get("reorder_level"))),
                (BigDecimal) overrides.getOrDefault("reorderQuantity", dec(i.get("reorder_qty"))),
                (Integer) overrides.getOrDefault("leadTimeDays", ((Number) i.get("lead_time_days")).intValue()));
    }

    String machine() {
        String m = id();
        update(
                "INSERT INTO machines(id,tenant_id,factory_id,code,name,kind,status,hourly_rate) VALUES(?,?,?,?,'Line','Moulding','IDLE',10)",
                m,
                t,
                tenant.plant(),
                "M-" + m.substring(0, 6));
        return m;
    }

    String approvedBom(String product, String material, String qty) {
        String bom = str(
                catalog.bom(
                        planner,
                        new CatalogCommands.Bom(
                                product,
                                "R" + id().substring(0, 4),
                                bd("1"),
                                bd("1"),
                                List.of(new CatalogCommands.BomLine(material, bd(qty)))),
                        id()),
                "id");
        catalog.approveBom(admin, bom, id());
        return bom;
    }

    ManufacturingCommands.Manufacture order(String bom, String machine, String qty) {
        return new ManufacturingCommands.Manufacture(
                bom,
                machine,
                null,
                bd(qty),
                "NORMAL",
                LocalDate.now().plusDays(1).toString());
    }

    // ------------------------------------------------------------------ editing and lifecycle

    @Test
    void editChangesMasterDataAuditsAndNeverRewritesPostedValues() throws Exception {
        String resin = item(planner, "RM-ED", "RAW_MATERIAL", "kg");
        String lot = openingStock(admin, resin, tenant.rawWarehouse(), "10", "10");
        String category = str(categories.create(planner, new ItemCommands.Category("Resins", null, ""), id()), "id");

        var result = json(command(
                        planner,
                        "/api/items/" + resin,
                        edit(
                                resin,
                                Map.of(
                                        "name",
                                        "HDPE resin",
                                        "description",
                                        "Blow moulding grade",
                                        "categoryId",
                                        category,
                                        "standardCost",
                                        bd("12.5"),
                                        "reorderLevel",
                                        bd("100"),
                                        "reorderQuantity",
                                        bd("500"),
                                        "leadTimeDays",
                                        14)))
                .andExpect(status().isOk()));
        assertThat(result.get("status")).isEqualTo("UPDATED");

        var row = row("SELECT * FROM items WHERE tenant_id=? AND id=?", t, resin);
        assertThat(row.get("name")).isEqualTo("HDPE resin");
        assertThat(row.get("category_id")).isEqualTo(category);
        assertThat(dec(row.get("standard_cost"))).isEqualByComparingTo("12.5");
        assertThat(dec(row.get("reorder_qty"))).isEqualByComparingTo("500");
        assertThat(row.get("lead_time_days")).isEqualTo(14);
        // Posted lot cost and ledger value stay at the historical 10.
        assertThat(decimal("SELECT unit_cost FROM lots WHERE tenant_id=? AND id=?", t, lot))
                .isEqualByComparingTo("10");
        assertThat(decimal("SELECT value_amount FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lot))
                .isEqualByComparingTo("100");
        var audit = row(
                "SELECT * FROM audit_events WHERE tenant_id=? AND action='ITEM_UPDATED' ORDER BY created_at DESC LIMIT 1",
                t);
        assertThat(str(audit, "detail"))
                .contains("standard cost 10 → 12.5")
                .contains("posted values unchanged")
                .contains("name RM-ED item → HDPE resin")
                .contains("lead time 0 → 14 days");

        // Lifecycle: deactivate, a second deactivate is a conflict, reactivate.
        command(planner, "/api/items/" + resin + "/deactivate", null).andExpect(status().isOk());
        command(planner, "/api/items/" + resin + "/deactivate", null).andExpect(status().isConflict());
        assertThat(row("SELECT active FROM items WHERE tenant_id=? AND id=?", t, resin)
                        .get("active"))
                .isEqualTo(false);
        command(planner, "/api/items/" + resin + "/activate", null).andExpect(status().isOk());
        assertThat(count(
                        "SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action IN ('ITEM_DEACTIVATED','ITEM_REACTIVATED')",
                        t))
                .isEqualTo(2);
    }

    @Test
    void skuAndBaseUnitAreLockedOnceTheItemIsUsed() throws Exception {
        String fresh = item(planner, "RM-NEW", "RAW_MATERIAL", "kg");
        // Unused: SKU and base unit can still be corrected.
        command(planner, "/api/items/" + fresh, edit(fresh, Map.of("sku", "RM-FIXED", "uom", "g")))
                .andExpect(status().isOk());
        assertThat(row("SELECT sku,uom FROM items WHERE tenant_id=? AND id=?", t, fresh))
                .containsEntry("sku", "RM-FIXED")
                .containsEntry("uom", "g");
        // A duplicate SKU (case-insensitive) is rejected.
        item(planner, "RM-OTHER", "RAW_MATERIAL", "kg");
        command(planner, "/api/items/" + fresh, edit(fresh, Map.of("sku", "rm-other")))
                .andExpect(status().isConflict());

        // After a stock movement: SKU and base unit are locked; other fields stay editable.
        openingStock(admin, fresh, tenant.rawWarehouse(), "5", "2");
        command(planner, "/api/items/" + fresh, edit(fresh, Map.of("sku", "RM-RENAMED")))
                .andExpect(status().isConflict());
        command(planner, "/api/items/" + fresh, edit(fresh, Map.of("uom", "kg")))
                .andExpect(status().isConflict());
        command(planner, "/api/items/" + fresh, edit(fresh, Map.of("name", "Still editable")))
                .andExpect(status().isOk());
        // The database guard rejects a direct change too, and the V1 ledger unit check still works.
        assertThatThrownBy(() -> update("UPDATE items SET uom='kg' WHERE tenant_id=? AND id=?", t, fresh))
                .hasMessageContaining("base unit cannot change");
        assertThat(row("SELECT uom FROM items WHERE tenant_id=? AND id=?", t, fresh)
                        .get("uom"))
                .isEqualTo("g");

        // A document reference without stock (a BOM line) also locks the SKU.
        String fg = item(planner, "FG-LOCK", "FINISHED_GOOD", "pcs");
        String packaging = item(planner, "PK-LOCK", "PACKAGING", "pcs");
        catalog.bom(
                planner,
                new CatalogCommands.Bom(
                        fg, "R1", bd("1"), bd("1"), List.of(new CatalogCommands.BomLine(packaging, bd("1")))),
                id());
        command(planner, "/api/items/" + packaging, edit(packaging, Map.of("sku", "PK-RENAMED")))
                .andExpect(status().isConflict());
        var detail = json(query(planner, "/api/items/" + packaging).andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var usage = (Map<String, Object>) detail.get("usage");
        assertThat(usage).containsEntry("skuLocked", true).containsEntry("movements", false);
    }

    @Test
    void inactiveItemsCannotBeUsedOnNewManufacturingOrdersOrBoms() throws Exception {
        String fg = item(planner, "FG-IN", "FINISHED_GOOD", "pcs");
        String rm = item(planner, "RM-IN", "RAW_MATERIAL", "kg");
        String bom = approvedBom(fg, rm, "1");
        String machine = machine();

        items.deactivate(planner, rm, id());
        assertThatThrownBy(() -> mo.create(planner, order(bom, machine, "5"), id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Inactive materials in this BOM: RM-IN");
        assertThatThrownBy(() -> catalog.bom(
                        planner,
                        new CatalogCommands.Bom(
                                fg, "R9", bd("1"), bd("1"), List.of(new CatalogCommands.BomLine(rm, bd("1")))),
                        id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("inactive");

        items.activate(planner, rm, id());
        assertThat(mo.create(planner, order(bom, machine, "5"), id()).get("status"))
                .isEqualTo("DRAFT");

        items.deactivate(planner, fg, id());
        assertThatThrownBy(() -> mo.create(planner, order(bom, machine, "5"), id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("inactive");
        // Inactive items remain readable with their history.
        var detail = json(query(viewer, "/api/items/" + fg).andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var item = (Map<String, Object>) detail.get("item");
        assertThat(item.get("active")).isEqualTo(false);
        assertThat((List<?>) detail.get("producedBy")).hasSize(1);
    }

    // ------------------------------------------------------------------ units and conversions

    @Test
    void conversionsRoundAtTheBaseUnitPrecisionAndRejectInvalidFactors() throws Exception {
        String resin = item(planner, "RM-CV", "RAW_MATERIAL", "kg");
        String bottle = item(planner, "FG-CV", "FINISHED_GOOD", "pcs");
        assertThat(count("SELECT COUNT(*) FROM units WHERE tenant_id=?", t)).isEqualTo(15); // seeded per tenant

        command(
                        planner,
                        "/api/items/" + resin + "/conversions",
                        new ItemCommands.Conversion("bag", bd("25"), true, false))
                .andExpect(status().isOk());
        var bags = json(query(viewer, "/api/items/" + resin + "/convert?quantity=3&unit=bag")
                .andExpect(status().isOk()));
        assertThat(dec(bags.get("baseQuantity"))).isEqualByComparingTo("75");
        assertThat(bags).containsEntry("baseUnit", "kg").containsEntry("exact", true);
        // Standard same-kind ratio without an item conversion: 1500 g = 1.5 kg.
        assertThat(units.toBase(planner, resin, bd("1500"), "g").baseQuantity()).isEqualByComparingTo("1.5");
        assertThat(units.fromBase(planner, resin, bd("75"), "bag")).isEqualByComparingTo("3");
        // Quantities respect the source unit's precision (bags are whole).
        query(viewer, "/api/items/" + resin + "/convert?quantity=1.5&unit=bag").andExpect(status().isConflict());
        // A unit with no conversion for this item.
        query(viewer, "/api/items/" + resin + "/convert?quantity=1&unit=roll").andExpect(status().isConflict());

        // Rounding: a third of a kilogram per scoop, at the kg precision of six decimals.
        units.create(planner, new ItemCommands.Unit("scoop", "Scoop", "PACKAGE", 2, null), id());
        items.setConversion(
                planner, resin, new ItemCommands.Conversion("scoop", bd("0.333333333333"), false, false), id());
        var scoops = units.toBase(planner, resin, bd("3"), "scoop");
        assertThat(scoops.baseQuantity()).isEqualByComparingTo("1");
        assertThat(scoops.baseQuantity().scale()).isEqualTo(6);
        assertThat(scoops.exact()).isFalse();
        assertThatThrownBy(() -> units.toBaseExact(planner, resin, bd("3"), "scoop"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not a whole multiple");
        // Pieces round to whole pieces: half a "pair-pack" of 3 pcs is 1.5 -> 2 pcs (HALF_UP), flagged inexact.
        units.create(planner, new ItemCommands.Unit("pack3", "Pack of three", "PACKAGE", 1, null), id());
        items.setConversion(planner, bottle, new ItemCommands.Conversion("pack3", bd("3"), false, true), id());
        var half = units.toBase(planner, bottle, bd("0.5"), "pack3");
        assertThat(half.baseQuantity()).isEqualByComparingTo("2");
        assertThat(half.exact()).isFalse();

        // Invalid factors: zero and negative are input errors; structural conflicts are 409.
        command(
                        planner,
                        "/api/items/" + resin + "/conversions",
                        Map.of("unit", "drum", "factor", "0", "purchase", true, "sales", false))
                .andExpect(status().isBadRequest());
        command(
                        planner,
                        "/api/items/" + resin + "/conversions",
                        Map.of("unit", "drum", "factor", "-5", "purchase", true, "sales", false))
                .andExpect(status().isBadRequest());
        command(planner, "/api/items/" + resin + "/conversions", new ItemCommands.Conversion("kg", bd("1"), true, true))
                .andExpect(status().isConflict()); // the base unit
        command(
                        planner,
                        "/api/items/" + resin + "/conversions",
                        new ItemCommands.Conversion("g", bd("0.002"), true, true))
                .andExpect(status().isConflict()); // fixed ratio
        command(
                        planner,
                        "/api/items/" + bottle + "/conversions",
                        new ItemCommands.Conversion("box", bd("50.5"), true, true))
                .andExpect(status().isConflict()); // a box cannot hold half a piece
        command(
                        planner,
                        "/api/items/" + resin + "/conversions",
                        new ItemCommands.Conversion("nope", bd("2"), true, true))
                .andExpect(status().isNotFound());

        // Updating a factor replaces it (audited); removing it makes the unit unconvertible again.
        items.setConversion(planner, resin, new ItemCommands.Conversion("bag", bd("20"), true, false), id());
        assertThat(units.factor(planner, resin, "bag")).isEqualByComparingTo("20");
        command(planner, "/api/items/" + resin + "/conversions/bag/remove", null)
                .andExpect(status().isOk());
        assertThatThrownBy(() -> units.factor(planner, resin, "bag")).hasMessageContaining("No conversion");

        // Units: custom units, precision locked once used, codes unique.
        command(planner, "/api/units", new ItemCommands.Unit("SCOOP", "Dup", "PACKAGE", 0, null))
                .andExpect(status().isConflict());
        command(planner, "/api/units", new ItemCommands.Unit("bale", "Bale", "PACKAGE", 0, bd("2")))
                .andExpect(status().isConflict());
        command(planner, "/api/units/scoop", new ItemCommands.EditUnit("Scoop", 3, true))
                .andExpect(status().isConflict());
        command(planner, "/api/units/scoop", new ItemCommands.EditUnit("Measuring scoop", 2, true))
                .andExpect(status().isOk());
        var list = json(query(viewer, "/api/units").andExpect(status().isOk()));
        assertThat((List<?>) list.get("items")).hasSize(17);
    }

    // ------------------------------------------------------------------ barcodes

    @Test
    void barcodesAreUniquePerTenantAndResolveToBaseQuantities() throws Exception {
        String bottle = item(planner, "FG-BC", "FINISHED_GOOD", "pcs");
        String cap = item(planner, "FG-CAP", "FINISHED_GOOD", "pcs");
        items.setConversion(planner, bottle, new ItemCommands.Conversion("box", bd("50"), false, true), id());

        command(planner, "/api/items/" + bottle + "/barcodes", new ItemCommands.Barcode("4790001000015", null))
                .andExpect(status().isOk());
        command(planner, "/api/items/" + bottle + "/barcodes", new ItemCommands.Barcode("14790001000012", "box"))
                .andExpect(status().isOk());
        command(planner, "/api/items/" + cap + "/barcodes", new ItemCommands.Barcode("4790001000015", null))
                .andExpect(status().isConflict());
        command(planner, "/api/items/" + cap + "/barcodes", new ItemCommands.Barcode("CAP-ROLL", "roll"))
                .andExpect(status().isConflict()); // no roll conversion for caps
        command(planner, "/api/items/" + cap + "/barcodes", new ItemCommands.Barcode("has space", null))
                .andExpect(status().isBadRequest());

        var single =
                json(query(viewer, "/api/items/by-barcode?code=4790001000015").andExpect(status().isOk()));
        assertThat(single).containsEntry("unit", "pcs");
        assertThat(dec(single.get("baseQuantity"))).isEqualByComparingTo("1");
        var carton = json(
                query(warehouse, "/api/items/by-barcode?code=14790001000012").andExpect(status().isOk()));
        assertThat(carton).containsEntry("unit", "box");
        assertThat(dec(carton.get("baseQuantity"))).isEqualByComparingTo("50");
        @SuppressWarnings("unchecked")
        var found = (Map<String, Object>) carton.get("item");
        assertThat(found.get("id")).isEqualTo(bottle);
        query(viewer, "/api/items/by-barcode?code=0000000").andExpect(status().isNotFound());
        // The item list search matches an exact barcode.
        var page = json(query(viewer, "/api/items?q=14790001000012").andExpect(status().isOk()));
        assertThat(((Number) page.get("total")).intValue()).isEqualTo(1);

        // A conversion used by a barcode cannot be removed.
        command(planner, "/api/items/" + bottle + "/conversions/box/remove", null)
                .andExpect(status().isConflict());

        // Another tenant may use the same barcode for its own item and only sees its own item.
        var other = createTenant("Other barcode tenant");
        var otherPlanner = createUser(other.id(), "PLANNER");
        String otherItem = str(
                json(command(
                                otherPlanner,
                                "/api/items",
                                new CatalogCommands.Item("X-1", "Other", "FINISHED_GOOD", "pcs", bd("1"), bd("0")))
                        .andExpect(status().isOk())),
                "id");
        command(otherPlanner, "/api/items/" + otherItem + "/barcodes", new ItemCommands.Barcode("4790001000015", null))
                .andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        var otherFound = (Map<String, Object>) json(query(otherPlanner, "/api/items/by-barcode?code=4790001000015"))
                .get("item");
        assertThat(otherFound.get("id")).isEqualTo(otherItem);

        String barcodeId =
                str(row("SELECT id FROM item_barcodes WHERE tenant_id=? AND barcode='14790001000012'", t), "id");
        command(planner, "/api/items/" + bottle + "/barcodes/" + barcodeId + "/remove", null)
                .andExpect(status().isOk());
        command(planner, "/api/items/" + bottle + "/conversions/box/remove", null)
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ SEMI_FINISHED

    @Test
    void semiFinishedItemsAreProducedIntoTheRawStoreAndConsumedAsMaterial() throws Exception {
        String resin = item(planner, "RM-SF", "RAW_MATERIAL", "kg");
        String preform = item(planner, "SF-PRE", "SEMI_FINISHED", "pcs");
        String bottle = item(planner, "FG-SF", "FINISHED_GOOD", "pcs");
        openingStock(admin, resin, tenant.rawWarehouse(), "100", "10");
        String machine = machine();

        // A semi-finished item has its own (single-level) BOM and manufacturing order.
        String preformBom = approvedBom(preform, resin, "0.5");
        String order = str(mo.create(planner, order(preformBom, machine, "100"), id()), "id");
        mo.transition(admin, order, "approve", id());
        mo.transition(warehouse, order, "reserve", id());
        mo.transition(warehouse, order, "issue", id());
        mo.transition(operator, order, "start", id());
        mo.complete(
                operator, order, new ManufacturingCommands.Complete(bd("100"), bd("0"), bd("0"), bd("0"), "Run"), id());
        var lot = row("SELECT * FROM lots WHERE tenant_id=? AND source_id=?", t, order);
        assertThat(str(lot, "number")).startsWith("SF-");
        assertThat(lot.get("warehouse_id")).isEqualTo(tenant.rawWarehouse());
        assertThat(lot.get("status")).isEqualTo("QUARANTINE");
        assertThat(count(
                        "SELECT COUNT(*) FROM journal_lines l JOIN journal_entries e ON e.tenant_id=l.tenant_id AND e.id=l.entry_id WHERE l.tenant_id=? AND e.reference=? AND l.account='RAW_INVENTORY' AND l.debit>0",
                        t,
                        mo.order(planner, order).get("number")))
                .isEqualTo(1);
        var inspection = row("SELECT id FROM inspections WHERE tenant_id=? AND lot_id=?", t, lot.get("id"));
        quality.inspect(admin, str(inspection, "id"), new QualityCommands.Inspect("PASSED", "ok"), id());

        // The released preform stock is consumed by a finished-good order like any other material.
        String bottleBom = approvedBom(bottle, preform, "1");
        String bottleOrder = str(mo.create(planner, order(bottleBom, machine, "40"), id()), "id");
        mo.transition(admin, bottleOrder, "approve", id());
        mo.transition(warehouse, bottleOrder, "reserve", id());
        mo.transition(warehouse, bottleOrder, "issue", id());
        assertThat(decimal("SELECT quantity FROM stock_balances WHERE tenant_id=? AND lot_id=?", t, lot.get("id")))
                .isEqualByComparingTo("60");

        // Planning covers semi-finished items and reports their type.
        var state = json(query(planner, "/api/state?days=14").andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var mrp = (List<Map<String, Object>>) state.get("mrp");
        assertThat(mrp).anySatisfy(r -> {
            assertThat(r.get("id")).isEqualTo(preform);
            assertThat(r.get("type")).isEqualTo("SEMI_FINISHED");
        });
        // A product cannot consume itself.
        assertThatThrownBy(() -> catalog.bom(
                        planner,
                        new CatalogCommands.Bom(
                                preform,
                                "R9",
                                bd("1"),
                                bd("1"),
                                List.of(new CatalogCommands.BomLine(preform, bd("1")))),
                        id()))
                .hasMessageContaining("own BOM");
    }

    // ------------------------------------------------------------------ variants

    @Test
    void variantFamiliesGenerateOrdinaryStockableItems() throws Exception {
        String category = str(categories.create(planner, new ItemCommands.Category("Bottles", null, ""), id()), "id");
        var created = json(command(
                        planner,
                        "/api/item-templates",
                        new ItemCommands.Template(
                                "BTL",
                                "PET bottle",
                                "Blow moulded",
                                "FINISHED_GOOD",
                                "pcs",
                                category,
                                List.of(
                                        new ItemCommands.Attribute("Size", List.of("500ml", "1L")),
                                        new ItemCommands.Attribute("Colour", List.of("Clear", "Blue")))))
                .andExpect(status().isOk()));
        String family = str(created, "id");

        var generated = json(command(
                        planner,
                        "/api/item-templates/" + family + "/variants",
                        new ItemCommands.GenerateVariants(List.of(
                                new ItemCommands.Variant(
                                        Map.of("Size", "500ml", "Colour", "Clear"),
                                        "BTL-500-C",
                                        null,
                                        bd("16.8"),
                                        null),
                                new ItemCommands.Variant(
                                        Map.of("Size", "1L", "Colour", "Clear"),
                                        "BTL-1L-C",
                                        "Big bottle",
                                        bd("27"),
                                        bd("100")))))
                .andExpect(status().isOk()));
        assertThat((List<?>) generated.get("created")).hasSize(2);
        var variant = row("SELECT * FROM items WHERE tenant_id=? AND sku='BTL-500-C'", t);
        assertThat(variant)
                .containsEntry("type", "FINISHED_GOOD")
                .containsEntry("uom", "pcs")
                .containsEntry("category_id", category)
                .containsEntry("template_id", family)
                .containsEntry("variant_label", "500ml · Clear")
                .containsEntry("name", "PET bottle · 500ml · Clear");
        // The family itself is not an item and holds no stock; a variant is stocked like any item.
        assertThat(count("SELECT COUNT(*) FROM items WHERE tenant_id=? AND id=?", t, family))
                .isZero();
        openingStock(admin, str(variant, "id"), tenant.finishedWarehouse(), "10", "16.8");

        // Same combination, incomplete or unknown values and duplicate SKUs are rejected.
        command(
                        planner,
                        "/api/item-templates/" + family + "/variants",
                        new ItemCommands.GenerateVariants(List.of(new ItemCommands.Variant(
                                Map.of("Size", "500ml", "Colour", "Clear"), "BTL-X", null, bd("1"), null))))
                .andExpect(status().isConflict());
        command(
                        planner,
                        "/api/item-templates/" + family + "/variants",
                        new ItemCommands.GenerateVariants(List.of(
                                new ItemCommands.Variant(Map.of("Size", "500ml"), "BTL-Y", null, bd("1"), null))))
                .andExpect(status().isConflict());
        command(
                        planner,
                        "/api/item-templates/" + family + "/variants",
                        new ItemCommands.GenerateVariants(List.of(new ItemCommands.Variant(
                                Map.of("Size", "2L", "Colour", "Clear"), "BTL-Z", null, bd("1"), null))))
                .andExpect(status().isConflict());
        command(
                        planner,
                        "/api/item-templates/" + family + "/variants",
                        new ItemCommands.GenerateVariants(List.of(new ItemCommands.Variant(
                                Map.of("Size", "500ml", "Colour", "Blue"), "btl-500-c", null, bd("1"), null))))
                .andExpect(status().isConflict());

        // Values can be added later; used values and the attribute set are fixed once variants exist.
        var withTwoLitre = List.of(
                new ItemCommands.Attribute("Size", List.of("500ml", "1L", "2L")),
                new ItemCommands.Attribute("Colour", List.of("Clear", "Blue")));
        command(
                        planner,
                        "/api/item-templates/" + family,
                        new ItemCommands.EditTemplate("PET bottle", "", category, withTwoLitre))
                .andExpect(status().isOk());
        command(
                        planner,
                        "/api/item-templates/" + family,
                        new ItemCommands.EditTemplate(
                                "PET bottle",
                                "",
                                category,
                                List.of(
                                        new ItemCommands.Attribute("Size", List.of("1L", "2L")),
                                        new ItemCommands.Attribute("Colour", List.of("Clear", "Blue")))))
                .andExpect(status().isConflict());
        command(
                        planner,
                        "/api/item-templates/" + family,
                        new ItemCommands.EditTemplate(
                                "PET bottle",
                                "",
                                category,
                                List.of(
                                        new ItemCommands.Attribute("Size", List.of("500ml", "1L", "2L")),
                                        new ItemCommands.Attribute("Colour", List.of("Clear", "Blue")),
                                        new ItemCommands.Attribute("Neck", List.of("28mm")))))
                .andExpect(status().isConflict());
        variants.generate(
                planner,
                family,
                new ItemCommands.GenerateVariants(List.of(new ItemCommands.Variant(
                        Map.of("Size", "2L", "Colour", "Blue"), "BTL-2L-B", null, bd("40"), null))),
                id());

        var detail = json(query(viewer, "/api/item-templates/" + family).andExpect(status().isOk()));
        assertThat((List<?>) detail.get("variants")).hasSize(3);
        var itemDetail = json(query(viewer, "/api/items/" + variant.get("id")).andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var familyBlock = (Map<String, Object>) itemDetail.get("variants");
        assertThat(familyBlock).containsEntry("code", "BTL");
        assertThat((List<?>) familyBlock.get("items")).hasSize(3);
        command(
                        planner,
                        "/api/item-templates",
                        new ItemCommands.Template("btl", "Dup", null, "FINISHED_GOOD", "pcs", null, withTwoLitre))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ categories and the read model

    @Test
    void categoriesFormAnAcyclicTreeAndFilterTheItemList() throws Exception {
        String raw = str(categories.create(planner, new ItemCommands.Category("Raw", null, ""), id()), "id");
        String resins = str(categories.create(planner, new ItemCommands.Category("Resins", raw, ""), id()), "id");
        String hdpe = str(categories.create(planner, new ItemCommands.Category("HDPE", resins, ""), id()), "id");
        command(planner, "/api/item-categories", new ItemCommands.Category("resins", null, ""))
                .andExpect(status().isConflict());
        command(planner, "/api/item-categories/" + raw, new ItemCommands.EditCategory("Raw", hdpe, "", true))
                .andExpect(status().isConflict());
        command(planner, "/api/item-categories/" + raw, new ItemCommands.EditCategory("Raw", raw, "", true))
                .andExpect(status().isConflict());

        for (int i = 1; i <= 5; i++) {
            String id = item(planner, "P-" + i, i <= 3 ? "RAW_MATERIAL" : "PACKAGING", i <= 3 ? "kg" : "pcs");
            if (i <= 2) items.edit(planner, id, edit(id, Map.of("categoryId", hdpe)), id());
            if (i == 3) items.edit(planner, id, edit(id, Map.of("categoryId", raw)), id());
        }
        String inactive = row("SELECT id FROM items WHERE tenant_id=? AND sku='P-5'", t)
                .get("id")
                .toString();
        items.deactivate(planner, inactive, id());

        var first = json(query(viewer, "/api/items?limit=2").andExpect(status().isOk()));
        assertThat(((Number) first.get("total")).intValue()).isEqualTo(5);
        assertThat((List<?>) first.get("items")).hasSize(2);
        var second = json(
                query(viewer, "/api/items?limit=2&after=" + first.get("next")).andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var secondItems = (List<Map<String, Object>>) second.get("items");
        assertThat(secondItems).extracting(r -> r.get("sku")).containsExactly("P-3", "P-4");
        var third = json(query(viewer, "/api/items?limit=2&after=" + second.get("next")));
        assertThat(third.get("next")).isEqualTo("");

        // Category filter includes sub-categories; type, active and text filters combine.
        assertThat(((Number) json(query(viewer, "/api/items?category=" + raw)).get("total")).intValue())
                .isEqualTo(3);
        assertThat(((Number) json(query(viewer, "/api/items?category=" + resins))
                                .get("total"))
                        .intValue())
                .isEqualTo(2);
        assertThat(((Number) json(query(viewer, "/api/items?category=none")).get("total")).intValue())
                .isEqualTo(2);
        assertThat(((Number) json(query(viewer, "/api/items?type=PACKAGING&active=true"))
                                .get("total"))
                        .intValue())
                .isEqualTo(1);
        assertThat(((Number) json(query(viewer, "/api/items?q=p-4")).get("total")).intValue())
                .isEqualTo(1);
        query(viewer, "/api/items?type=BOGUS").andExpect(status().isBadRequest());
        var tree = json(query(viewer, "/api/item-categories").andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) tree.get("items");
        assertThat(nodes).extracting(r -> r.get("path")).containsExactly("Raw", "Raw / Resins", "Raw / Resins / HDPE");
        assertThat(((Number) nodes.getFirst().get("total_items")).intValue()).isEqualTo(3);
    }

    @Test
    void itemDetailShowsStockWhereUsedMovementsAndUnits() throws Exception {
        String resin = item(planner, "RM-DT", "RAW_MATERIAL", "kg");
        String fg = item(planner, "FG-DT", "FINISHED_GOOD", "pcs");
        items.setConversion(planner, resin, new ItemCommands.Conversion("bag", bd("25"), true, false), id());
        openingStock(admin, resin, tenant.rawWarehouse(), "50", "4");
        openingStock(admin, resin, tenant.rawWarehouse(), "25", "5");
        approvedBom(fg, resin, "2");

        var detail = json(query(viewer, "/api/items/" + resin).andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var stock = (Map<String, Object>) detail.get("stock");
        assertThat((List<?>) stock.get("lots")).hasSize(2);
        @SuppressWarnings("unchecked")
        var warehouses = (List<Map<String, Object>>) stock.get("warehouses");
        assertThat(dec(warehouses.getFirst().get("on_hand"))).isEqualByComparingTo("75");
        assertThat((List<?>) detail.get("whereUsed")).hasSize(1);
        assertThat((List<?>) detail.get("movements")).hasSize(2);
        @SuppressWarnings("unchecked")
        var unitChoices = (List<Map<String, Object>>) detail.get("units");
        assertThat(unitChoices).extracting(u -> u.get("code")).contains("kg", "bag", "g", "t");

        var movements =
                json(query(viewer, "/api/items/" + resin + "/movements?limit=1").andExpect(status().isOk()));
        assertThat((List<?>) movements.get("items")).hasSize(1);
        var older = json(query(viewer, "/api/items/" + resin + "/movements?limit=1&before=" + movements.get("next")));
        assertThat((List<?>) older.get("items")).hasSize(1);
        assertThat(older.get("next")).isEqualTo("");
        var itemUnits = json(query(viewer, "/api/items/" + resin + "/units").andExpect(status().isOk()));
        assertThat(itemUnits).containsEntry("baseUnit", "kg");
    }

    // ------------------------------------------------------------------ security and idempotency

    @Test
    void itemMasterChangesNeedPlannerAndStayInsideTheTenant() throws Exception {
        String resin = item(planner, "RM-SEC", "RAW_MATERIAL", "kg");
        var body = edit(resin, Map.of("name", "Denied"));
        for (var role : List.of(warehouse, operator, viewer, createUser(t, "SALES"), createUser(t, "QUALITY")))
            command(role, "/api/items/" + resin, body).andExpect(status().isForbidden());
        command(warehouse, "/api/items/" + resin + "/barcodes", new ItemCommands.Barcode("ABC-123", null))
                .andExpect(status().isForbidden());
        command(viewer, "/api/units", new ItemCommands.Unit("bale", "Bale", "PACKAGE", 0, null))
                .andExpect(status().isForbidden());
        command(operator, "/api/item-categories", new ItemCommands.Category("Nope", null, null))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> items.deactivate(warehouse, resin, id())).isInstanceOf(AccessDeniedException.class);
        command(admin, "/api/items/" + resin, edit(resin, Map.of("name", "Admin edit")))
                .andExpect(status().isOk());
        query(viewer, "/api/items").andExpect(status().isOk());

        var other = createTenant("Other item tenant");
        var otherPlanner = createUser(other.id(), "PLANNER");
        command(otherPlanner, "/api/items/" + resin, body).andExpect(status().isNotFound());
        command(otherPlanner, "/api/items/" + resin + "/deactivate", null).andExpect(status().isNotFound());
        command(
                        otherPlanner,
                        "/api/items/" + resin + "/conversions",
                        new ItemCommands.Conversion("bag", bd("25"), true, true))
                .andExpect(status().isNotFound());
        query(otherPlanner, "/api/items/" + resin).andExpect(status().isNotFound());
        query(otherPlanner, "/api/items/" + resin + "/convert?quantity=1&unit=kg")
                .andExpect(status().isNotFound());
        assertThat(((Number) json(query(otherPlanner, "/api/items")).get("total")).intValue())
                .isZero();
        assertThat(row("SELECT name FROM items WHERE id=?", resin).get("name")).isEqualTo("Admin edit");
    }

    @Test
    void retriesAreIdempotentAndKeyReuseWithAnotherBodyConflicts() throws Exception {
        String resin = item(planner, "RM-IDEM", "RAW_MATERIAL", "kg");
        String key = "item-edit-" + id();
        var body = edit(resin, Map.of("standardCost", bd("11")));
        var firstResult =
                json(command(planner, "/api/items/" + resin, body, key).andExpect(status().isOk()));
        var retry = json(command(planner, "/api/items/" + resin, body, key).andExpect(status().isOk()));
        assertThat(retry).isEqualTo(firstResult);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='ITEM_UPDATED'", t))
                .isEqualTo(1);
        command(planner, "/api/items/" + resin, edit(resin, Map.of("standardCost", bd("12"))), key)
                .andExpect(status().isConflict());

        String barcodeKey = "barcode-" + id();
        command(planner, "/api/items/" + resin + "/barcodes", new ItemCommands.Barcode("RM-IDEM-1", null), barcodeKey)
                .andExpect(status().isOk());
        command(planner, "/api/items/" + resin + "/barcodes", new ItemCommands.Barcode("RM-IDEM-1", null), barcodeKey)
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM item_barcodes WHERE tenant_id=?", t))
                .isEqualTo(1);

        // A failed command rolls back with its key: the same key may then carry the corrected request.
        String failingKey = "conversion-" + id();
        command(
                        planner,
                        "/api/items/" + resin + "/conversions",
                        new ItemCommands.Conversion("g", bd("2"), true, true),
                        failingKey)
                .andExpect(status().isConflict());
        command(
                        planner,
                        "/api/items/" + resin + "/conversions",
                        new ItemCommands.Conversion("bag", bd("25"), true, true),
                        failingKey)
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ migration

    @Test
    void v4MigrationUpgradesAnExistingTenant() throws Exception {
        String schema = "v4_upgrade_" + id().replace("-", "");
        var dataSource = s.db().getDataSource();
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("2")
                    .load()
                    .migrate();
            inSchema(schema, db -> {
                db.update("INSERT INTO tenants(id,name) VALUES('legacy','Legacy tenant')");
                db.update("INSERT INTO factories(id,tenant_id,name,location) VALUES('p','legacy','Plant','Here')");
                db.update(
                        "INSERT INTO warehouses(id,tenant_id,factory_id,name,kind) VALUES('w','legacy','p','Raw','RAW')");
                db.update(
                        "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES('rm','legacy','RM','Resin','RAW_MATERIAL','kg',10,5),('fg','legacy','FG','Bottle','FINISHED_GOOD','pcs',2,0)");
                db.update(
                        "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES('l','legacy','L-1','rm','w','RELEASED','OPENING','X',10)");
                db.update(
                        "INSERT INTO stock_ledger(id,tenant_id,lot_id,item_id,warehouse_id,uom,delta,value_delta,unit_cost,reference,reason,actor) VALUES('e1','legacy','l','rm','w','kg',4,40,10,'X','OPENING','Test')");
            });
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("4")
                    .load()
                    .migrate();
            inSchema(schema, db -> {
                assertThat(db.queryForObject("SELECT COUNT(*) FROM units WHERE tenant_id='legacy'", Long.class))
                        .isEqualTo(15);
                var rm = db.queryForMap("SELECT * FROM items WHERE id='rm'");
                assertThat(rm)
                        .containsEntry("uom", "kg")
                        .containsEntry("description", "")
                        .containsEntry("lead_time_days", 0);
                assertThat(dec(rm.get("reorder_qty"))).isZero();
                assertThat(dec(rm.get("reorder_level"))).isEqualByComparingTo("5");
                // New units and SEMI_FINISHED are accepted; unknown units are rejected by the foreign key.
                db.update(
                        "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost) VALUES('sf','legacy','SF','Preform','SEMI_FINISHED','box',1)");
                assertThatThrownBy(
                                () -> db.update(
                                        "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost) VALUES('bad','legacy','BAD','Bad','RAW_MATERIAL','zz',1)"))
                        .hasMessageContaining("items_unit_fk");
                // The V1 ledger trigger still compares the ledger unit with the item's base unit.
                db.update(
                        "INSERT INTO stock_ledger(id,tenant_id,lot_id,item_id,warehouse_id,uom,delta,value_delta,unit_cost,reference,reason,actor) VALUES('e2','legacy','l','rm','w','kg',1,10,10,'X','TEST','Test')");
                assertThatThrownBy(
                                () -> db.update(
                                        "INSERT INTO stock_ledger(id,tenant_id,lot_id,item_id,warehouse_id,uom,delta,value_delta,unit_cost,reference,reason,actor) VALUES('e3','legacy','l','rm','w','g',1,10,10,'X','TEST','Test')"))
                        .hasMessageContaining("Ledger unit, item or warehouse mismatch");
                // Existing stock locks the legacy item's base unit and SKU.
                assertThatThrownBy(() -> db.update("UPDATE items SET uom='g' WHERE id='rm'"))
                        .hasMessageContaining("base unit cannot change");
                assertThatThrownBy(() -> db.update("UPDATE items SET sku='RM-2' WHERE id='rm'"))
                        .hasMessageContaining("fixed once");
                db.update("UPDATE items SET sku='FG-2',name='Renamed' WHERE id='fg'"); // unused: allowed
                // A tenant created after the upgrade gets the standard units from the trigger.
                db.update("INSERT INTO tenants(id,name) VALUES('fresh','Fresh tenant')");
                assertThat(db.queryForObject("SELECT COUNT(*) FROM units WHERE tenant_id='fresh'", Long.class))
                        .isEqualTo(15);
            });
        } finally {
            s.db().execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** Runs {@code work} on one pooled connection whose search_path is {@code schema}, then restores it. */
    private void inSchema(String schema, java.util.function.Consumer<JdbcTemplate> work) throws Exception {
        try (var connection = s.db().getDataSource().getConnection()) {
            String previous;
            try (var st = connection.createStatement();
                    var rs = st.executeQuery("SHOW search_path")) {
                rs.next();
                previous = rs.getString(1);
            }
            try (var st = connection.createStatement()) {
                st.execute("SET search_path TO " + schema);
            }
            try {
                work.accept(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
            } finally {
                try (var st = connection.createStatement()) {
                    st.execute("SET search_path TO " + previous);
                }
            }
        }
    }
}
