package lk.dexlanka.erp.routing;

import static lk.dexlanka.erp.platform.Store.str;

import java.time.LocalDate;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Component;

/**
 * Whether a user may perform work that requires a skill (V10). Used by {@code manufacturing.OperationService} before an
 * operation starts or resumes, and by the shop-floor queue for skill hints. A user qualifies when they hold the skill
 * at the required level or higher and its certification has not expired: {@code certified_until} is the last valid day
 * (Asia/Colombo), NULL = no expiry. Read-only; runs inside the caller's transaction.
 */
@Component
public class SkillRules {
    /** Outcome of a skill check. {@code ok()} for {@code NOT_REQUIRED} and {@code QUALIFIED}. */
    public record Check(
            String status,
            String skillCode,
            String skillName,
            int requiredLevel,
            Integer level,
            String certifiedUntil) {
        public static final String NOT_REQUIRED = "NOT_REQUIRED";
        public static final String QUALIFIED = "QUALIFIED";
        public static final String MISSING = "MISSING";
        public static final String EXPIRED = "EXPIRED";
        public static final String LEVEL_TOO_LOW = "LEVEL_TOO_LOW";

        public boolean ok() {
            return NOT_REQUIRED.equals(status) || QUALIFIED.equals(status);
        }

        /** A sentence for a refused start, e.g. "Injection moulding (IM) level 2 or higher is required; ...". */
        public String message(String who) {
            String need =
                    skillName + " (" + skillCode + ")" + (requiredLevel > 1 ? " level " + requiredLevel + "+" : "");
            return switch (status) {
                case MISSING -> who + " does not hold the required skill " + need;
                case EXPIRED -> who + "'s certification for " + need + " expired on " + certifiedUntil;
                case LEVEL_TOO_LOW -> who + " holds " + skillName + " at level " + level + "; " + need + " is required";
                default -> "";
            };
        }
    }

    private final Store s;

    SkillRules(Store s) {
        this.s = s;
    }

    /** Checks {@code userId} against {@code skillId} (null = no skill required) at {@code requiredLevel} on {@code day}. */
    public Check check(String tenant, String userId, String skillId, int requiredLevel, LocalDate day) {
        if (skillId == null || skillId.isBlank()) return new Check(Check.NOT_REQUIRED, null, null, 0, null, null);
        var skill = s.one("SELECT code,name FROM skills WHERE tenant_id=? AND id=?", tenant, skillId);
        var held = s.list(
                "SELECT level,certified_until FROM user_skills WHERE tenant_id=? AND user_id=? AND skill_id=?",
                tenant,
                userId,
                skillId);
        String code = str(skill, "code");
        String name = str(skill, "name");
        if (held.isEmpty()) return new Check(Check.MISSING, code, name, requiredLevel, null, null);
        int level = ((Number) held.getFirst().get("level")).intValue();
        Object until = held.getFirst().get("certified_until");
        String untilText = until == null ? null : until.toString();
        if (until != null && LocalDate.parse(untilText).isBefore(day))
            return new Check(Check.EXPIRED, code, name, requiredLevel, level, untilText);
        if (level < requiredLevel) return new Check(Check.LEVEL_TOO_LOW, code, name, requiredLevel, level, untilText);
        return new Check(Check.QUALIFIED, code, name, requiredLevel, level, untilText);
    }

    /** {@link #check} for today in the business timezone. */
    public Check check(String tenant, String userId, String skillId, int requiredLevel) {
        return check(tenant, userId, skillId, requiredLevel, LocalDate.now(CalendarService.ZONE));
    }
}
