package lk.dexlanka.erp.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies for organization, user, partner, item, machine and BOM commands.
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class CatalogCommands {
    private CatalogCommands() {}

    public record User(
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(min = 16, max = 128) String password,

            @NotBlank @Pattern(regexp = "ADMIN|PLANNER|WAREHOUSE|OPERATOR|QUALITY|SALES|VIEWER")
            String role) {}

    public record BomLine(
            @NotBlank String itemId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity) {}

    public record Bom(
            @NotBlank String productId,
            @NotBlank @Size(max = 30) String revision,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal outputQty,

            @NotNull @DecimalMin("0.000001") @DecimalMax("1") @Digits(integer = 1, fraction = 6)
            BigDecimal yieldFactor,

            @NotEmpty @Size(max = 100) List<@Valid BomLine> lines) {}

    /** New customer or supplier. The fields after {@code paymentDays} are optional (V3); see {@code PartnerService}. */
    public record Partner(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "CUSTOMER|SUPPLIER") String type,
            @Email @Size(max = 160) String email,
            @Min(0) @Max(365) int paymentDays,
            @Size(max = 40) String phone,
            @Size(max = 400) String billingAddress,
            @Size(max = 400) String shippingAddress,
            @Size(max = 40) String taxId,

            @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal creditLimit) {}

    public record Machine(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 80) String kind,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal hourlyRate) {}

    public record Organization(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 120) String factoryName,
            @NotBlank @Size(max = 160) String location) {}

    public record Item(
            @NotBlank @Size(max = 50) String sku,
            @NotBlank @Size(max = 160) String name,

            @Pattern(regexp = "RAW_MATERIAL|FINISHED_GOOD|PACKAGING") @NotNull
            String type,

            @Pattern(regexp = "kg|pcs") @NotNull String uom,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal standardCost,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal reorderLevel) {}
}
