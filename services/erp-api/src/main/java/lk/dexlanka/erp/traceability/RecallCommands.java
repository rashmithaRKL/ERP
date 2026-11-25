package lk.dexlanka.erp.traceability;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request bodies of the V14 recall commands. Component names are the JSON field names and, in declaration order, the
 * idempotency payload hash: never rename or reorder them.
 */
public final class RecallCommands {
    private RecallCommands() {}

    static final String DATE = "\\d{4}-\\d{2}-\\d{2}";

    /**
     * Opens a recall. {@code basis} LOTS takes the suspect {@code lotIds}; SUPPLIER takes {@code supplierId}, the
     * receipt dates {@code receivedFrom}..{@code receivedTo} (Asia/Colombo) and optionally one {@code itemId}.
     * {@code scope} FORWARD affects the suspect lots and everything made from them; FORWARD_AND_SIBLINGS also every lot
     * made from the origin lots (purchased, opening or found stock) the suspects were made from. {@code defectCode}
     * classifies the RECALL nonconformance reports raised at containment. {@code depth} limits lot-to-lot hops (default
     * {@value TraceService#MAX_DEPTH}, the deepest trace).
     */
    public record Open(
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 4000) String reason,

            @NotBlank @Pattern(regexp = "MINOR|MAJOR|CRITICAL")
            String severity,

            @NotBlank @Pattern(regexp = "FORWARD|FORWARD_AND_SIBLINGS")
            String scope,

            @NotBlank @Pattern(regexp = "LOTS|SUPPLIER")
            String basis,

            @Size(max = 200) List<@NotBlank @Size(max = 64) String> lotIds,
            @Size(max = 64) String supplierId,
            @Size(max = 64) String itemId,

            @Pattern(regexp = DATE)
            String receivedFrom,

            @Pattern(regexp = DATE)
            String receivedTo,

            @NotBlank @Size(max = 20) String defectCode,

            @Min(1) @Max(TraceService.MAX_DEPTH)
            Integer depth) {}

    /** A customer told about the recall: on which day, whom (name, phone or email) and what was agreed. */
    public record Notify(
            @NotBlank @Pattern(regexp = DATE) String notifiedOn,
            @NotBlank @Size(max = 200) String contact,
            @Size(max = 2000) String notes) {}

    /**
     * Closes a contained recall. {@code overrideReason} is required (and must come from a different QUALITY/ADMIN user
     * than the one who opened the recall) while customers are still to be notified or held lots are not dispositioned.
     */
    public record Close(@Size(max = 2000) String note, @Size(max = 2000) String overrideReason) {}

    /** Cancels an open recall (opened by mistake); nothing was held yet. */
    public record Cancel(@NotBlank @Size(max = 2000) String reason) {}
}
