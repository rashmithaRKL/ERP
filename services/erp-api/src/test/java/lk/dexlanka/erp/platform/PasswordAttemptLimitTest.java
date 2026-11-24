package lk.dexlanka.erp.platform;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * V9: {@code POST /api/auth/password} refuses (429) further attempts after {@value PasswordAttemptLimit#MAX_FAILURES}
 * wrong current passwords per account within {@value PasswordAttemptLimit#WINDOW_MINUTES} minutes; other accounts are
 * unaffected, the window slides, and a successful change clears the failures.
 */
class PasswordAttemptLimitTest extends IntegrationTestBase {
    static final String PASSWORD = "integration-password-123";
    static final String NEXT = "a-brand-new-passphrase-456";

    @Autowired
    PasswordAttemptLimit limit;

    final AtomicLong now = new AtomicLong(1_000_000_000L);
    String t;

    @BeforeEach
    void clock() {
        limit.clock(now::get);
        t = createTenant("Password limits").id();
    }

    @AfterEach
    void systemClock() {
        limit.clock(null);
    }

    /** Signs in from a random client address, so the per-address login limit of the shared context is never hit. */
    MockHttpSession signIn(Actor user) throws Exception {
        var random = ThreadLocalRandom.current();
        String address = "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
        return (MockHttpSession) mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return request;
                        })
                        .param("email", user.email())
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }

    ResultActions change(MockHttpSession session, String current, String next) throws Exception {
        return command(session, "/api/auth/password", new AccountCommands.ChangePassword(current, next));
    }

    @Test
    void wrongCurrentPasswordsAreLimitedPerAccountInASlidingWindow() throws Exception {
        var alice = createUser(t, "PLANNER", PASSWORD);
        var bob = createUser(t, "PLANNER", PASSWORD);
        var session = signIn(alice);
        for (int i = 0; i < PasswordAttemptLimit.MAX_FAILURES; i++) {
            now.addAndGet(1_000);
            change(session, "wrong-password-" + i + "-xxxxxxx", NEXT)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("Your current password is incorrect"));
        }
        change(session, "wrong-password-again-xxxxxxx", NEXT)
                .andExpect(status().isTooManyRequests())
                .andExpect(
                        jsonPath("$.detail").value("Too many incorrect current passwords. Try again in 15 minutes."));
        // Even the right password waits for the window; the stored hash is unchanged.
        change(session, PASSWORD, NEXT).andExpect(status().isTooManyRequests());
        String hash = row("SELECT password_hash FROM users WHERE id=?", alice.id())
                .get("password_hash")
                .toString();
        org.assertj.core.api.Assertions.assertThat(passwords.matches(PASSWORD, hash))
                .isTrue();

        // Another account of the same tenant is not affected.
        var other = signIn(bob);
        change(other, "not-bobs-password-xxxxxxx", NEXT).andExpect(status().isConflict());
        change(other, PASSWORD, NEXT).andExpect(status().isOk());

        // The window slides: once the oldest failure is 15 minutes old, one more attempt is allowed.
        now.addAndGet(PasswordAttemptLimit.WINDOW_MINUTES * 60_000 - 4_000);
        change(session, "still-wrong-password-xxxxx", NEXT).andExpect(status().isConflict());
        change(session, PASSWORD, NEXT).andExpect(status().isTooManyRequests());
        now.addAndGet(PasswordAttemptLimit.WINDOW_MINUTES * 60_000);
        change(session, PASSWORD, NEXT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHANGED"));
    }

    @Test
    void aSuccessfulChangeClearsEarlierFailures() throws Exception {
        var carol = createUser(t, "WAREHOUSE", PASSWORD);
        var session = signIn(carol);
        for (int i = 0; i < PasswordAttemptLimit.MAX_FAILURES - 1; i++)
            change(session, "wrong-password-" + i + "-xxxxxxx", NEXT).andExpect(status().isConflict());
        change(session, PASSWORD, NEXT).andExpect(status().isOk());
        // A fresh count: four more failures are still 409s, the fifth makes the next attempt wait.
        for (int i = 0; i < PasswordAttemptLimit.MAX_FAILURES; i++)
            change(session, "wrong-password-" + i + "-yyyyyyy", PASSWORD).andExpect(status().isConflict());
        change(session, NEXT, PASSWORD).andExpect(status().isTooManyRequests());
    }
}
