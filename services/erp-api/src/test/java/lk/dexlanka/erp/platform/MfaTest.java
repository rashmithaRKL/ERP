package lk.dexlanka.erp.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import lk.dexlanka.erp.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/**
 * V17 two-step sign-in: set-up, the code step of a password sign-in, replay and attempt limits, recovery codes, the
 * administrator requirement (and the demo exemption), turning it off, and an administrator's reset.
 */
class MfaTest extends IntegrationTestBase {
    static final String PASSWORD = "integration-password-123";

    @Autowired
    MfaService mfa;

    @Autowired
    MfaPolicy policy;

    @Autowired
    MfaAttemptLimit attempts;

    final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    String t;

    @BeforeEach
    void setUp() {
        mfa.clock(now::get);
        attempts.clock(now::get);
        t = createTenant("Two-step sign-in").id();
    }

    @AfterEach
    void restore() {
        mfa.clock(null);
        attempts.clock(null);
        policy.requiredForAdmins(false);
    }

    MockHttpSession signIn(Actor user) throws Exception {
        return (MockHttpSession) loginAttempt(user)
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }

    ResultActions loginAttempt(Actor user) throws Exception {
        var random = ThreadLocalRandom.current();
        String address = "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
        return mvc.perform(post("/api/auth/login")
                .with(csrf())
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
                .param("email", user.email())
                .param("password", PASSWORD));
    }

    ResultActions read(MockHttpSession session, String path) throws Exception {
        return mvc.perform(get(path).session(session));
    }

    String code(String secret) {
        return Totp.code(secret, now.get() / 1000 / Totp.STEP_SECONDS);
    }

    void nextStep() {
        now.addAndGet(Totp.STEP_SECONDS * 1000);
    }

    /** Sets up two-step sign-in for {@code user} and returns the secret and the recovery codes. */
    @SuppressWarnings("unchecked")
    Map.Entry<String, List<String>> enroll(MockHttpSession session) throws Exception {
        String secret =
                str(json(command(session, "/api/auth/mfa/setup", Map.of()).andExpect(status().isOk())), "secret");
        var enabled =
                json(command(session, "/api/auth/mfa/enable", Map.of("currentPassword", PASSWORD, "code", code(secret)))
                        .andExpect(status().isOk()));
        nextStep();
        return Map.entry(secret, (List<String>) enabled.get("recoveryCodes"));
    }

