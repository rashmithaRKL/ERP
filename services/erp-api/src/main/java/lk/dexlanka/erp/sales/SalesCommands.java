package lk.dexlanka.erp.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
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
 * Request bodies for sales-order, dispatch and customer-return commands.
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class SalesCommands {
    private SalesCommands() {}

    /**
     * Single-item order, created and confirmed in one step ({@code POST /api/sales}). Kept for existing clients; the
     * order is stored as a confirmed header with one line.
     */
    public record Sale(
            @NotBlank String customerId,
            @NotBlank String productId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal unitPrice,

            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
            String dueDate) {}

    /**
     * Single-lot dispatch ({@code POST /api/sales/{id}/dispatch}). Without {@code lineId} the order line is the only
     * outstanding line of the lot's product.
     */
    public record Dispatch(
            @NotBlank String lotId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotBlank @Size(max = 120) String reference,
            @Size(max = 64) String lineId) {
        public Dispatch(String lotId, BigDecimal quantity, String reference) {
            this(lotId, quantity, reference, null);
        }
    }

    /**
     * One sales-order line. {@code quantity} and {@code unitPrice} are in {@code unit} (V9): the product's base unit
     * (the default) or a unit it may be sold in (a conversion flagged for sales, or a standard unit of the same kind).
     * {@code id} is only meaningful when editing a draft (keep the id of an existing line to keep its identity); {@code
     * discountPct} defaults to zero.
     */
    public record Line(
            @Size(max = 64) String id,
            @NotBlank String productId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal unitPrice,

            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4)
            BigDecimal discountPct,

            @Size(max = 20) String unit) {
        /** A line in the product's base unit (the V3 shape). */
        public Line(String id, String productId, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPct) {
            this(id, productId, quantity, unitPrice, discountPct, null);
        }
    }

    /** Draft sales order: create ({@code POST /api/sales/orders}) or replace a draft ({@code POST .../{id}}). */
    public record Order(
            @NotBlank String customerId,
            @Size(max = 80) String customerReference,

            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
            String dueDate,

            @Size(max = 2000) String notes,
            @NotNull @Size(max = 100) List<@Valid Line> lines) {}

    /** Reason for cancelling or closing an order short. */
    public record Reason(@NotBlank @Size(max = 400) String reason) {}

    /** Quantity taken from one released lot. */
    public record Allocation(
            @NotBlank String lotId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity) {}

    /** Dispatch of one order line from one or more released lots under one delivery-note reference. */
    public record DispatchLine(
            @NotBlank @Size(max = 120) String reference,
            @NotEmpty @Size(max = 20) List<@Valid Allocation> lots) {}

    /** Customer return of part or all of a delivery. */
    public record Return(
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotBlank @Size(max = 400) String reason) {}
}
