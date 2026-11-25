package lk.dexlanka.erp.quality;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request bodies for corrective and preventive actions (CAPA, V12). Component names are the JSON field names and, in
 * declaration order, the idempotency payload hash: never rename or reorder them. Dates are {@code yyyy-MM-dd}.
 */
public final class CapaCommands {
    private CapaCommands() {}

    /** A new CAPA, linked to zero or more NCRs (standalone CAPAs come from audits, customers or internal reviews). */
    public record Capa(
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 4000) String problem,
            @NotBlank @Pattern(regexp = "NCR|AUDIT|CUSTOMER|SUPPLIER|INTERNAL") String source,
            @NotBlank @Size(max = 64) String ownerId,
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String dueDate,
            @Size(max = 50) List<@NotBlank @Size(max = 64) String> ncrIds) {}

    /** Edits a CAPA that is not closed or cancelled: texts, owner, due date, root-cause analysis and linked NCRs. */
    public record Update(
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 4000) String problem,
            @NotBlank @Size(max = 64) String ownerId,
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String dueDate,
            @Pattern(regexp = "MAN|MACHINE|METHOD|MATERIAL|MEASUREMENT|ENVIRONMENT") String rootCauseCategory,
            @Size(max = 5) List<@NotNull @Size(max = 1000) String> whys,
            @Size(max = 4000) String rootCause,
            @Size(max = 50) List<@NotBlank @Size(max = 64) String> ncrIds) {}

    public record Action(
            @NotBlank @Pattern(regexp = "CORRECTIVE|PREVENTIVE") String type,
            @NotBlank @Size(max = 1000) String description,
            @NotBlank @Size(max = 64) String ownerId,
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String dueDate) {}

    /** Completion or cancellation note of an action; note of a lifecycle step. */
    public record Note(@NotBlank @Size(max = 2000) String note) {}

    /** Effectiveness verification: effective closes the CAPA, not effective returns it to work. */
    public record Verify(@NotNull Boolean effective, @NotBlank @Size(max = 2000) String note) {}
}
