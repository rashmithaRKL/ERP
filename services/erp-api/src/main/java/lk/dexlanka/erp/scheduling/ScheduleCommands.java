package lk.dexlanka.erp.scheduling;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request bodies of the V13 scheduling commands. Component names are the JSON field names and, serialized in
 * declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class ScheduleCommands {
    private ScheduleCommands() {}

    /**
     * Runs the scheduler into a new DRAFT run. Null parameters take the defaults: a 28-day horizon, alternates on, open
     * downtime blocks 8 hours when no later maintenance window end is known, draft orders excluded.
     */
    public record Run(
            @Min(1) @Max(90) Integer horizonDays,
            Boolean useAlternates,
            @Min(1) @Max(168) Integer breakdownHours,
            Boolean includeDrafts,
            @Size(max = 400) String notes) {}

    /**
     * Pins an operation (or, without {@code operationId}, the estimated run of an order without a routing) to a work
     * center and start ({@code yyyy-MM-ddTHH:mm} in Asia/Colombo, or an ISO instant with offset). The start moves
     * forward to the next working minute of the work center. {@code rerun} also runs the scheduler into a new draft.
     */
    public record Pin(
            @Size(max = 64) String orderId,
            @Size(max = 64) String operationId,
            @NotBlank @Size(max = 64) String machineId,
            @NotBlank @Size(max = 40) String start,
            @Size(max = 400) String reason,
            boolean rerun) {}

    /** Removes a pin; {@code rerun} also runs the scheduler into a new draft. */
    public record Unpin(boolean rerun) {}

    /** The planner's remaining-duration estimate (working minutes) for an order without a routing. */
    public record Estimate(
            @NotBlank @Size(max = 64) String orderId,

            @NotNull @DecimalMin("1") @DecimalMax("525600") @Digits(integer = 6, fraction = 2)
            BigDecimal minutes,

            @Size(max = 400) String notes) {}
}
