package lk.dexlanka.erp.quality;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies for inspection plans and the defect-code catalogue (V12). Component names are the JSON field names
 * and, in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class InspectionPlanCommands {
    private InspectionPlanCommands() {}

    /** A new plan for one item or one item category (exactly one of them) and one inspection type. */
    public record Plan(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 64) String itemId,
            @Size(max = 64) String categoryId,
            @NotBlank @Pattern(regexp = "INCOMING|FINAL|RETURN|REWORK") String inspectionType,
            @Size(max = 2000) String notes,
            @NotEmpty @Size(max = 40) List<@Valid @NotNull Characteristic> characteristics) {}

    /**
     * Edits a plan (its item/category and type are fixed): the characteristic set is replaced and the version grows.
     * Deactivating a plan stops it from governing new decisions.
     */
    public record PlanUpdate(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String notes,
            @NotNull Boolean active,
            @NotEmpty @Size(max = 40) List<@Valid @NotNull Characteristic> characteristics) {}

    /**
     * A VARIABLE characteristic is measured ({@code unit}, optional {@code nominal}, at least one specification limit,
     * up to 20 readings); an ATTRIBUTE characteristic is a pass/fail check of {@code sampleSize} units.
     */
    public record Characteristic(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "VARIABLE|ATTRIBUTE") String kind,
            @Size(max = 20) String unit,
            @Digits(integer = 14, fraction = 6) BigDecimal nominal,
            @Digits(integer = 14, fraction = 6) BigDecimal lowerLimit,
            @Digits(integer = 14, fraction = 6) BigDecimal upperLimit,
            @NotNull @Min(1) @Max(100000) Integer sampleSize,
            @NotNull Boolean critical,
            @Size(max = 20) String defectCode,
            @Size(max = 200) String method) {}

    /** A defect code: created with {@code POST /quality/defect-codes}, edited with {@code POST .../{code}}. */
    public record DefectCode(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,19}") String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 400) String description,
            @NotNull Boolean active) {}
}
