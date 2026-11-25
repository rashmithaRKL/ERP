package lk.dexlanka.erp.maintenance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies of the maintenance commands ({@code /api/maintenance/**}).
 *
 * <p>Date-times ({@code startedAt}, {@code scheduledStart}, ...) are ISO-8601 strings with an offset, for example
 * {@code 2026-11-25T08:30:00+05:30} or {@code 2026-11-25T03:00:00Z}; dates are {@code yyyy-MM-dd}. Component names are
 * the JSON field names and, serialized in declaration order, the idempotency payload hash: never rename or reorder
 * them.
 */
public final class MaintenanceCommands {
    /** The stable downtime reason codes (OEE and the Pareto read them). */
    public static final String REASONS =
            "BREAKDOWN|PLANNED_MAINTENANCE|CHANGEOVER|MATERIAL_SHORTAGE|NO_OPERATOR|QUALITY_HOLD|UTILITIES|OTHER";

    public static final String PRIORITIES = "NORMAL|HIGH|URGENT";

    private MaintenanceCommands() {}

    // ------------------------------------------------------------------ downtime

    /**
     * Reports a stop of a machine. Without {@code endedAt} the event stays open until it is closed. {@code planned}
     * defaults per reason (BREAKDOWN is always unplanned, PLANNED_MAINTENANCE and CHANGEOVER default to planned).
     */
    public record ReportDowntime(
            @NotBlank @Size(max = 64) String machineId,
            @NotBlank @Pattern(regexp = REASONS) String reason,
            Boolean planned,
            @Size(max = 40) String startedAt,
            @Size(max = 40) String endedAt,
            @Size(max = 64) String manufacturingOrderId,
            @Size(max = 1000) String notes) {}

    public record CloseDowntime(
            @Size(max = 40) String endedAt,
            @Size(max = 1000) String notes) {}

    /**
     * Corrects a closed event: the original becomes SUPERSEDED and the corrected event is recorded with these values.
     * {@code withdraw} records the event as VOID instead (it never happened).
     */
    public record CorrectDowntime(
            @NotBlank @Pattern(regexp = REASONS) String reason,
            Boolean planned,
            @NotBlank @Size(max = 40) String startedAt,
            @NotBlank @Size(max = 40) String endedAt,
            @Size(max = 1000) String notes,
            @NotBlank @Size(max = 400) String correctionReason,
            boolean withdraw) {}

    // ------------------------------------------------------------------ work orders

    /**
     * A corrective (breakdown) request. {@code breakdown} also opens a BREAKDOWN downtime event at {@code
     * downtimeStartedAt} (default now); {@code downtimeEventId} links an already reported open event instead.
     */
    public record RequestWorkOrder(
            @NotBlank @Size(max = 64) String machineId,
            @NotBlank @Size(max = 160) String title,
            @Size(max = 2000) String description,
            @Pattern(regexp = PRIORITIES) String priority,
            boolean breakdown,
            @Size(max = 40) String downtimeStartedAt,
            @Size(max = 64) String downtimeEventId,
            @Size(max = 64) String manufacturingOrderId) {}

    /** Approval, optionally with the maintenance window (the order is then SCHEDULED) and an assignee. */
    public record Approve(
            @Size(max = 40) String scheduledStart,
            @Size(max = 40) String scheduledEnd,
            @Size(max = 64) String assigneeId) {}

    public record Schedule(
            @NotBlank @Size(max = 40) String scheduledStart,
            @NotBlank @Size(max = 40) String scheduledEnd,
            @Size(max = 64) String assigneeId) {}

    /** The result of checklist item {@code index} (0-based) of the order's checklist. */
    public record ChecklistResult(
            @Min(0) @Max(99) int index,
            @Pattern(regexp = "PASS|FAIL|NA") String result,
            @Size(max = 400) String note) {}

    /** Work in progress: checklist results, findings and actions so far. Null fields are left unchanged. */
    public record Progress(
            @Valid @Size(max = 100) List<ChecklistResult> checklist,
            @Size(max = 4000) String findings,
            @Size(max = 4000) String actions) {}

    /**
     * Labour hours of one technician (default: the signed-in user), costed at the tenant's maintenance labour rate.
     * Negative hours reverse an earlier entry.
     */
    public record Labour(
            @NotNull @DecimalMin("-1000") @DecimalMax("1000") @Digits(integer = 4, fraction = 2)
            BigDecimal hours,

            @Size(max = 64) String technicianId,
            @Size(max = 400) String notes) {}

    /** Issues a spare part from released stock, oldest lots first. */
    public record IssuePart(
            @NotBlank @Size(max = 64) String itemId,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @Size(max = 400) String notes) {}

    /** Returns unused parts of one issue movement to the lot they came from. */
    public record ReturnPart(
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,

            @Size(max = 400) String notes) {}

    /** Completes the work: findings are required; every checklist item needs a result. */
    public record Complete(
            @NotBlank @Size(max = 4000) String findings,
            @Size(max = 4000) String actions,
            @Valid @Size(max = 100) List<ChecklistResult> checklist,

            @DecimalMin("0") @Digits(integer = 12, fraction = 2)
            BigDecimal meterReading) {}

    public record Cancel(@NotBlank @Size(max = 400) String reason) {}

    // ------------------------------------------------------------------ preventive plans

    public record Plan(
            @NotBlank @Size(max = 64) String machineId,
            @NotBlank @Size(max = 160) String title,
            @Size(max = 2000) String description,
            @NotNull @Size(max = 40) List<@NotBlank @Size(max = 200) String> checklist,
            @NotNull @Min(1) @Max(3650) Integer intervalDays,

            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2)
            BigDecimal intervalHours,

            @NotBlank @Size(max = 10) String nextDueDate,

            @DecimalMin(value = "0", inclusive = false) @DecimalMax("720") @Digits(integer = 3, fraction = 2)
            BigDecimal estimatedHours,

            @Pattern(regexp = PRIORITIES) String priority) {}

    public record PlanUpdate(
            @NotBlank @Size(max = 160) String title,
            @Size(max = 2000) String description,
            @NotNull @Size(max = 40) List<@NotBlank @Size(max = 200) String> checklist,
            @NotNull @Min(1) @Max(3650) Integer intervalDays,

            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2)
            BigDecimal intervalHours,

            @NotBlank @Size(max = 10) String nextDueDate,

            @DecimalMin(value = "0", inclusive = false) @DecimalMax("720") @Digits(integer = 3, fraction = 2)
            BigDecimal estimatedHours,

            @Pattern(regexp = PRIORITIES) String priority,
            @NotNull Boolean active) {}

    /** Creates preventive orders for active plans due within {@code horizonDays} days (0 = due today or overdue). */
    public record Generate(@Min(0) @Max(31) int horizonDays) {}

    // ------------------------------------------------------------------ machines and settings

    public record Meter(
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2)
            BigDecimal reading,

            @Size(max = 400) String notes) {}

    /** A planner's correction of a wrong machine status. MAINTENANCE is only entered by starting a work order. */
    public record MachineStatus(
            @NotBlank @Pattern(regexp = "IDLE|RUNNING") String status,
            @NotBlank @Size(max = 400) String reason) {}

    public record Settings(
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2)
            BigDecimal labourRate) {}
}
