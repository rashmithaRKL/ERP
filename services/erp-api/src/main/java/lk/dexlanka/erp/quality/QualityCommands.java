package lk.dexlanka.erp.quality;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.Valid;
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
 * Request bodies for quality-inspection commands.
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class QualityCommands {
    private QualityCommands() {}

    /**
     * An inspection decision. {@code measurements} (V12, optional) are required when an inspection plan governs the
     * inspection: one entry per active characteristic of the plan.
     */
    public record Inspect(
            @NotBlank @Pattern(regexp = "PASSED|FAILED") String result,
            @NotBlank @Size(max = 2000) String notes,
            @Size(max = 40) List<@Valid @NotNull Measurement> measurements) {
        @JsonCreator
        public Inspect {}

        /** The Phase 1 decision without measurements. */
        public Inspect(String result, String notes) {
            this(result, notes, null);
        }
    }

    /**
     * The result of one plan characteristic: {@code values} are the readings of a VARIABLE characteristic (exactly its
     * sample size), {@code nonconforming} the nonconforming units found in the sample of an ATTRIBUTE characteristic.
     * {@code note} is required to pass an inspection with this non-critical characteristic out of specification.
     */
    public record Measurement(
            @NotBlank @Size(max = 64) String characteristicId,
            @Size(max = 20) List<@NotNull @Digits(integer = 14, fraction = 6) BigDecimal> values,
            @Min(0) @Max(100000) Integer nonconforming,
            @Size(max = 500) String note) {}
}
