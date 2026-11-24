package lk.dexlanka.erp.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies for the BOM revision tools (V7): copy an approved revision into a new draft and edit a draft.
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class BomCommands {
    private BomCommands() {}

    /** {@code POST /api/boms/{id}/copy}: the new draft's revision reference (unique per product). */
    public record Copy(@NotBlank @Size(max = 30) String revision) {}

    /** {@code POST /api/boms/{id}}: replaces a draft's base output, yield and components. */
    public record Draft(
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal outputQty,

            @NotNull @DecimalMin("0.000001") @DecimalMax("1") @Digits(integer = 1, fraction = 6)
            BigDecimal yieldFactor,

            @NotEmpty @Size(max = 100) List<CatalogCommands.@Valid BomLine> lines) {}
}
