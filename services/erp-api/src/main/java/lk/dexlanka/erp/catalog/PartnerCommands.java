package lk.dexlanka.erp.catalog;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request bodies for partner (customer/supplier) maintenance. Creation stays {@link CatalogCommands.Partner}.
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class PartnerCommands {
    private PartnerCommands() {}

    /** Full replacement of a partner's editable fields. {@code creditLimit} applies to customers only. */
    public record Update(
            @NotBlank @Size(max = 160) String name,
            @Email @Size(max = 160) String email,
            @Size(max = 40) String phone,
            @Size(max = 400) String billingAddress,
            @Size(max = 400) String shippingAddress,
            @Size(max = 40) String taxId,
            @NotNull @Min(0) @Max(365) Integer paymentDays,

            @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal creditLimit,

            @NotNull Boolean active) {}
}
