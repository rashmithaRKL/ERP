package lk.dexlanka.erp.admin;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies of the administration commands ({@code /api/admin/**}, ADMIN only).
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class AdminCommands {
    public static final String ROLES = "ADMIN|PLANNER|WAREHOUSE|OPERATOR|QUALITY|SALES|VIEWER|MAINTENANCE|FINANCE";
    public static final String APPROVER_ROLES = "PLANNER|WAREHOUSE|QUALITY|SALES|OPERATOR|FINANCE";
    public static final String DOCUMENT_TYPES = "MANUFACTURING_ORDER|PURCHASE_ORDER|STOCK_ADJUSTMENT|MANUAL_JOURNAL";
    public static final String IMPORT_KINDS = "ITEMS|PARTNERS|OPENING_STOCK";

    /** Largest accepted CSV upload (characters of the decoded text). */
    public static final int MAX_FILE_CHARS = 2_000_000;

    private AdminCommands() {}

    /** Full replacement of a user's editable fields. Email changes are not supported (it is the sign-in name). */
    public record UserUpdate(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = ROLES) String role) {}

    /**
     * Approval policy of one document type. {@code amountThreshold} null = every document needs approval (when
     * {@code approvalRequired}); otherwise only documents whose value exceeds it. ADMIN may always approve; the
     * creator never may.
     */
    public record Policy(
            @NotNull Boolean approvalRequired,

            @DecimalMin("0") @Digits(integer = 12, fraction = 2)
            BigDecimal amountThreshold,

            @NotNull @Size(max = 6) List<@NotBlank @Pattern(regexp = APPROVER_ROLES) String> approverRoles) {}

    /** A CSV file as text (UTF-8, optional BOM). Imports run a dry run first; see {@code ImportService}. */
    public record ImportFile(
            @NotBlank @Size(max = 200) String fileName,
            @NotBlank @Size(max = MAX_FILE_CHARS) String content) {}

    /** Close every open day up to and including {@code endDate} (yyyy-MM-dd). */
    public record ClosePeriod(
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
            String endDate) {}

    /** Reopen the most recently closed period; the reason is kept in the audit trail. */
    public record ReopenPeriod(
            @NotBlank @Size(min = 10, max = 400) String reason) {}
}
