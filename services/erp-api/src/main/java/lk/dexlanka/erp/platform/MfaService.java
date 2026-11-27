package lk.dexlanka.erp.platform;

import static lk.dexlanka.erp.platform.Store.require;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two-step sign-in (V17): authenticator set-up, the code step of a password sign-in, one-time recovery codes and turning
 * it off. Session state lives in {@link SessionGuard}'s session attributes ({@link #PENDING}, {@link #PASSWORD_SESSION},
 * {@link #ENROLLING}); the database holds the encrypted secret ({@link MfaSecrets}), the last accepted time step and the
 * hashed recovery codes. An administrator's reset is {@code admin.UserAdminService#resetMfa}.
 */
@Service
public class MfaService {
    /** Session attribute: epoch millis at which the password was accepted; the code is still due. */
    static final String PENDING = "dex.mfa.pending";
    /** Session attribute: this session began with a password sign-in, so the administrator set-up rule applies. */
    static final String PASSWORD_SESSION = "dex.mfa.password-session";
    /** Session attribute: the Base32 secret of a set-up in progress (not yet confirmed with a code). */
    static final String ENROLLING = "dex.mfa.enrolling";

    static final long PENDING_MILLIS = 5 * 60_000;
    static final int RECOVERY_CODES = 10;
    static final String ISSUER = "DexLanka Manufacturing Pro";

    /** Lower case without 0/o, 1/l/i: recovery codes are written down and typed. */
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Store s;
    private final MfaSecrets secrets;
    private final MfaPolicy policy;
    private final PasswordEncoder passwords;
    private final PasswordAttemptLimit passwordAttempts;
    private final MfaAttemptLimit attempts;
    private LongSupplier clock = System::currentTimeMillis;

    MfaService(
            Store s,
            MfaSecrets secrets,
            MfaPolicy policy,
            PasswordEncoder passwords,
            PasswordAttemptLimit passwordAttempts,
            MfaAttemptLimit attempts) {
        this.s = s;
        this.secrets = secrets;
        this.policy = policy;
        this.passwords = passwords;
        this.passwordAttempts = passwordAttempts;
        this.attempts = attempts;
    }

    private record Fingerprint(String user, String password, String code) {}

    /** The signed-in user's two-step sign-in state for {@code /api/auth/me}. */
    public Map<String, Object> status(Actor a) {
        var u = s.one(
                "SELECT u.mfa_enabled_at,t.demo FROM users u JOIN tenants t ON t.id=u.tenant_id WHERE u.tenant_id=? AND u.id=?",
                a.tenant(),
                a.id());
        var result = new LinkedHashMap<String, Object>();
        result.put("mfaEnabled", u.get("mfa_enabled_at") != null);
        result.put("mfaEnabledAt", u.get("mfa_enabled_at"));
        result.put("mfaRequired", policy.required(a.role(), Boolean.TRUE.equals(u.get("demo"))));
        result.put(
                "recoveryCodesLeft",
                u.get("mfa_enabled_at") == null
                        ? 0
                        : s.db().queryForObject(
                                        "SELECT COUNT(*) FROM mfa_recovery_codes WHERE tenant_id=? AND user_id=? AND used_at IS NULL",
                                        Long.class,
                                        a.tenant(),
                                        a.id()));
        return result;
    }

    /** Whether {@code userId} has two-step sign-in on (the password sign-in then asks for a code). */
    public boolean enabled(String userId) {
        return Boolean.TRUE.equals(s.db().queryForObject(
                        "SELECT mfa_enabled_at IS NOT NULL FROM users WHERE id=?", Boolean.class, userId));
    }

    /**
     * Starts a set-up: a new secret for the authenticator app, kept in the session until {@link #enable} confirms it with
     * a code. Nothing is stored in the database yet.
     */
    public Map<String, Object> setup(Actor a) {
        require(!enabled(a.id()), "Two-step sign-in is already on; turn it off first to move it to another phone");
        String secret = Totp.newSecret();
        var result = new LinkedHashMap<String, Object>();
        result.put("secret", secret);
        result.put("uri", Totp.uri(ISSUER, a.email(), secret));
        result.put("issuer", ISSUER);
        result.put("account", a.email());
        return result;
    }

    /**
     * Confirms the set-up with the current password and a code from the app: stores the encrypted secret, issues
     * {@value #RECOVERY_CODES} recovery codes (shown once, never stored in clear text, so an idempotent replay answers
     * {@code recoveryCodes: null}) and signs the user's other sessions out.
     */
    @Transactional
    public Map<String, Object> enable(Actor a, AccountCommands.MfaConfirm c, String key, String enrolling) {
        require(enrolling != null, "Start the set-up again: scan a new code with your authenticator app");
        var issued = new ArrayList<String>();
        var stored = s.once(a, key, "mfa-enable", fingerprint(a, key, c), () -> {
            var u = lockUser(a);
            require(u.get("mfa_enabled_at") == null, "Two-step sign-in is already on");
            checkPassword(a, u, c.currentPassword());
            attempts.check(a.id());
            var step = Totp.match(enrolling, c.code().strip(), clock.getAsLong() / 1000);
            if (step.isEmpty()) {
                attempts.failed(a.id());
                require(false, "That code is not correct. Check that your phone's clock is set automatically");
            }
            attempts.succeeded(a.id());
            s.db().update(
                            "UPDATE users SET mfa_secret=?,mfa_enabled_at=now(),mfa_last_step=?,session_version=session_version+1,updated_at=now() WHERE tenant_id=? AND id=?",
                            secrets.encrypt(a.id(), enrolling),
                            step.getAsLong(),
                            a.tenant(),
                            a.id());
            issued.addAll(newRecoveryCodes(a));
            s.audit(
                    a,
                    "MFA_ENABLED",
                    a.email(),
                    "Two-step sign-in turned on with an authenticator app; " + RECOVERY_CODES
                            + " recovery codes issued; other sessions signed out");
            return Map.of("status", "ENABLED");
        });
        return withCodes(stored, issued);
    }

    /**
     * The second step of a password sign-in: an authenticator code (each 30-second code is accepted once) or an unused
     * recovery code, which is then spent.
     */
    @Transactional
    public Map<String, Object> verify(Actor a, String code) {
        var u = lockUser(a);
        require(u.get("mfa_enabled_at") != null, "Two-step sign-in is not on for this account");
        String method = checkCode(a, u, code);
        var result = new LinkedHashMap<String, Object>();
        result.put("method", method);
        result.put("recoveryCodesLeft", codesLeft(a));
        return result;
    }

    /** New recovery codes (the unused ones stop working); needs the current password and a code. */
    @Transactional
    public Map<String, Object> regenerate(Actor a, AccountCommands.MfaConfirm c, String key) {
        var issued = new ArrayList<String>();
        var stored = s.once(a, key, "mfa-recovery-codes", fingerprint(a, key, c), () -> {
            var u = lockUser(a);
            require(u.get("mfa_enabled_at") != null, "Turn on two-step sign-in first");
            checkPassword(a, u, c.currentPassword());
            checkCode(a, u, c.code());
            s.db().update("DELETE FROM mfa_recovery_codes WHERE tenant_id=? AND user_id=?", a.tenant(), a.id());
            issued.addAll(newRecoveryCodes(a));
            s.audit(
                    a,
                    "MFA_RECOVERY_CODES_REGENERATED",
                    a.email(),
                    RECOVERY_CODES + " new recovery codes issued; the previous codes no longer work");
            return Map.of("status", "REGENERATED");
        });
        return withCodes(stored, issued);
    }

    /** Turns two-step sign-in off (not allowed while the policy requires it); signs the other sessions out. */
    @Transactional
    public Map<String, Object> disable(Actor a, AccountCommands.MfaConfirm c, String key) {
        return s.once(a, key, "mfa-disable", fingerprint(a, key, c), () -> {
            var u = lockUser(a);
            require(u.get("mfa_enabled_at") != null, "Two-step sign-in is already off");
            require(
                    !policy.required(a.role(), Boolean.TRUE.equals(u.get("demo"))),
                    "Administrators must keep two-step sign-in on. Ask another administrator to reset it if you changed phones");
            checkPassword(a, u, c.currentPassword());
            checkCode(a, u, c.code());
            s.db().update(
                            "UPDATE users SET mfa_secret=NULL,mfa_enabled_at=NULL,mfa_last_step=NULL,session_version=session_version+1,updated_at=now() WHERE tenant_id=? AND id=?",
                            a.tenant(),
                            a.id());
            s.db().update("DELETE FROM mfa_recovery_codes WHERE tenant_id=? AND user_id=?", a.tenant(), a.id());
            s.audit(a, "MFA_DISABLED", a.email(), "Turned off by the account holder; other sessions signed out");
            return Map.of("status", "DISABLED");
        });
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> lockUser(Actor a) {
        return s.one(
                "SELECT u.*,t.demo FROM users u JOIN tenants t ON t.id=u.tenant_id WHERE u.tenant_id=? AND u.id=? FOR UPDATE OF u",
                a.tenant(),
                a.id());
    }

    private void checkPassword(Actor a, Map<String, Object> u, String password) {
        passwordAttempts.check(a.id());
        if (!passwords.matches(password, u.get("password_hash").toString())) {
            passwordAttempts.failed(a.id());
            require(false, "Your current password is incorrect");
        }
        passwordAttempts.succeeded(a.id());
    }

    /** {@code TOTP} or {@code RECOVERY_CODE}; a 409 (and a counted failure) when the code is wrong or already used. */
    private String checkCode(Actor a, Map<String, Object> u, String raw) {
        attempts.check(a.id());
        String code = raw == null ? "" : raw.strip().replace(" ", "");
        if (code.matches("\\d{" + Totp.DIGITS + "}")) {
            String secret = secrets.decrypt(a.id(), u.get("mfa_secret").toString());
            var step = Totp.match(secret, code, clock.getAsLong() / 1000);
            Object last = u.get("mfa_last_step");
            if (step.isPresent() && last != null && step.getAsLong() <= ((Number) last).longValue()) {
                attempts.failed(a.id());
                require(false, "This code was already used. Wait for the next code in your authenticator app");
            }
            if (step.isPresent()) {
                s.db().update(
                                "UPDATE users SET mfa_last_step=? WHERE tenant_id=? AND id=?",
                                step.getAsLong(),
                                a.tenant(),
                                a.id());
                attempts.succeeded(a.id());
                return "TOTP";
            }
        } else {
            String normalized = code.replace("-", "").toLowerCase(Locale.ROOT);
            if (normalized.length() == 10
                    && s.db().update(
                                            "UPDATE mfa_recovery_codes SET used_at=now() WHERE tenant_id=? AND user_id=? AND code_hash=? AND used_at IS NULL",
                                            a.tenant(),
                                            a.id(),
                                            sha256(normalized))
                            == 1) {
                attempts.succeeded(a.id());
                s.audit(a, "MFA_RECOVERY_CODE_USED", a.email(), "A recovery code was used; " + codesLeft(a) + " left");
                return "RECOVERY_CODE";
            }
        }
        attempts.failed(a.id());
        require(false, "That code is not correct");
        return null;
    }

    private long codesLeft(Actor a) {
        return s.db().queryForObject(
                        "SELECT COUNT(*) FROM mfa_recovery_codes WHERE tenant_id=? AND user_id=? AND used_at IS NULL",
                        Long.class,
                        a.tenant(),
                        a.id());
    }

    private List<String> newRecoveryCodes(Actor a) {
        var codes = new ArrayList<String>();
        while (codes.size() < RECOVERY_CODES) {
            var c = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                if (i == 5) c.append('-');
                c.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            String code = c.toString();
            if (codes.contains(code)) continue;
            codes.add(code);
            s.db().update(
                            "INSERT INTO mfa_recovery_codes(id,tenant_id,user_id,code_hash) VALUES(?,?,?,?)",
                            Store.id(),
                            a.tenant(),
                            a.id(),
                            sha256(code.replace("-", "")));
        }
        return codes;
    }

    private static Map<String, Object> withCodes(Map<String, Object> stored, List<String> issued) {
        var response = new LinkedHashMap<>(stored);
        response.put("recoveryCodes", issued.isEmpty() ? null : issued);
        return response;
    }

    private static Fingerprint fingerprint(Actor a, String key, AccountCommands.MfaConfirm c) {
        String salt = a.id() + ":" + key;
        return new Fingerprint(
                a.id(), Passwords.fingerprint(salt, c.currentPassword()), Passwords.fingerprint(salt, c.code()));
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Tests only: a controllable clock in epoch millis ({@code null} restores the system clock). */
    public void clock(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }
}
