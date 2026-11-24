package lk.dexlanka.erp.platform;

import static lk.dexlanka.erp.platform.Store.require;

import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The signed-in user's own password: verify the current one, store the new one, revoke the user's other sessions. */
@Service
class PasswordService {
    private final Store s;
    private final PasswordEncoder passwords;

    PasswordService(Store s, PasswordEncoder passwords) {
        this.s = s;
        this.passwords = passwords;
    }

    /** Idempotency payload: the actor plus salted password fingerprints, never the passwords themselves. */
    private record Fingerprint(String user, String current, String next) {}

    /**
     * Any role. Increments {@code session_version} (every other session of this user ends on its next request) and
     * clears {@code must_change_password}; the caller re-saves the current session with the new version.
     */
    @Transactional
    public Map<String, Object> change(Actor a, AccountCommands.ChangePassword c, String key) {
        String salt = a.id() + ":" + key;
        var body = new Fingerprint(
                a.id(), Passwords.fingerprint(salt, c.currentPassword()), Passwords.fingerprint(salt, c.newPassword()));
        return s.once(a, key, "change-password", body, () -> {
            var u = s.one("SELECT * FROM users WHERE tenant_id=? AND id=? FOR UPDATE", a.tenant(), a.id());
            require(Boolean.TRUE.equals(u.get("active")), "This account is inactive");
            require(
                    passwords.matches(
                            c.currentPassword(), u.get("password_hash").toString()),
                    "Your current password is incorrect");
            Passwords.requireAcceptable(c.newPassword(), u.get("email").toString());
            require(
                    !passwords.matches(c.newPassword(), u.get("password_hash").toString()),
                    "Choose a password that is different from your current one");
            boolean forced = Boolean.TRUE.equals(u.get("must_change_password"));
            s.db().update(
                            "UPDATE users SET password_hash=?,must_change_password=false,password_changed_at=now(),session_version=session_version+1,updated_at=now() WHERE tenant_id=? AND id=?",
                            passwords.encode(c.newPassword()),
                            a.tenant(),
                            a.id());
            s.audit(
                    a,
                    "PASSWORD_CHANGED",
                    u.get("email").toString(),
                    forced
                            ? "Temporary password replaced by the account holder; other sessions signed out"
                            : "Changed by the account holder; other sessions signed out");
            return Map.of("status", "CHANGED");
        });
    }
}
