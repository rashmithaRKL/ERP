package lk.dexlanka.erp.routing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for the skill catalogue and user skills (V10).
 *
 * <p>Bean Validation annotations are the input contract (HTTP 400). Component names are the JSON field names and,
 * serialized in declaration order, the idempotency payload hash: never rename or reorder them.
 */
public final class SkillCommands {
    private SkillCommands() {}

    /** {@code POST /api/skills}. */
    public record Skill(
            @NotBlank @Pattern(regexp = CalendarCommands.CODE)
            String code,

            @NotBlank @Size(max = 120) String name,
            @Size(max = 400) String description) {}

    /** {@code POST /api/skills/{id}}: an inactive skill cannot be required by new routings or assigned again. */
    public record SkillUpdate(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 400) String description,
            @NotNull Boolean active) {}

    /**
     * {@code POST /api/skills/assignments}: sets a user's level (1 trainee ... 5 expert) and optional certification
     * expiry (last valid day) for a skill.
     */
    public record UserSkill(
            @NotBlank @Size(max = 64) String userId,
            @NotBlank @Size(max = 64) String skillId,
            @NotNull @Min(1) @Max(5) Integer level,
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String certifiedUntil,
            @Size(max = 400) String notes) {}

    /** {@code POST /api/skills/assignments/remove}. */
    public record RemoveUserSkill(
            @NotBlank @Size(max = 64) String userId,
            @NotBlank @Size(max = 64) String skillId) {}
}
