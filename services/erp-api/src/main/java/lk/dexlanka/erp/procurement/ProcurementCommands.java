package lk.dexlanka.erp.procurement;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request bodies for purchase, goods-receipt and supplier-invoice commands.
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class ProcurementCommands {
    private ProcurementCommands() {}

    public record Purchase(
            @NotBlank String supplierId,
            @NotBlank String itemId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal unitCost,

            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
            String dueDate) {}

    public record Receive(
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotBlank String warehouseId,
            @NotBlank @Size(max = 80) String supplierLot) {}

    public record Invoice(@NotBlank @Size(max = 80) String reference) {}
}
