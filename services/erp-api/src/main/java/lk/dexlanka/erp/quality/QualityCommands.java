package lk.dexlanka.erp.quality;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for quality-inspection commands.
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class QualityCommands {
    private QualityCommands() {}

    public record Inspect(
            @NotBlank @Pattern(regexp = "PASSED|FAILED") String result,
            @NotBlank @Size(max = 2000) String notes) {}
}
