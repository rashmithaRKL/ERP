package lk.dexlanka.erp.routing;

import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;

import java.util.Locale;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Skill catalogue and user skills (V10), maintained by PLANNER and ADMIN. */
@Service
public class SkillService {
    private final Store s;

    SkillService(Store s) {
        this.s = s;
    }

    @Transactional
    public Map<String, Object> create(Actor a, SkillCommands.Skill c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "skill", c, () -> {
            String code = c.code().strip().toUpperCase(Locale.ROOT);
            require(
                    s.list("SELECT 1 FROM skills WHERE tenant_id=? AND code=?", a.tenant(), code)
                            .isEmpty(),
                    "Skill " + code + " already exists");
            String id = id();
            s.db().update(
                            "INSERT INTO skills(id,tenant_id,code,name,description,created_by) VALUES(?,?,?,?,?,?)",
                            id,
                            a.tenant(),
                            code,
                            c.name().strip(),
                            c.description() == null ? "" : c.description().strip(),
                            a.id());
            s.audit(a, "SKILL_CREATED", code, c.name().strip());
            return Map.of("id", id, "code", code);
        });
    }

    @Transactional
    public Map<String, Object> update(Actor a, String id, SkillCommands.SkillUpdate c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "edit-skill:" + id, c, () -> {
            var skill = s.one("SELECT * FROM skills WHERE tenant_id=? AND id=?", a.tenant(), id);
            s.db().update(
                            "UPDATE skills SET name=?,description=?,active=?,updated_at=now() WHERE tenant_id=? AND id=?",
                            c.name().strip(),
                            c.description() == null ? "" : c.description().strip(),
                            c.active(),
                            a.tenant(),
                            id);
            s.audit(
                    a,
                    "SKILL_UPDATED",
                    str(skill, "code"),
                    c.name().strip() + (Boolean.TRUE.equals(c.active()) ? "" : " (inactive)"));
            return Map.of("id", id, "code", str(skill, "code"));
        });
    }

    /** Creates or replaces a user's level and certification for one skill. */
    @Transactional
    public Map<String, Object> assign(Actor a, SkillCommands.UserSkill c, String key) {
        a.allow("PLANNER");
        if (c.certifiedUntil() != null && !c.certifiedUntil().isBlank()) s.validDate(c.certifiedUntil());
        return s.once(a, key, "user-skill:" + c.userId() + ":" + c.skillId(), c, () -> {
            var user = s.one("SELECT name,active FROM users WHERE tenant_id=? AND id=?", a.tenant(), c.userId());
            require(Boolean.TRUE.equals(user.get("active")), "Skills can be recorded only for active users");
            var skill = s.one("SELECT code,active FROM skills WHERE tenant_id=? AND id=?", a.tenant(), c.skillId());
            require(Boolean.TRUE.equals(skill.get("active")), "Skill " + str(skill, "code") + " is inactive");
            String until = c.certifiedUntil() == null || c.certifiedUntil().isBlank() ? null : c.certifiedUntil();
            s.db().update(
                            "INSERT INTO user_skills(tenant_id,user_id,skill_id,level,certified_until,notes,updated_by) VALUES(?,?,?,?,?::date,?,?) ON CONFLICT (tenant_id,user_id,skill_id) DO UPDATE SET level=EXCLUDED.level,certified_until=EXCLUDED.certified_until,notes=EXCLUDED.notes,updated_by=EXCLUDED.updated_by,updated_at=now()",
                            a.tenant(),
                            c.userId(),
                            c.skillId(),
                            c.level(),
                            until,
                            c.notes() == null ? "" : c.notes().strip(),
                            a.id());
            s.audit(
                    a,
                    "USER_SKILL_SET",
                    str(skill, "code"),
                    str(user, "name") + ": level " + c.level()
                            + (until == null ? ", no expiry" : ", certified until " + until));
            return Map.of("userId", c.userId(), "skillId", c.skillId(), "level", c.level());
        });
    }

    @Transactional
    public Map<String, Object> remove(Actor a, SkillCommands.RemoveUserSkill c, String key) {
        a.allow("PLANNER");
        return s.once(a, key, "remove-user-skill:" + c.userId() + ":" + c.skillId(), c, () -> {
            var user = s.one("SELECT name FROM users WHERE tenant_id=? AND id=?", a.tenant(), c.userId());
            var skill = s.one("SELECT code FROM skills WHERE tenant_id=? AND id=?", a.tenant(), c.skillId());
            int removed = s.db().update(
                            "DELETE FROM user_skills WHERE tenant_id=? AND user_id=? AND skill_id=?",
                            a.tenant(),
                            c.userId(),
                            c.skillId());
            require(removed == 1, str(user, "name") + " does not hold skill " + str(skill, "code"));
            s.audit(a, "USER_SKILL_REMOVED", str(skill, "code"), str(user, "name"));
            return Map.of("userId", c.userId(), "skillId", c.skillId(), "removed", true);
        });
    }
}
