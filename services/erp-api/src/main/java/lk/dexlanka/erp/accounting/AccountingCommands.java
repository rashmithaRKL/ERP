package lk.dexlanka.erp.accounting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies of the general-ledger commands ({@code /api/accounting/**}, V24; FINANCE and ADMIN).
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class AccountingCommands {
    /** Account codes: upper-case letters, digits, underscore, dot or hyphen (the posting codes are UPPER_SNAKE). */
    public static final String CODE = "[A-Z0-9][A-Z0-9_.-]{0,39}";

    public static final String TYPES = "ASSET|LIABILITY|EQUITY|INCOME|EXPENSE";
    public static final String DATE = "\\d{4}-\\d{2}-\\d{2}";
    /** Most lines on one manual journal. */
    public static final int MAX_LINES = 200;

    private AccountingCommands() {}

    /**
     * A chart-of-accounts entry (create, or full replacement on update). {@code header} = a grouping account that takes
     * no postings; {@code contra} flips the type's normal balance (e.g. sales returns under income). {@code parentId} is
     * a header account of the same type, or null for a top-level account.
     */
    public record Account(
            @NotBlank @Pattern(regexp = CODE) String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = TYPES) String type,
            Boolean contra,
            Boolean header,
            @Size(max = 80) String parentId,
            @Size(max = 400) String description) {}

    /** One manual journal line: an account code and exactly one positive amount (to the cent), with an optional memo. */
    public record Line(
            @NotBlank @Size(max = 80) String account,

            @DecimalMin("0") @Digits(integer = 12, fraction = 2)
            BigDecimal debit,

            @DecimalMin("0") @Digits(integer = 12, fraction = 2)
            BigDecimal credit,

            @Size(max = 200) String memo) {}

    /**
     * A manual journal (create, or full replacement of a draft): the posting date (yyyy-MM-dd, the accounting date of
     * the entry it will post), the narration and the lines. Drafts may be incomplete; submitting needs at least two
     * lines whose debits equal their credits.
     */
    public record Journal(
            @NotBlank @Pattern(regexp = DATE) String postingDate,
            @NotBlank @Size(max = 400) String narration,
            @NotNull @Size(max = MAX_LINES) List<@Valid @NotNull Line> lines) {}

    /** The reason of a return to draft or a cancellation (kept on the journal and in the audit trail). */
    public record Reason(@NotBlank @Size(min = 5, max = 400) String reason) {}

    /** Reverses a posted entry on {@code postingDate} (yyyy-MM-dd; default today) with a reason. */
    public record Reverse(
            @Pattern(regexp = DATE) String postingDate,
            @NotBlank @Size(min = 5, max = 400) String reason) {}
}
