package lk.dexlanka.erp.sales;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request bodies for sales-order and dispatch commands.
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class SalesCommands {
    private SalesCommands() {}

    public record Sale(
            @NotBlank String customerId,
            @NotBlank String productId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal unitPrice,

            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
            String dueDate) {}

    public record Dispatch(
            @NotBlank String lotId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotBlank @Size(max = 120) String reference) {}
}
