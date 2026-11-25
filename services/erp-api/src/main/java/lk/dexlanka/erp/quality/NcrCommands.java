package lk.dexlanka.erp.quality;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies for nonconformance reports, dispositions and rework orders (V12). Component names are the JSON field
 * names and, in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class NcrCommands {
    private NcrCommands() {}

    /**
     * A manually raised NCR. With a lot, the item, quantity unit and source links (MO, supplier, customer return,
     * rework order) are taken from the lot, and a released lot is put on hold (quarantine). Without a lot the NCR
     * documents a nonconformance with nothing to disposition and closes directly.
     */
    public record Raise(
            @NotBlank @Pattern(regexp = "PRODUCTION|SUPPLIER|CUSTOMER|INTERNAL|AUDIT|RECALL")
            String source,

            @NotBlank @Pattern(regexp = "MINOR|MAJOR|CRITICAL")
            String severity,

            @NotBlank @Size(max = 20) String defectCode,
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 4000) String description,
            @Size(max = 2000) String containment,
            @Size(max = 64) String itemId,
            @Size(max = 64) String lotId,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @Size(max = 64) String manufacturingOrderId,
            @Size(max = 64) String supplierId) {}

    /** Edits the classification and texts of an open NCR. */
    public record Update(
            @NotBlank @Pattern(regexp = "MINOR|MAJOR|CRITICAL")
            String severity,

            @NotBlank @Size(max = 20) String defectCode,
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 4000) String description,
            @Size(max = 2000) String containment) {}

    /**
     * One disposition of part of the affected quantity. {@code instructions} are the rework instructions (REWORK
     * only).
     */
    public record Disposition(
            @NotBlank @Pattern(regexp = "USE_AS_IS|SCRAP|RETURN_TO_SUPPLIER|REWORK")
            String type,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @NotBlank @Size(max = 2000) String note,
            @Size(max = 2000) String instructions) {}

    /** Approves or rejects a pending concession (use as is). */
    public record Decision(
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(max = 2000) String note) {}

    /** Posts the supplier return of a RETURN_TO_SUPPLIER disposition (warehouse). */
    public record ShipReturn(
            @NotBlank @Size(max = 80) String debitNote,
            @Size(max = 400) String reason) {}

    public record Close(@NotBlank @Size(max = 2000) String note) {}

    public record Cancel(@NotBlank @Size(max = 400) String reason) {}

    /**
     * Completes a rework order: the output quantity (at most the input) enters a new quarantined lot whose value is the
     * input value plus {@code labourCost} plus the value of the extra {@code materials} issued from released lots.
     */
    public record ReworkComplete(
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal outputQuantity,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal labourCost,

            @Size(max = 20) List<@Valid @NotNull Material> materials,
            @Size(max = 2000) String note) {}

    public record Material(
            @NotBlank @Size(max = 64) String lotId,

            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
            BigDecimal quantity) {}
}
