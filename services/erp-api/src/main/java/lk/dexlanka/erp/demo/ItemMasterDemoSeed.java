package lk.dexlanka.erp.demo;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.str;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.catalog.CatalogService;
import lk.dexlanka.erp.catalog.CategoryService;
import lk.dexlanka.erp.catalog.ItemCommands;
import lk.dexlanka.erp.catalog.ItemService;
import lk.dexlanka.erp.catalog.VariantService;
import lk.dexlanka.erp.manufacturing.ManufacturingCommands;
import lk.dexlanka.erp.manufacturing.ManufacturingService;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import lk.dexlanka.erp.quality.QualityCommands;
import lk.dexlanka.erp.quality.QualityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Item master demo data (V4), seeded once into the demo tenant after the base seed: categories for the demo items,
 * purchase/sales conversions (resin bag = 25 kg, bottle box = 50 pcs, label roll = 1000 pcs), barcodes, one variant
 * family (PET bottle in 500ml/1L) and a semi-finished PET preform produced through a real manufacturing order.
 *
 * <p>Everything is posted through the public commands as {@link DemoPersonas}. It also runs against an existing demo
 * database: steps whose data already exists are skipped, and a failure is logged and rolled back without stopping the
 * application.
 */
@Component
@Order(DemoSeed.ORDER + 40)
class ItemMasterDemoSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ItemMasterDemoSeed.class);
    private static final String T = DemoPersonas.TENANT;

    private final Store s;
    private final CatalogService catalog;
    private final ItemService items;
    private final CategoryService categories;
    private final VariantService variants;
    private final ManufacturingService mo;
    private final QualityService quality;
    private final TransactionTemplate tx;
    private final boolean demo;

    ItemMasterDemoSeed(
            Store s,
            CatalogService catalog,
            ItemService items,
            CategoryService categories,
            VariantService variants,
            ManufacturingService mo,
            QualityService quality,
            PlatformTransactionManager transactions,
            @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.catalog = catalog;
        this.items = items;
        this.categories = categories;
        this.variants = variants;
        this.mo = mo;
        this.quality = quality;
        this.tx = new TransactionTemplate(transactions);
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo) return;
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.warn("Item master demo data was not seeded: {}", e.getMessage());
        }
    }

    void seed() {
        if (s.list("SELECT 1 FROM tenants WHERE id=?", T).isEmpty()) return;
        if (!s.list("SELECT 1 FROM item_categories WHERE tenant_id=? LIMIT 1", T)
                .isEmpty()) return; // seeded
        Actor planner = DemoPersonas.PLANNER;

        // Categories (two levels).
        String raw = category("Raw materials", null, "Polymers and additives bought from suppliers");
        String resins = category("Resins", raw, "Moulding-grade polymer resins");
        String colourants = category("Colourants", raw, "Masterbatches and pigments");
        String packaging = category("Packaging", null, "Packing materials consumed by finished goods");
        String cartons = category("Cartons", packaging, "");
        String labels = category("Labels", packaging, "");
        String semi = category("Semi-finished", null, "Intermediates produced and consumed in-house");
        String preforms = category("Preforms", semi, "Injection-moulded preforms for blow moulding");
        String finished = category("Finished goods", null, "Products sold to customers");
        String bottles = category("Bottles", finished, "");
        String containers = category("Containers", finished, "");
        String closures = category("Closures", finished, "");
        String food = category("Food packaging", finished, "");

        // Existing demo items: category, description, reorder quantity and lead time.
        describe("hdpe", resins, "Blow-moulding grade HDPE, supplied in 25 kg bags.", "2000", 14);
        describe("pet", resins, "Food-grade PET resin, supplied in 25 kg bags.", "1000", 14);
        describe("color", colourants, "Blue colour masterbatch, 2% let-down ratio.", "100", 21);
        describe("carton", cartons, "Five-ply corrugated shipper; holds 50 bottles.", "2000", 7);
        describe("label", labels, "Printed wrap-around bottle label, 1000 per roll.", "50000", 10);
        describe("bottle", bottles, "500 ml HDPE bottle, sold in cartons of 50.", "0", 0);
        describe("container", containers, "1 L food-grade container.", "0", 0);
        describe("cap", closures, "28 mm screw cap.", "0", 0);
        describe("food", food, "750 ml food packaging box.", "0", 0);

        // Purchase and sales units.
        conversion("hdpe", "bag", "25", true, false);
        conversion("pet", "bag", "25", true, false);
        conversion("bottle", "box", "50", false, true);
        conversion("label", "roll", "1000", true, false);

        // Barcodes: a retail EAN, a shipper GTIN-14 that scans as one box (50 pcs), a bag and a roll label.
        barcode("bottle", gtin("479000100001"), null);
        barcode("bottle", gtin("1479000100001"), "box");
        barcode("hdpe", "RM001-BAG25", "bag");
        barcode("label", gtin("479000200002"), "roll");

        // A variant family: PET bottles in two sizes and two colours; two variants generated so far.
        if (s.list("SELECT 1 FROM item_templates WHERE tenant_id=? AND lower(code)='btl-pet'", T)
                .isEmpty()) {
            String family = str(
                    variants.create(
                            planner,
                            new ItemCommands.Template(
                                    "BTL-PET",
                                    "PET bottle",
                                    "Stretch blow-moulded PET bottle from the 28 mm preform.",
                                    "FINISHED_GOOD",
                                    "pcs",
                                    bottles,
                                    List.of(
                                            new ItemCommands.Attribute("Size", List.of("500ml", "1L")),
                                            new ItemCommands.Attribute("Colour", List.of("Clear", "Blue")))),
                            "demo-v4-family"),
                    "id");
            if (s.list("SELECT 1 FROM items WHERE tenant_id=? AND sku IN ('FG-101','FG-102')", T)
                    .isEmpty())
                variants.generate(
                        planner,
                        family,
                        new ItemCommands.GenerateVariants(List.of(
                                new ItemCommands.Variant(
                                        Map.of("Size", "500ml", "Colour", "Clear"),
                                        "FG-101",
                                        "PET bottle 500ml · clear",
                                        new BigDecimal("16.8"),
                                        new BigDecimal("5000")),
                                new ItemCommands.Variant(
                                        Map.of("Size", "1L", "Colour", "Clear"),
                                        "FG-102",
                                        "PET bottle 1L · clear",
                                        new BigDecimal("27.4"),
                                        new BigDecimal("3000")))),
                        "demo-v4-variants");
        }

        // A semi-finished preform with stock, produced by a manufacturing order from PET resin.
        if (s.list("SELECT 1 FROM items WHERE tenant_id=? AND lower(sku)='sf-001'", T)
                .isEmpty()) preform(preforms);
        log.info("Item master demo data seeded");
    }

    private void preform(String category) {
        Actor planner = DemoPersonas.PLANNER;
        String preform = str(
                catalog.item(
                        planner,
                        new CatalogCommands.Item(
                                "SF-001",
                                "PET preform · 28 mm neck · 24 g",
                                "SEMI_FINISHED",
                                "pcs",
                                new BigDecimal("9.6"),
                                new BigDecimal("5000"),
                                "Injection-moulded preform; blown into 500 ml PET bottles.",
                                category,
                                new BigDecimal("20000"),
                                2),
                        "demo-v4-preform"),
                "id");
        String bom = str(
                catalog.bom(
                        planner,
                        new CatalogCommands.Bom(
                                preform,
                                "R01",
                                new BigDecimal("1000"),
                                BigDecimal.ONE,
                                List.of(new CatalogCommands.BomLine("pet", new BigDecimal("24.5")))),
                        "demo-v4-preform-bom"),
                "id");
        catalog.approveBom(DemoPersonas.ADMIN, bom, "demo-v4-preform-bom-approve");
        BigDecimal released = s.db().queryForObject(
                        "SELECT COALESCE(SUM(b.quantity-b.reserved),0) FROM lots l JOIN stock_balances b ON b.tenant_id=l.tenant_id AND b.lot_id=l.id WHERE l.tenant_id=? AND l.item_id='pet' AND l.status='RELEASED'",
                        BigDecimal.class,
                        T);
        if (released.compareTo(new BigDecimal("122.5")) >= 0) produce(bom);
        else opening(preform);

        // Where-used: a draft bottle revision that blows the preform (left as a draft for the planner to review).
        var variant = s.list("SELECT id FROM items WHERE tenant_id=? AND sku='FG-101'", T);
        if (!variant.isEmpty())
            catalog.bom(
                    planner,
                    new CatalogCommands.Bom(
                            str(variant.getFirst(), "id"),
                            "R01",
                            new BigDecimal("1000"),
                            new BigDecimal("0.99"),
                            List.of(
                                    new CatalogCommands.BomLine(preform, new BigDecimal("1000")),
                                    new CatalogCommands.BomLine("carton", new BigDecimal("20")),
                                    new CatalogCommands.BomLine("label", new BigDecimal("1000")))),
                    "demo-v4-bottle-bom");
    }

    /** 5,000 preforms through the full order lifecycle: the output lot lands in the raw (production input) store. */
    private void produce(String bom) {
        String order = str(
                mo.create(
                        DemoPersonas.PLANNER,
                        new ManufacturingCommands.Manufacture(
                                bom,
                                "im02",
                                null,
                                new BigDecimal("5000"),
                                "NORMAL",
                                LocalDate.now().toString()),
                        "demo-v4-preform-mo"),
                "id");
        mo.transition(DemoPersonas.ADMIN, order, "approve", "demo-v4-preform-mo-approve");
        mo.transition(DemoPersonas.WAREHOUSE, order, "reserve", "demo-v4-preform-mo-reserve");
        mo.transition(DemoPersonas.WAREHOUSE, order, "issue", "demo-v4-preform-mo-issue");
        mo.transition(DemoPersonas.OPERATOR, order, "start", "demo-v4-preform-mo-start");
        mo.complete(
                DemoPersonas.OPERATOR,
                order,
                new ManufacturingCommands.Complete(
                        new BigDecimal("4960"),
                        new BigDecimal("40"),
                        new BigDecimal("1800"),
                        new BigDecimal("1500"),
                        "Demonstration run: start-up preforms rejected for wall thickness"),
                "demo-v4-preform-mo-output");
        var inspection = s.one(
                "SELECT i.id FROM inspections i JOIN lots l ON l.tenant_id=i.tenant_id AND l.id=i.lot_id WHERE i.tenant_id=? AND l.source_id=?",
                T,
                order);
        quality.inspect(
                DemoPersonas.QUALITY,
                str(inspection, "id"),
                new QualityCommands.Inspect("PASSED", "Weight, neck finish and clarity checks passed · demonstration"),
                "demo-v4-preform-qc");
        mo.transition(DemoPersonas.PLANNER, order, "close", "demo-v4-preform-mo-close");
    }

    /** Fallback when the demo database has no PET left: an opening lot through the ledger and journal. */
    private void opening(String preform) {
        String lot = Store.id();
        s.db().update(
                        "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,'RELEASED','OPENING','OPENING-2026',?)",
                        lot,
                        T,
                        "SF-261001-PREFORM",
                        preform,
                        T + "-raw",
                        new BigDecimal("9.6"));
        BigDecimal value =
                s.ledger(DemoPersonas.ADMIN, lot, new BigDecimal("4960"), "OPENING-2026", "DEMO_OPENING_STOCK");
        s.journal(
                DemoPersonas.ADMIN,
                "OPENING-2026",
                "Demonstration opening inventory",
                "RAW_INVENTORY",
                "OPENING_EQUITY",
                value);
    }

    // ------------------------------------------------------------------ helpers

    private String category(String name, String parent, String description) {
        var existing = s.list("SELECT id FROM item_categories WHERE tenant_id=? AND lower(name)=lower(?)", T, name);
        if (!existing.isEmpty()) return str(existing.getFirst(), "id");
        return str(
                categories.create(
                        DemoPersonas.PLANNER,
                        new ItemCommands.Category(name, parent, description),
                        "demo-v4-category-" + name.toLowerCase().replaceAll("[^a-z0-9]+", "-")),
                "id");
    }

    private void describe(String item, String category, String description, String reorderQty, int leadTime) {
        var rows = s.list("SELECT * FROM items WHERE tenant_id=? AND id=?", T, item);
        if (rows.isEmpty()) return;
        var i = rows.getFirst();
        items.edit(
                DemoPersonas.PLANNER,
                item,
                new ItemCommands.EditItem(
                        str(i, "sku"),
                        str(i, "name"),
                        description,
                        category,
                        str(i, "uom"),
                        dec(i.get("standard_cost")),
                        dec(i.get("reorder_level")),
                        new BigDecimal(reorderQty),
                        leadTime),
                "demo-v4-describe-" + item);
    }

    private void conversion(String item, String unit, String factor, boolean purchase, boolean sales) {
        if (s.list("SELECT 1 FROM items WHERE tenant_id=? AND id=?", T, item).isEmpty()) return;
        items.setConversion(
                DemoPersonas.PLANNER,
                item,
                new ItemCommands.Conversion(unit, new BigDecimal(factor), purchase, sales),
                "demo-v4-conversion-" + item + "-" + unit);
    }

    private void barcode(String item, String code, String unit) {
        if (s.list("SELECT 1 FROM items WHERE tenant_id=? AND id=?", T, item).isEmpty()) return;
        if (!s.list("SELECT 1 FROM item_barcodes WHERE tenant_id=? AND barcode=?", T, code)
                .isEmpty()) return;
        items.addBarcode(
                DemoPersonas.PLANNER,
                item,
                new ItemCommands.Barcode(code, unit),
                "demo-v4-barcode-" + code.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
    }

    /** Appends the GS1 check digit (EAN-13 from 12 digits, GTIN-14 from 13). Fictional numbers. */
    static String gtin(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int digit = body.charAt(body.length() - 1 - i) - '0';
            sum += digit * (i % 2 == 0 ? 3 : 1);
        }
        return body + (10 - sum % 10) % 10;
    }
}
