package lk.dexlanka.erp.procurement;

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
 * Request bodies for purchase-order, goods-receipt, supplier-invoice and supplier-return commands.
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class ProcurementCommands {
    private ProcurementCommands() {}

    // ------------------------------------------------------------------ original single-item contract

    /**
     * Single-item purchase ({@code POST /api/purchases}), created and submitted for approval in one step. Kept for
     * existing clients; the order is stored as a header with one line in the item's base unit.
     */
    public record Purchase(
            @NotBlank String supplierId,
            @NotBlank String itemId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal unitCost,

            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
            String dueDate) {}

    /** Single-lot receipt of the order's only open line ({@code POST /api/purchases/{id}/receive}). */
    public record Receive(
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotBlank String warehouseId,
            @NotBlank @Size(max = 80) String supplierLot) {}

    /** Whole-receipt invoice match at the purchase price ({@code POST /api/receipts/{id}/invoice}). */
    public record Invoice(@NotBlank @Size(max = 80) String reference) {}

    // ------------------------------------------------------------------ purchase order documents

    /**
     * One purchase-order line. {@code quantity} and {@code unitCost} are in {@code unit}: the item's base unit (the
     * default) or a unit the item can be purchased in. {@code id} keeps an existing line's identity when a draft is
     * edited; {@code expectedDate} defaults to the order's expected date.
     */
    public record OrderLine(
            @Size(max = 64) String id,
            @NotBlank String itemId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @Size(max = 20) String unit,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal unitCost,

            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String expectedDate) {}

    /**
     * Draft purchase order: create ({@code POST /api/purchases/orders}) or replace a draft ({@code POST .../{id}}). The
     * supplier may be left empty on a draft; it is required to submit.
     */
    public record Order(
            @Size(max = 64) String supplierId,
            @Size(max = 80) String supplierReference,

            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
            String dueDate,

            @Size(max = 2000) String notes,
            @NotNull @Size(max = 100) List<@Valid OrderLine> lines) {}

    /** Reason for cancelling an order or closing it short. */
    public record Reason(@NotBlank @Size(max = 400) String reason) {}

    // ------------------------------------------------------------------ receiving

    /**
     * Quantity of one order line received in this delivery, in {@code unit} (default: the line's purchase unit).
     * {@code supplierLot} becomes the lot number (generated from the goods-receipt number when empty).
     */
    public record ReceiptLine(
            @NotBlank @Size(max = 64) String lineId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @Size(max = 20) String unit,
            @Size(max = 80) String supplierLot) {}

    /** One goods receipt: one or more order lines delivered under one delivery note into a raw-material warehouse. */
    public record GoodsReceipt(
            @NotBlank @Size(max = 64) String warehouseId,
            @NotBlank @Size(max = 120) String deliveryNote,
            @NotEmpty @Size(max = 100) List<@Valid ReceiptLine> lines) {}

    // ------------------------------------------------------------------ supplier invoices and returns

    /** One invoice line matched to a quality-released receipt: billed base quantity and price per base unit. */
    public record InvoiceLine(
            @NotBlank @Size(max = 64) String receiptId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal unitPrice) {}

    /** A supplier invoice matched against receipts of one purchase order. */
    public record SupplierInvoice(
            @NotBlank @Size(max = 80) String supplierReference,

            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
            String invoiceDate,

            @Size(max = 2000) String notes,
            @NotEmpty @Size(max = 100) List<@Valid InvoiceLine> lines) {}

    /** Return of released stock from a receipt to the supplier (base quantity). */
    public record SupplierReturn(
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotBlank @Size(max = 400) String reason,
            @NotBlank @Size(max = 80) String debitNote) {}

    // ------------------------------------------------------------------ planning

    /** One material-planning shortage to buy (base quantity); {@code supplierId} overrides the preferred supplier. */
    public record Recommendation(
            @NotBlank @Size(max = 64) String itemId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @Size(max = 64) String supplierId) {}

    /** Draft purchase orders from several shortages: one per supplier, plus one without a supplier when unknown. */
    public record Recommendations(
            @NotEmpty @Size(max = 100) List<@Valid Recommendation> lines,
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String dueDate) {}
}
