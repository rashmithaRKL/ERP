package lk.dexlanka.erp.catalog;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Item master routes (V4). {@code POST /api/items} (create) stays in {@link CatalogController}. Reads are open to every
 * signed-in role; changes require PLANNER (or ADMIN).
 */
@RestController
@RequestMapping("/api")
class ItemMasterController {
    private final ItemQuery query;
    private final ItemService items;
    private final UnitService units;
    private final CategoryService categories;
    private final VariantService variants;

    ItemMasterController(
            ItemQuery query,
            ItemService items,
            UnitService units,
            CategoryService categories,
            VariantService variants) {
        this.query = query;
        this.items = items;
        this.units = units;
        this.categories = categories;
        this.variants = variants;
    }

    // ------------------------------------------------------------------ items: reads

    @GetMapping("/items")
    Map<String, Object> items(
            Authentication a,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String active,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String after) {
        return query.page(Actor.of(a), q, type, category, active, limit, after);
    }

    @GetMapping("/items/by-barcode")
    Map<String, Object> byBarcode(Authentication a, @RequestParam String code) {
        return query.barcode(Actor.of(a), code);
    }

    @GetMapping("/items/{id}")
    Map<String, Object> item(Authentication a, @PathVariable String id) {
        return query.detail(Actor.of(a), id);
    }

    @GetMapping("/items/{id}/movements")
    Map<String, Object> movements(
            Authentication a,
            @PathVariable String id,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.movements(Actor.of(a), id, limit, before);
    }

    @GetMapping("/items/{id}/units")
    Map<String, Object> itemUnits(Authentication a, @PathVariable String id) {
        return query.itemUnits(Actor.of(a), id);
    }

    @GetMapping("/items/{id}/convert")
    Map<String, Object> convert(
            Authentication a, @PathVariable String id, @RequestParam BigDecimal quantity, @RequestParam String unit) {
        return units.toBase(Actor.of(a), id, quantity, unit).toMap();
    }

    // ------------------------------------------------------------------ items: commands

    @PostMapping("/items/{id}")
    Map<String, Object> edit(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ItemCommands.EditItem c,
            @RequestHeader("Idempotency-Key") String k) {
        return items.edit(Actor.of(a), id, c, k);
    }

    @PostMapping("/items/{id}/deactivate")
    Map<String, Object> deactivate(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return items.deactivate(Actor.of(a), id, k);
    }

    @PostMapping("/items/{id}/activate")
    Map<String, Object> activate(
            Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return items.activate(Actor.of(a), id, k);
    }

    @PostMapping("/items/{id}/conversions")
    Map<String, Object> setConversion(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ItemCommands.Conversion c,
            @RequestHeader("Idempotency-Key") String k) {
        return items.setConversion(Actor.of(a), id, c, k);
    }

    @PostMapping("/items/{id}/conversions/{unit}/remove")
    Map<String, Object> removeConversion(
            Authentication a,
            @PathVariable String id,
            @PathVariable String unit,
            @RequestHeader("Idempotency-Key") String k) {
        return items.removeConversion(Actor.of(a), id, unit, k);
    }

    @PostMapping("/items/{id}/barcodes")
    Map<String, Object> addBarcode(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ItemCommands.Barcode c,
            @RequestHeader("Idempotency-Key") String k) {
        return items.addBarcode(Actor.of(a), id, c, k);
    }

    @PostMapping("/items/{id}/barcodes/{barcodeId}/remove")
    Map<String, Object> removeBarcode(
            Authentication a,
            @PathVariable String id,
            @PathVariable String barcodeId,
            @RequestHeader("Idempotency-Key") String k) {
        return items.removeBarcode(Actor.of(a), id, barcodeId, k);
    }

    // ------------------------------------------------------------------ units

    @GetMapping("/units")
    Map<String, Object> units(Authentication a) {
        return query.units(Actor.of(a));
    }

    @PostMapping("/units")
    Map<String, Object> createUnit(
            Authentication a, @Valid @RequestBody ItemCommands.Unit c, @RequestHeader("Idempotency-Key") String k) {
        return units.create(Actor.of(a), c, k);
    }

    @PostMapping("/units/{code}")
    Map<String, Object> updateUnit(
            Authentication a,
            @PathVariable String code,
            @Valid @RequestBody ItemCommands.EditUnit c,
            @RequestHeader("Idempotency-Key") String k) {
        return units.update(Actor.of(a), code, c, k);
    }

    // ------------------------------------------------------------------ categories

    @GetMapping("/item-categories")
    Map<String, Object> categories(Authentication a) {
        return query.categories(Actor.of(a));
    }

    @PostMapping("/item-categories")
    Map<String, Object> createCategory(
            Authentication a, @Valid @RequestBody ItemCommands.Category c, @RequestHeader("Idempotency-Key") String k) {
        return categories.create(Actor.of(a), c, k);
    }

    @PostMapping("/item-categories/{id}")
    Map<String, Object> updateCategory(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ItemCommands.EditCategory c,
            @RequestHeader("Idempotency-Key") String k) {
        return categories.update(Actor.of(a), id, c, k);
    }

    // ------------------------------------------------------------------ variant families

    @GetMapping("/item-templates")
    Map<String, Object> templates(Authentication a) {
        return query.templates(Actor.of(a));
    }

    @GetMapping("/item-templates/{id}")
    Map<String, Object> template(Authentication a, @PathVariable String id) {
        return query.template(Actor.of(a), id);
    }

    @PostMapping("/item-templates")
    Map<String, Object> createTemplate(
            Authentication a, @Valid @RequestBody ItemCommands.Template c, @RequestHeader("Idempotency-Key") String k) {
        return variants.create(Actor.of(a), c, k);
    }

    @PostMapping("/item-templates/{id}")
    Map<String, Object> updateTemplate(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ItemCommands.EditTemplate c,
            @RequestHeader("Idempotency-Key") String k) {
        return variants.update(Actor.of(a), id, c, k);
    }

    @PostMapping("/item-templates/{id}/variants")
    Map<String, Object> generateVariants(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody ItemCommands.GenerateVariants c,
            @RequestHeader("Idempotency-Key") String k) {
        return variants.generate(Actor.of(a), id, c, k);
    }
}
