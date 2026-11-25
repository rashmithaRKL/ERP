package lk.dexlanka.erp.performance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request bodies of the OEE commands (V15). Component names are the JSON field names and, serialized in declaration
 * order, the idempotency payload hash: never rename or reorder them.
 */
public final class PerformanceCommands {
    private PerformanceCommands() {}

    /**
     * {@code POST /api/performance/close}: freezes the OEE of a completed business day ({@code date}, Asia/Colombo) for
     * the given work centers (empty = every work center). A day that is already closed is left unchanged unless {@code
     * recalculate} is true: then a new version is recorded with {@code reason} and the previous one is kept as
     * superseded history.
     */
    public record Close(
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String date,
            @Size(max = 200) List<@NotBlank @Size(max = 64) String> machineIds,
            Boolean recalculate,
            @Size(max = 400) String reason) {}
}
