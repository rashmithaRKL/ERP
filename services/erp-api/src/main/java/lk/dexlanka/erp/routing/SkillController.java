package lk.dexlanka.erp.routing;

import jakarta.validation.Valid;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Skill catalogue and user skills (V10). */
@RestController
@RequestMapping("/api")
class SkillController {
    private final SkillService skills;
    private final SkillQuery query;

    SkillController(SkillService skills, SkillQuery query) {
        this.skills = skills;
        this.query = query;
    }

    @GetMapping("/skills")
    Map<String, Object> catalogue(Authentication a) {
        return query.catalogue(Actor.of(a));
    }

    @GetMapping("/skills/users")
    Map<String, Object> users(
            Authentication a,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return query.users(Actor.of(a), q, limit, before);
    }

    @GetMapping("/skills/mine")
    Map<String, Object> mine(Authentication a) {
        return query.mine(Actor.of(a));
    }

    @PostMapping("/skills")
    Map<String, Object> create(
            Authentication a, @Valid @RequestBody SkillCommands.Skill c, @RequestHeader("Idempotency-Key") String k) {
        return skills.create(Actor.of(a), c, k);
    }

    @PostMapping("/skills/assignments")
    Map<String, Object> assign(
            Authentication a,
            @Valid @RequestBody SkillCommands.UserSkill c,
            @RequestHeader("Idempotency-Key") String k) {
        return skills.assign(Actor.of(a), c, k);
    }

    @PostMapping("/skills/assignments/remove")
    Map<String, Object> remove(
            Authentication a,
            @Valid @RequestBody SkillCommands.RemoveUserSkill c,
            @RequestHeader("Idempotency-Key") String k) {
        return skills.remove(Actor.of(a), c, k);
    }

    @PostMapping("/skills/{id}")
    Map<String, Object> update(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody SkillCommands.SkillUpdate c,
            @RequestHeader("Idempotency-Key") String k) {
        return skills.update(Actor.of(a), id, c, k);
    }
}
