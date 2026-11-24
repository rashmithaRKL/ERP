package lk.dexlanka.erp.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies for inventory operations: warehouses, adjustments, transfers, cycle counts and the approval
 * threshold. Quantities are always in the item's base unit.
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class InventoryCommands {
    private InventoryCommands() {}

    public static final String WAREHOUSE_KINDS = "RAW|FINISHED|WIP|QUARANTINE|OTHER";
    public static final String CODE = "[A-Za-z0-9][A-Za-z0-9-]{0,19}";

    /** New warehouse in the tenant's plant. {@code code} is stored upper-case and unique per tenant. */
    public record Warehouse(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Pattern(regexp = CODE) String code,
            @NotBlank @Pattern(regexp = WAREHOUSE_KINDS) String kind) {}

    /** Full replacement of a warehouse's editable fields. */
    public record WarehouseUpdate(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Pattern(regexp = CODE) String code,
            @NotBlank @Pattern(regexp = WAREHOUSE_KINDS) String kind,
            @NotNull Boolean active) {}

    /** Tenant approval threshold: adjustments whose gross absolute value exceeds it need a second person. */
    public record Settings(
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2)
            BigDecimal approvalThreshold) {}

    /**
     * One adjustment line. {@code DECREASE}/{@code INCREASE} change an existing lot ({@code lotId}) at its current
     * cost; {@code FOUND} adds stock as a new released lot of {@code itemId} in {@code warehouseId} at {@code unitCost}
     * ({@code lotNumber} optional, generated when blank).
     */
    public record AdjustmentLine(
            @NotBlank @Pattern(regexp = "INCREASE|DECREASE|FOUND")
            String kind,

            @Size(max = 64) String lotId,
            @Size(max = 64) String itemId,
            @Size(max = 64) String warehouseId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal unitCost,

            @Size(max = 80) String lotNumber) {}

    public record Adjustment(
            @NotBlank @Pattern(regexp = "DAMAGE|EXPIRY|FOUND|COUNT_VARIANCE|SAMPLE|OTHER")
            String reason,

            @Size(max = 1000) String notes,
            @NotEmpty @Size(max = 200) List<@Valid @NotNull AdjustmentLine> lines) {}

    public record TransferLine(
            @NotBlank @Size(max = 64) String lotId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity) {}

    public record Transfer(
            @NotBlank @Size(max = 64) String fromWarehouseId,
            @NotBlank @Size(max = 64) String toWarehouseId,
            @Size(max = 1000) String notes,
            @NotEmpty @Size(max = 200) List<@Valid @NotNull TransferLine> lines) {}

    /**
     * Starts a cycle count of a warehouse, optionally narrowed to one item, one item type and/or one item category
     * including its sub-categories (V9).
     */
    public record Count(
            @NotBlank @Size(max = 64) String warehouseId,
            @Size(max = 64) String itemId,
            @Pattern(regexp = "[A-Z_]{0,40}") String itemType,
            boolean blind,
            @Size(max = 1000) String notes,
            @Size(max = 64) String categoryId) {
        /** The V5 shape (no category). */
        public Count(String warehouseId, String itemId, String itemType, boolean blind, String notes) {
            this(warehouseId, itemId, itemType, blind, notes, null);
        }
    }

    public record CountEntry(
            @NotBlank @Size(max = 64) String lineId,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal countedQty) {}

    public record CountEntries(@NotEmpty @Size(max = 500) List<@Valid @NotNull CountEntry> entries) {}
}
