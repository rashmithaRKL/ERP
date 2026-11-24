package lk.dexlanka.erp.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;
import lk.dexlanka.erp.IntegrationTestBase;
import lk.dexlanka.erp.platform.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared fixture for the administration tests: a fresh tenant with an ADMIN, PLANNER and WAREHOUSE user that can sign
 * in with {@link #PASSWORD}. Sign-ins use a random client address each, so the per-address login rate limit of the
 * shared test context is never reached.
 */
abstract class AdminTestSupport extends IntegrationTestBase {
    static final String PASSWORD = "integration-password-123";

    TestTenant tenant;
    String t;
    Actor admin, planner, warehouse;

    @BeforeEach
    void adminFixture() {
        tenant = createTenant();
        t = tenant.id();
        admin = createUser(t, "ADMIN", PASSWORD);
        planner = createUser(t, "PLANNER", PASSWORD);
        warehouse = createUser(t, "WAREHOUSE", PASSWORD);
    }

    static RequestPostProcessor someClient() {
        var random = ThreadLocalRandom.current();
        String address = "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    ResultActions loginAttempt(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(csrf())
                .with(someClient())
                .param("email", email)
                .param("password", password));
    }

    MockHttpSession signIn(String email, String password) throws Exception {
        return (MockHttpSession) loginAttempt(email, password)
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }

    ResultActions read(MockHttpSession session, String path) throws Exception {
        return mvc.perform(get(path).session(session));
    }

    ResultActions write(MockHttpSession session, String path, Object body) throws Exception {
        return mvc.perform(commandRequest(path, body).session(session));
    }

    /** A user with a chosen name (the default fixture names users after their role). */
    Actor namedUser(String name, String role) {
        var u = createUser(t, role, PASSWORD);
        update("UPDATE users SET name=? WHERE id=?", name, u.id());
        return new Actor(u.id(), u.tenant(), u.email(), name, role);
    }
}
