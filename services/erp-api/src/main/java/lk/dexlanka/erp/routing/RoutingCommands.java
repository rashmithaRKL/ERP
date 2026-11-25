package lk.dexlanka.erp.routing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies for routing revisions (V10).
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class RoutingCommands {
    private RoutingCommands() {}

    /**
     * One operation of a routing. {@code runMinutesPerUnit} is the run time per base unit of the product (1,200 pcs/h =
     * 0.05); {@code setupMinutes} is once per order. {@code crewSize} is the number of people tending the operation
     * (labour minutes = machine minutes x crew). Alternates must be work centers of the same kind as {@code machineId}.
     */
    public record Operation(
            @NotNull @Min(1) @Max(9999) Integer seq,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 64) String machineId,
            @Size(max = 10) List<@NotBlank @Size(max = 64) String> alternateMachineIds,

            @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 6)
            BigDecimal setupMinutes,

            @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 12)
            BigDecimal runMinutesPerUnit,

            @NotNull @DecimalMin("0.01") @Digits(integer = 4, fraction = 2)
            BigDecimal crewSize,

            @Size(max = 64) String skillId,
            @Min(1) @Max(5) Integer skillLevel,
            Boolean inspectionPoint,
            @Size(max = 4000) String instructions) {}

    /** {@code POST /api/routings}: a new draft revision for a finished or semi-finished product. */
    public record Routing(
            @NotBlank @Size(max = 64) String productId,
            @NotBlank @Size(max = 30) String revision,
            @Size(max = 2000) String notes,
            @NotEmpty @Size(max = 50) List<@Valid Operation> operations) {}

    /** {@code POST /api/routings/{id}}: replaces a draft's revision text, notes and operations. */
    public record Draft(
            @NotBlank @Size(max = 30) String revision,
            @Size(max = 2000) String notes,
            @NotEmpty @Size(max = 50) List<@Valid Operation> operations) {}

    /** {@code POST /api/routings/{id}/copy}: an approved revision copied into a new draft with this revision text. */
    public record Copy(@NotBlank @Size(max = 30) String revision) {}
}
