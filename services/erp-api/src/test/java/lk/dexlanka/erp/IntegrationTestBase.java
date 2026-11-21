package lk.dexlanka.erp;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.ErpUser;
import lk.dexlanka.erp.platform.Store;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base class for API integration tests: the full Spring context on a real embedded PostgreSQL ({@code test} profile,
 * Flyway migrations applied, no demo data). All subclasses share one cached context and database, so every test must
 * create its own tenant ({@link #createTenant()}) and never assume an empty table.
 *
 * <p>Put feature tests in the feature's package under {@code src/test/java} (e.g. {@code lk.dexlanka.erp.maintenance})
 * so they can reach package-private services, and keep the class annotations here unchanged: a subclass that adds
 * {@code @SpringBootTest} properties, profiles or {@code @MockBean}s starts a second context and a second database.
 */
@SpringBootTest(
        properties = {
            "app.bootstrap-email=test@example.test",
            "app.bootstrap-password=only-for-isolated-tests-123",
            "server.servlet.session.cookie.secure=false"
        })
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {
    @Autowired
    protected Store s;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected PasswordEncoder passwords;

    /** A tenant with the plant/warehouse shape that every command expects (one plant, one RAW, one FINISHED). */
    public record TestTenant(String id, String plant, String rawWarehouse, String finishedWarehouse) {}

    // ------------------------------------------------------------------ values

    protected static String id() {
        return Store.id();
    }

    protected static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    protected static BigDecimal dec(Object value) {
        return Store.dec(value);
    }

    protected static String str(Map<String, ?> row, String key) {
        return Store.str(row, key);
    }

    // ------------------------------------------------------------------ fixtures

    protected TestTenant createTenant() {
        return createTenant("Integration fixture");
    }

    protected TestTenant createTenant(String name) {
        String tenant = id();
        String plant = id();
        String raw = id();
        String finished = id();
        update("INSERT INTO tenants(id,name) VALUES(?,?)", tenant, name);
        update("INSERT INTO factories(id,tenant_id,name,location) VALUES(?,?,'Test plant','Test')", plant, tenant);
        update(
                "INSERT INTO warehouses(id,tenant_id,factory_id,name,kind) VALUES(?,?,?,'Raw','RAW'),(?,?,?,'Finished','FINISHED')",
                raw,
                tenant,
                plant,
                finished,
                tenant,
                plant);
        return new TestTenant(tenant, plant, raw, finished);
    }

    /** A user that cannot sign in by password; use it directly in service calls or with {@link #as(Actor)}. */
    protected Actor createUser(String tenant, String role) {
        String uid = id();
        String email = uid + "@example.test";
        update(
                "INSERT INTO users(id,tenant_id,email,name,password_hash,role) VALUES(?,?,?,?,'unused',?)",
                uid,
                tenant,
                email,
                role,
                role);
        return new Actor(uid, tenant, email, role, role);
    }

    /** A user with a real bcrypt password, for {@link #login(String, String)} through the form-login endpoint. */
    protected Actor createUser(String tenant, String role, String password) {
        String uid = id();
        String email = uid + "@example.test";
        update(
                "INSERT INTO users(id,tenant_id,email,name,password_hash,role) VALUES(?,?,?,?,?,?)",
                uid,
                tenant,
                email,
                role,
                passwords.encode(password),
                role);
        return new Actor(uid, tenant, email, role, role);
    }

    /**
     * A quality-released opening lot of a raw-material/packaging item, posted through the real stock ledger and journal
     * (RAW_INVENTORY / OPENING_EQUITY). Returns the lot id.
     */
    protected String openingStock(Actor admin, String item, String warehouse, String quantity, String unitCost) {
        String lot = id();
        update(
                "INSERT INTO lots(id,tenant_id,number,item_id,warehouse_id,status,source_type,source_id,unit_cost) VALUES(?,?,?,?,?,'RELEASED','OPENING','TEST',?)",
                lot,
                admin.tenant(),
                "OPENING-" + lot.substring(0, 8),
                item,
                warehouse,
                bd(unitCost));
        BigDecimal value = s.ledger(admin, lot, bd(quantity), "OPENING", "TEST_OPENING");
        s.journal(admin, "OPENING", "Test opening", "RAW_INVENTORY", "OPENING_EQUITY", value);
        return lot;
    }

    // ------------------------------------------------------------------ HTTP

    /** Authenticates one MockMvc request as {@code actor} (no password or session needed). */
    protected static RequestPostProcessor as(Actor actor) {
        return user(new ErpUser(actor, "unused"));
    }

    /** Signs in through {@code POST /api/auth/login} and returns the authenticated session. */
    protected MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("email", email)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** A JSON write command with a valid CSRF token and a fresh {@code Idempotency-Key}; add authentication yourself. */
    protected MockHttpServletRequestBuilder commandRequest(String path, Object body) {
        return commandRequest(path, body, id());
    }

    protected MockHttpServletRequestBuilder commandRequest(String path, Object body, String idempotencyKey) {
        var request = post(path).with(csrf()).header("Idempotency-Key", idempotencyKey);
        if (body != null) request.contentType("application/json").content(toJson(body));
        return request;
    }

    /** Posts {@code body} (a command record, a Map, or null) to {@code path} as {@code actor}. */
    protected ResultActions command(Actor actor, String path, Object body) throws Exception {
        return mvc.perform(commandRequest(path, body).with(as(actor)));
    }

    protected ResultActions command(Actor actor, String path, Object body, String idempotencyKey) throws Exception {
        return mvc.perform(commandRequest(path, body, idempotencyKey).with(as(actor)));
    }

    protected ResultActions command(MockHttpSession session, String path, Object body) throws Exception {
        return mvc.perform(commandRequest(path, body).session(session));
    }

    protected ResultActions query(Actor actor, String path) throws Exception {
        return mvc.perform(get(path).with(as(actor)));
    }

    protected String toJson(Object value) {
        return s.encode(value);
    }

    /** The response body of a JSON object endpoint. */
    protected Map<String, Object> json(ResultActions result) throws Exception {
        return s.decode(result.andReturn().getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ database

    protected int update(String sql, Object... args) {
        return s.db().update(sql, args);
    }

    /** Exactly one row (404 {@code ResponseStatusException} when none). */
    protected Map<String, Object> row(String sql, Object... args) {
        return s.one(sql, args);
    }

    protected List<Map<String, Object>> rows(String sql, Object... args) {
        return s.list(sql, args);
    }

    protected long count(String sql, Object... args) {
        return s.db().queryForObject(sql, Long.class, args);
    }

    protected BigDecimal decimal(String sql, Object... args) {
        return s.db().queryForObject(sql, BigDecimal.class, args);
    }
}
