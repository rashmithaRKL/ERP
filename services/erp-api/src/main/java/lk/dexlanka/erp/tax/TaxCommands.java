package lk.dexlanka.erp.tax;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies of the tax commands (V25). Component names are the JSON field names and, serialized in declaration
 * order, the idempotency payload hash: never rename or reorder them. Dates are {@code yyyy-MM-dd}; rates are
 * percentages with up to four decimals.
 */
public final class TaxCommands {
    private TaxCommands() {}

    /** A new tax code; {@code components} only for STANDARD codes (at least one), each with its first rate. */
    public record Code(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,19}")
            String code,

            @NotBlank @Size(max = 120) String name,

            @NotBlank @Pattern(regexp = "SALES|PURCHASES|BOTH")
            String appliesTo,

            @NotBlank @Pattern(regexp = "STANDARD|ZERO_RATED|EXEMPT")
            String kind,

            @Pattern(regexp = "[A-Z0-9_]{1,40}") String outputAccountCode,
            @Pattern(regexp = "[A-Z0-9_]{1,40}") String inputAccountCode,
            @Valid @Size(max = 9) List<Component> components) {}

    /** A component with its first rate version. */
    public record Component(
            @NotBlank @Size(max = 80) String name,
            @NotNull Boolean compound,
            @NotBlank String effectiveFrom,

            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4)
            BigDecimal rate) {}

    /** The editable header of a code (code and kind are fixed after creation). */
    public record CodeUpdate(
            @NotBlank @Size(max = 120) String name,

            @NotBlank @Pattern(regexp = "SALES|PURCHASES|BOTH")
            String appliesTo,

            @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,40}") String outputAccountCode,
            @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,40}") String inputAccountCode,
            @NotNull Boolean active) {}

    public record ComponentUpdate(
            @NotBlank @Size(max = 80) String name, @NotNull Boolean compound) {}

    /** A new rate version of a component. */
    public record Rate(
            @NotBlank String effectiveFrom,

            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4)
            BigDecimal rate,

            @Size(max = 200) String note) {}

    /** Tenant tax settings; the code ids may be null (no default). */
    public record Settings(
            @NotNull Boolean pricesIncludeTax,

            @NotBlank @Pattern(regexp = "PER_LINE|PER_DOCUMENT")
            String rounding,

            String defaultSalesCodeId,
            String defaultPurchaseCodeId,
            String exemptCodeId) {}

    /** A partner's tax profile: registration, exemption (reference and valid-until together) and default codes. */
    public record PartnerTax(
            @NotNull Boolean taxRegistered,
            @Size(max = 80) String exemptionReference,
            String exemptionValidUntil,
            String salesTaxCodeId,
            String purchaseTaxCodeId) {}

    /** An item's default tax codes (null = none). */
    public record ItemTax(String salesTaxCodeId, String purchaseTaxCodeId) {}

    /** A read-only calculation preview ({@code POST /api/tax/calculate}). */
    public record Calculate(
            @NotBlank @Pattern(regexp = "OUTPUT|INPUT") String direction,
            @NotBlank String date,
            String partnerId,
            Boolean pricesIncludeTax,
            @NotNull @Valid @Size(max = 500) List<CalculateLine> lines) {}

    public record CalculateLine(
            @NotNull @Min(1) Integer lineNo,
            String itemId,
            @NotNull @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @NotNull @Digits(integer = 12, fraction = 6) BigDecimal unitPrice,

            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 6)
            BigDecimal discountPct,

            String taxCodeId) {}
}
