package lk.dexlanka.erp.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.RoutingTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V10 skill catalogue, user skills and the skill rule used by the shop floor. */
class SkillTest extends RoutingTestBase {
    @Autowired
    SkillRules rules;

    @Test
    void catalogueAndUserSkills() throws Exception {
        var created = json(command(planner, "/api/skills", new SkillCommands.Skill("blow", "Blow moulding", "SBM"))
                .andExpect(status().isOk()));
        String skill = str(created, "id");
        assertThat(created).containsEntry("code", "BLOW");
        command(planner, "/api/skills", new SkillCommands.Skill("BLOW", "Again", "")).andExpect(status().isConflict());
        command(planner, "/api/skills", new SkillCommands.Skill("bad code", "x", "")).andExpect(status().isBadRequest());

        String until = LocalDate.now().plusMonths(6).toString();
        command(planner, "/api/skills/assignments", new SkillCommands.UserSkill(operator.id(), skill, 2, until, "Trained"))
                .andExpect(status().isOk());
        command(planner, "/api/skills/assignments", new SkillCommands.UserSkill(operator.id(), skill, 3, null, ""))
                .andExpect(status().isOk());
        assertThat(row("SELECT level,certified_until FROM user_skills WHERE tenant_id=? AND user_id=?", t, operator.id()))
                .containsEntry("level", 3)
                .containsEntry("certified_until", null);

        var matrix = json(query(planner, "/api/skills/users").andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var users = (List<Map<String, Object>>) matrix.get("items");
        var op = users.stream().filter(u -> operator.id().equals(u.get("id"))).findFirst().orElseThrow();
        assertThat(op.get("skills")).asList().hasSize(1);
        query(operator, "/api/skills/users").andExpect(status().isForbidden());
        var mine = json(query(operator, "/api/skills/mine").andExpect(status().isOk()));
        assertThat(mine.get("skills")).asList().hasSize(1);
        var catalogue = json(query(viewer, "/api/skills").andExpect(status().isOk()));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) catalogue.get("skills");
        assertThat(rows.getFirst()).containsEntry("code", "BLOW");
        assertThat(((Number) rows.getFirst().get("holders")).intValue()).isEqualTo(1);

        command(planner, "/api/skills/assignments/remove", new SkillCommands.RemoveUserSkill(operator.id(), skill))
                .andExpect(status().isOk());
        command(planner, "/api/skills/assignments/remove", new SkillCommands.RemoveUserSkill(operator.id(), skill))
                .andExpect(status().isConflict());

        command(planner, "/api/skills/" + skill, new SkillCommands.SkillUpdate("Blow moulding", "", false))
                .andExpect(status().isOk());
        command(planner, "/api/skills/assignments", new SkillCommands.UserSkill(operator.id(), skill, 1, null, ""))
                .andExpect(status().isConflict()); // inactive skill

        assertThat(rows("SELECT action FROM audit_events WHERE tenant_id=? AND reference='BLOW' ORDER BY created_at", t))
                .extracting(e -> e.get("action"))
                .containsExactly(
                        "SKILL_CREATED", "USER_SKILL_SET", "USER_SKILL_SET", "USER_SKILL_REMOVED", "SKILL_UPDATED");
    }

    @Test
    void theSkillRule() {
        String skill = skill("INJ");
        LocalDate today = LocalDate.of(2026, 6, 1);
        assertThat(rules.check(t, operator.id(), null, 1, today).status()).isEqualTo("NOT_REQUIRED");
        assertThat(rules.check(t, operator.id(), skill, 1, today).status()).isEqualTo("MISSING");
        grant(operator, skill, 2, today);
        assertThat(rules.check(t, operator.id(), skill, 2, today).status()).isEqualTo("QUALIFIED"); // last valid day
        assertThat(rules.check(t, operator.id(), skill, 3, today).status()).isEqualTo("LEVEL_TOO_LOW");
        var expired = rules.check(t, operator.id(), skill, 1, today.plusDays(1));
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.message("Kasun")).contains("expired on " + today);
        grant(operator, skill, 2, null);
        assertThat(rules.check(t, operator.id(), skill, 2, today.plusYears(5)).ok()).isTrue();
    }

    @Test
    void permissionsIdempotencyAndTenantIsolation() throws Exception {
        command(operator, "/api/skills", new SkillCommands.Skill("QC", "QC sampling", "")).andExpect(status().isForbidden());
        String key = id();
        var body = new SkillCommands.Skill("QC", "QC sampling", "");
        var first = json(command(planner, "/api/skills", body, key).andExpect(status().isOk()));
        var again = json(command(planner, "/api/skills", body, key).andExpect(status().isOk()));
        assertThat(again.get("id")).isEqualTo(first.get("id"));
        command(planner, "/api/skills", new SkillCommands.Skill("QC2", "Other", ""), key).andExpect(status().isConflict());

        var other = createTenant();
        var outsider = createUser(other.id(), "PLANNER");
        var stranger = createUser(other.id(), "OPERATOR");
        // A skill of another tenant, and a user of another tenant, are not found.
        command(outsider, "/api/skills/assignments", new SkillCommands.UserSkill(stranger.id(), str(first, "id"), 1, null, ""))
                .andExpect(status().isNotFound());
        command(planner, "/api/skills/assignments", new SkillCommands.UserSkill(stranger.id(), str(first, "id"), 1, null, ""))
                .andExpect(status().isNotFound());
        assertThat(json(query(outsider, "/api/skills")).get("skills")).asList().isEmpty();
    }
}
