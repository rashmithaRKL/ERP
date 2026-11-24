package lk.dexlanka.erp.manufacturing;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies for manufacturing-order commands.
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class ManufacturingCommands {
    private ManufacturingCommands() {}

    public record Manufacture(
            @NotBlank String bomId,
            @NotBlank String machineId,
            String salesOrderId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotBlank @Pattern(regexp = "NORMAL|HIGH|URGENT")
            String priority,

            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
            String dueDate,

            /* The customer order line this run fulfils (V3); takes precedence over salesOrderId. */
            @Size(max = 64) String salesOrderLineId,

            /* V7: the draft/approved order that consumes this order's semi-finished output (a child order). */
            @Size(max = 64) String parentOrderId) {
        public Manufacture(
                String bomId,
                String machineId,
                String salesOrderId,
                BigDecimal quantity,
                String priority,
                String dueDate) {
            this(bomId, machineId, salesOrderId, quantity, priority, dueDate, null, null);
        }

        /** The V3 shape (customer order line, no parent order). */
        public Manufacture(
                String bomId,
                String machineId,
                String salesOrderId,
                BigDecimal quantity,
                String priority,
                String dueDate,
                String salesOrderLineId) {
            this(bomId, machineId, salesOrderId, quantity, priority, dueDate, salesOrderLineId, null);
        }
    }

    /**
     * {@code POST /api/manufacturing/{id}/children} (V7): one child order per short semi-finished component of the parent.
     * All fields are optional: the work center defaults to the parent's, the priority to the parent's, and
     * {@code itemIds} limits the run to some components.
     */
    public record ChildOrders(
            @Size(max = 64) String machineId,
            @Pattern(regexp = "NORMAL|HIGH|URGENT") String priority,
            @Size(max = 50) List<@NotBlank @Size(max = 64) String> itemIds) {}

    public record Complete(
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal goodQty,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal scrapQty,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal labourCost,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal machineCost,

            @NotNull @Size(max = 2000) String notes) {}
}