    @Test
    void setUpThenSignInAsksForTheCodeBeforeAnythingElse() throws Exception {
        var planner = createUser(t, "PLANNER", PASSWORD);
        var first = signIn(planner);
        var enrolled = enroll(first);
        String secret = enrolled.getKey();
        assertThat(enrolled.getValue()).hasSize(10).allMatch(c -> c.matches("[a-z2-9]{5}-[a-z2-9]{5}"));
        String stored = str(row("SELECT mfa_secret FROM users WHERE id=?", planner.id()), "mfa_secret");
        assertThat(stored).startsWith("v1:").doesNotContain(secret);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='MFA_ENABLED'", t))
                .isEqualTo(1);
        // Turning it on signs the user's sessions out; this one was re-saved under the new version.
        read(first, "/api/auth/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(true));

        loginAttempt(planner).andExpect(jsonPath("$.mfaRequired").value(true));
        var session = signIn(planner);
        read(session, "/api/auth/me").andExpect(jsonPath("$.mfaPending").value(true));
        read(session, "/api/state")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        command(session, "/api/auth/mfa/verify", Map.of("code", "000000")).andExpect(status().isConflict());
        command(session, "/api/auth/mfa/verify", Map.of("code", code(secret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("TOTP"));
        read(session, "/api/state").andExpect(status().isOk());
        read(session, "/api/auth/me").andExpect(jsonPath("$.mfaPending").value(false));

        // The same 30-second code cannot be used twice.
        var again = signIn(planner);
        command(again, "/api/auth/mfa/verify", Map.of("code", code(secret))).andExpect(status().isConflict());
        nextStep();
        command(again, "/api/auth/mfa/verify", Map.of("code", code(secret))).andExpect(status().isOk());
    }

    @Test
    void aRecoveryCodeWorksOnceAndCanBeRegenerated() throws Exception {
        var user = createUser(t, "WAREHOUSE", PASSWORD);
        var enrolled = enroll(signIn(user));
        String recovery = enrolled.getValue().getFirst();

        var session = signIn(user);
        command(session, "/api/auth/mfa/verify", Map.of("code", recovery.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("RECOVERY_CODE"))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(9));
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='MFA_RECOVERY_CODE_USED'", t))
                .isEqualTo(1);
        command(signIn(user), "/api/auth/mfa/verify", Map.of("code", recovery)).andExpect(status().isConflict());

        var fresh = json(command(
                        session,
                        "/api/auth/mfa/recovery-codes",
                        Map.of("currentPassword", PASSWORD, "code", code(enrolled.getKey())))
                .andExpect(status().isOk()));
        assertThat((List<?>) fresh.get("recoveryCodes")).hasSize(10);
        command(
                        signIn(user),
                        "/api/auth/mfa/verify",
                        Map.of("code", enrolled.getValue().get(1)))
                .andExpect(status().isConflict());
    }

    @Test
    void wrongCodesAreLimitedAndAWaitingSignInExpires() throws Exception {
        var user = createUser(t, "QUALITY", PASSWORD);
        String secret = enroll(signIn(user)).getKey();
        var session = signIn(user);
        for (int i = 0; i < MfaAttemptLimit.MAX_FAILURES; i++)
            command(session, "/api/auth/mfa/verify", Map.of("code", "111111")).andExpect(status().isConflict());
        command(session, "/api/auth/mfa/verify", Map.of("code", code(secret))).andExpect(status().isTooManyRequests());
        now.addAndGet(MfaAttemptLimit.WINDOW_MINUTES * 60_000 + 1);

        var late = signIn(user);
        late.setAttribute(MfaService.PENDING, System.currentTimeMillis() - MfaService.PENDING_MILLIS - 1);
        read(late, "/api/state").andExpect(status().isUnauthorized());
    }

    @Test
    void administratorsMustSetItUpAndKeepItOnExceptInTheDemo() throws Exception {
        policy.requiredForAdmins(true);
        var admin = createUser(t, "ADMIN", PASSWORD);
        var session = signIn(admin);
        read(session, "/api/state")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_SETUP_REQUIRED"));
        read(session, "/api/auth/me").andExpect(jsonPath("$.mfaSetupRequired").value(true));
        String secret = enroll(session).getKey();
        read(session, "/api/state").andExpect(status().isOk());
        command(session, "/api/auth/mfa/disable", Map.of("currentPassword", PASSWORD, "code", code(secret)))
                .andExpect(status().isConflict());

        // A planner promoted to administrator mid-session is held at the set-up screen on the next request.
        var planner = createUser(t, "PLANNER", PASSWORD);
        var plannerSession = signIn(planner);
        read(plannerSession, "/api/state").andExpect(status().isOk());
        update("UPDATE users SET role='ADMIN' WHERE id=?", planner.id());
        read(plannerSession, "/api/state").andExpect(jsonPath("$.code").value("MFA_SETUP_REQUIRED"));

        // Persona switching (no password sign-in) and demo workspaces are not held.
        read(plannerSession, "/api/auth/me").andExpect(jsonPath("$.mfaRequired").value(true));
        update("UPDATE tenants SET demo=true WHERE id=?", t);
        read(plannerSession, "/api/state").andExpect(status().isOk());
        read(plannerSession, "/api/auth/me").andExpect(jsonPath("$.mfaRequired").value(false));
        command(session, "/api/auth/mfa/disable", Map.of("currentPassword", PASSWORD, "code", code(secret)))
                .andExpect(status().isOk());
    }

    @Test
    void aUserCanTurnItOffWithPasswordAndCode() throws Exception {
        var user = createUser(t, "OPERATOR", PASSWORD);
        var session = signIn(user);
        String secret = enroll(session).getKey();
        command(
                        session,
                        "/api/auth/mfa/disable",
                        Map.of("currentPassword", "not-the-password-xx", "code", code(secret)))
                .andExpect(status().isConflict());
        command(session, "/api/auth/mfa/disable", Map.of("currentPassword", PASSWORD, "code", code(secret)))
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM mfa_recovery_codes WHERE user_id=?", user.id()))
                .isZero();
        loginAttempt(user).andExpect(jsonPath("$.mfaRequired").value(false));
    }

    @Test
    void anAdministratorResetsAnotherUsersTwoStepSignIn() throws Exception {
        var admin = createUser(t, "ADMIN", PASSWORD);
        var user = createUser(t, "PLANNER", PASSWORD);
        var userSession = signIn(user);
        enroll(userSession);
        read(userSession, "/api/state").andExpect(status().isOk());

        command(admin, "/api/admin/users/" + admin.id() + "/reset-mfa", null).andExpect(status().isConflict());
        command(userSession, "/api/admin/users/" + user.id() + "/reset-mfa", null)
                .andExpect(status().isForbidden());
        var other = createUser(createTenant("Elsewhere").id(), "ADMIN");
        command(other, "/api/admin/users/" + user.id() + "/reset-mfa", null).andExpect(status().isNotFound());

        command(admin, "/api/admin/users/" + user.id() + "/reset-mfa", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa_enabled").value(false));
        read(userSession, "/api/state").andExpect(status().isUnauthorized());
        assertThat(count("SELECT COUNT(*) FROM mfa_recovery_codes WHERE user_id=?", user.id()))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='USER_MFA_RESET'", t))
                .isEqualTo(1);
        loginAttempt(user).andExpect(jsonPath("$.mfaRequired").value(false));
    }

    @Test
    void enablingNeedsTheSetUpSecretAndTheRightPassword() throws Exception {
        var user = createUser(t, "SALES", PASSWORD);
        var session = signIn(user);
        command(session, "/api/auth/mfa/enable", Map.of("currentPassword", PASSWORD, "code", "123456"))
                .andExpect(status().isConflict());
        String secret = str(json(command(session, "/api/auth/mfa/setup", Map.of())), "secret");
        command(session, "/api/auth/mfa/enable", Map.of("currentPassword", "wrong-password-0000", "code", code(secret)))
                .andExpect(status().isConflict());
        command(session, "/api/auth/mfa/enable", Map.of("currentPassword", PASSWORD, "code", code(secret)))
                .andExpect(status().isOk());
        command(session, "/api/auth/mfa/setup", Map.of()).andExpect(status().isConflict());
    }
}
