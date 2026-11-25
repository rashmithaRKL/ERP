package lk.dexlanka.erp.manufacturing;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request bodies for manufacturing-order operation commands (V10, shop floor).
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class OperationCommands {
    private OperationCommands() {}

    /**
     * {@code POST /api/operations/{id}/start}. {@code machineId}: the planned work center (default) or one of the
     * routing operation's alternates. {@code activity}: {@code SETUP} or {@code RUN}; by default SETUP when the
     * operation plans setup time and none was logged yet, else RUN. {@code overrideReason}: PLANNER/ADMIN only, to
     * start although the acting user lacks the required skill (recorded on the time log and audited).
     */
    public record Start(
            @Size(max = 64) String machineId,
            @Pattern(regexp = "SETUP|RUN") String activity,
            @Size(max = 400) String overrideReason) {}

    /** {@code POST /api/operations/{id}/resume}: default activity = the one that was paused. */
    public record Resume(
            @Pattern(regexp = "SETUP|RUN") String activity,
            @Size(max = 400) String overrideReason) {}

    /** {@code POST /api/operations/{id}/pause}. */
    public record Pause(@Size(max = 400) String reason) {}

    /**
     * {@code POST /api/operations/{id}/report} and {@code /finish}: good and scrap quantities in the product's base unit
     * processed since the last report (finish may report zero when everything was reported already). Scrap needs notes.
     */
    public record Quantities(
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal goodQty,

            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6)
            BigDecimal scrapQty,

            @Size(max = 2000) String notes) {}
}
