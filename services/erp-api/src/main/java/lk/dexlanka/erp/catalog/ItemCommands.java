package lk.dexlanka.erp.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Request bodies of the item master (V4): item editing, units of measure, item unit conversions, categories, barcodes
 * and variant families. Item creation keeps using {@link CatalogCommands.Item}.
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class ItemCommands {
    private ItemCommands() {}

    /** Item type codes. {@code SEMI_FINISHED}: an intermediate (sub-assembly) that is produced and consumed again. */
    public static final String ITEM_TYPES = "RAW_MATERIAL|SEMI_FINISHED|FINISHED_GOOD|PACKAGING";

    public static final String UNIT_CODE = "[A-Za-z0-9]{1,12}";
    public static final String UNIT_KINDS = "MASS|VOLUME|LENGTH|AREA|COUNT|PACKAGE";

    /**
     * Full replacement of the editable item fields. {@code sku} and {@code uom} may only differ from the stored values
     * while the item is unused (no stock movement or document reference); the type is fixed after creation.
     */
    public record EditItem(
            @NotBlank @Size(max = 50) String sku,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 2000) String description,
            @Size(max = 64) String categoryId,
            @NotBlank @Pattern(regexp = UNIT_CODE) String uom,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal standardCost,

            /* Safety stock (planning keeps at least this much released stock). */
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal reorderLevel,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal reorderQuantity,

            @NotNull @Min(0) @Max(365) Integer leadTimeDays) {}

    public record Unit(
            @NotBlank @Pattern(regexp = UNIT_CODE) String code,
            @NotBlank @Size(max = 60) String name,
            @NotBlank @Pattern(regexp = UNIT_KINDS) String kind,
            @NotNull @Min(0) @Max(6) Integer decimals,

            /* Size in the kind's reference unit (kg, l, m, m2, pcs); omit for package units such as box or bag. */
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 12)
            BigDecimal ratio) {}

    public record EditUnit(
            @NotBlank @Size(max = 60) String name,
            @NotNull @Min(0) @Max(6) Integer decimals,
            @NotNull Boolean active) {}

    /** Sets (creates or replaces) the item's conversion for {@code unit}: 1 unit = {@code factor} base units. */
    public record Conversion(
            @NotBlank @Pattern(regexp = UNIT_CODE) String unit,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 12)
            BigDecimal factor,

            @NotNull Boolean purchase,
            @NotNull Boolean sales) {}

    /** A barcode of the item. Without {@code unit} one scan is one base unit. */
    public record Barcode(
            @NotBlank @Pattern(regexp = "[!-~]{3,64}") String barcode,
            @Pattern(regexp = UNIT_CODE) String unit) {}

    public record Category(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 64) String parentId,
            @Size(max = 400) String description) {}

    public record EditCategory(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 64) String parentId,
            @Size(max = 400) String description,
            @NotNull Boolean active) {}

    public record Attribute(
            @NotBlank @Size(max = 40) String name,
            @NotEmpty @Size(max = 30) List<@NotBlank @Size(max = 40) String> values) {}

    /** A variant family: the template is not stocked; its variants are ordinary items. */
    public record Template(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+")
            String code,

            @NotBlank @Size(max = 160) String name,
            @Size(max = 2000) String description,
            @NotNull @Pattern(regexp = ITEM_TYPES) String type,
            @NotBlank @Pattern(regexp = UNIT_CODE) String uom,
            @Size(max = 64) String categoryId,
            @NotEmpty @Size(max = 5) List<@Valid Attribute> attributes) {}

    /**
     * Name, description, category and attributes of a family. Once variants exist, the attribute names are fixed and
     * values used by a variant must stay; new values can always be added.
     */
    public record EditTemplate(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 2000) String description,
            @Size(max = 64) String categoryId,
            @NotEmpty @Size(max = 5) List<@Valid Attribute> attributes) {}

    public record Variant(
            /* Attribute name -> value, one entry per family attribute. */
            @NotEmpty @Size(max = 5) Map<@NotBlank String, @NotBlank String> values,
            @NotBlank @Size(max = 50) String sku,
            @Size(max = 160) String name,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal standardCost,

            @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal reorderLevel) {}

    public record GenerateVariants(
            @NotEmpty @Size(max = 100) List<@Valid Variant> variants) {}
}
