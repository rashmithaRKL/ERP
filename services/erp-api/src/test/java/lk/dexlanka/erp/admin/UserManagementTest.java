package lk.dexlanka.erp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.catalog.CatalogCommands;
import lk.dexlanka.erp.platform.AccountCommands;
import lk.dexlanka.erp.platform.Passwords;
import org.junit.jupiter.api.Test;

/**
 * User administration: paged directory, edit, deactivate/reactivate with session revocation, last-admin protection,
 * administrator password reset with the forced change, self-service password change, last sign-in, deterministic
 * {@code POST /api/users} idempotency, permissions and tenant isolation.
 */
class UserManagementTest extends AdminTestSupport {
    private static final String NEW_PASSWORD = "a-brand-new-passphrase-2026";

    @Test
    void directoryIsServerPagedFilteredAndSummarized() throws Exception {
        namedUser("Anura Bandara", "QUALITY");
        var chamari = namedUser("Chamari Silva", "SALES");
        namedUser("Bimal Rathnayake", "VIEWER");
        update("UPDATE users SET active=false WHERE id=?", chamari.id());

        var first = json(query(admin, "/api/admin/users?limit=2").andExpect(status().isOk()));
        assertThat(names(first)).containsExactly("ADMIN", "Anura Bandara");
        var second = json(query(admin, "/api/admin/users?limit=2&before=" + first.get("next")));
        assertThat(names(second)).containsExactly("Bimal Rathnayake", "Chamari Silva");
        var third = json(query(admin, "/api/admin/users?limit=2&before=" + second.get("next")));
        assertThat(names(third)).containsExactly("PLANNER", "WAREHOUSE");
        assertThat(third.get("next")).isEqualTo("");

        assertThat(names(json(query(admin, "/api/admin/users?status=INACTIVE"))))
                .containsExactly("Chamari Silva");
        assertThat(names(json(query(admin, "/api/admin/users?role=QUALITY")))).containsExactly("Anura Bandara");
        assertThat(names(json(
                        query(admin, "/api/admin/users?q=" + planner.email().substring(0, 12)))))
                .containsExactly("PLANNER");
        assertThat(names(json(query(admin, "/api/admin/users?q=rathna")))).containsExactly("Bimal Rathnayake");

        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) first.get("summary");
        assertThat(((Number) summary.get("total")).intValue()).isEqualTo(6);
        assertThat(((Number) summary.get("inactive")).intValue()).isEqualTo(1);
        assertThat(((Number) summary.get("admins")).intValue()).isEqualTo(1);

        query(planner, "/api/admin/users").andExpect(status().isForbidden());
        query(admin, "/api/admin/users?limit=500").andExpect(status().isConflict());
    }

    @Test
    void editChangesNameAndRoleOnTheUsersNextRequest() throws Exception {
        var session = signIn(planner.email(), PASSWORD);
        String key = id();
        var body = new AdminCommands.UserUpdate("Dilini Perera", "VIEWER");
        var edited = json(
                command(admin, "/api/admin/users/" + planner.id(), body, key).andExpect(status().isOk()));
        assertThat(edited.get("role")).isEqualTo("VIEWER");
        assertThat(edited.get("name")).isEqualTo("Dilini Perera");

        // Not signed out: the next request already runs with the new identity.
        read(session, "/api/auth/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.name").value("Dilini Perera"));
        assertThat(rows("SELECT detail FROM audit_events WHERE tenant_id=? AND action='USER_UPDATED'", t))
                .extracting(r -> r.get("detail"))
                .containsExactly("name PLANNER → Dilini Perera; role Planner → Viewer");

        // Idempotent replay; the same key with another body is rejected.
        command(admin, "/api/admin/users/" + planner.id(), body, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
        command(admin, "/api/admin/users/" + planner.id(), new AdminCommands.UserUpdate("Other", "VIEWER"), key)
                .andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='USER_UPDATED'", t))
                .isEqualTo(1);

        command(admin, "/api/admin/users/" + planner.id(), Map.of("name", "X", "role", "OWNER"))
                .andExpect(status().isBadRequest());
        command(planner, "/api/admin/users/" + warehouse.id(), new AdminCommands.UserUpdate("X", "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deactivatedUsersCannotSignInAndTheirOpenSessionsEnd() throws Exception {
        var first = signIn(warehouse.email(), PASSWORD);
        var second = signIn(warehouse.email(), PASSWORD);
        read(first, "/api/auth/me").andExpect(status().isOk());

        command(admin, "/api/admin/users/" + warehouse.id() + "/deactivate", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        read(first, "/api/auth/me").andExpect(status().isUnauthorized());
        read(second, "/api/state?days=7").andExpect(status().isUnauthorized());
        write(second, "/api/inventory/warehouses", Map.of("name", "X", "code", "X1", "kind", "RAW"))
                .andExpect(status().isUnauthorized());
        loginAttempt(warehouse.email(), PASSWORD).andExpect(status().isUnauthorized());
        query(warehouse, "/api/auth/me").andExpect(status().isUnauthorized());
        assertThat(row("SELECT deactivated_at FROM users WHERE id=?", warehouse.id())
                        .get("deactivated_at"))
                .isNotNull();

        command(admin, "/api/admin/users/" + warehouse.id() + "/deactivate", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This user is already inactive"));
        command(admin, "/api/admin/users/" + admin.id() + "/deactivate", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("You cannot deactivate your own account"));

        command(admin, "/api/admin/users/" + warehouse.id() + "/reactivate", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        read(first, "/api/auth/me").andExpect(status().isUnauthorized()); // revoked sessions stay revoked
        var fresh = signIn(warehouse.email(), PASSWORD);
        read(fresh, "/api/auth/me").andExpect(status().isOk());
        assertThat(rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at",
                        t,
                        warehouse.email()))
                .extracting(r -> r.get("action"))
                .containsExactly("USER_DEACTIVATED", "USER_REACTIVATED");
        command(planner, "/api/admin/users/" + admin.id() + "/deactivate", null).andExpect(status().isForbidden());
    }

    @Test
    void theLastActiveAdministratorCannotBeDemotedOrDeactivated() throws Exception {
        command(admin, "/api/admin/users/" + admin.id(), new AdminCommands.UserUpdate("Owner", "PLANNER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("You cannot demote the last active administrator; make another user an administrator"
                                + " first"));

        var second = createUser(t, "ADMIN", PASSWORD);
        command(second, "/api/admin/users/" + admin.id() + "/deactivate", null).andExpect(status().isOk());
        command(second, "/api/admin/users/" + second.id(), new AdminCommands.UserUpdate("Second", "VIEWER"))
                .andExpect(status().isConflict());
        command(admin, "/api/admin/users/" + second.id() + "/deactivate", null).andExpect(status().isUnauthorized());

        // A demotion with another active admin is allowed and applies to the demoted user's next request.
        command(second, "/api/admin/users/" + admin.id() + "/reactivate", null).andExpect(status().isOk());
        var session = signIn(second.email(), PASSWORD);
        write(session, "/api/admin/users/" + second.id(), new AdminCommands.UserUpdate("Second", "PLANNER"))
                .andExpect(status().isOk());
        read(session, "/api/admin/users").andExpect(status().isForbidden());

        // The database keeps the rule even for direct updates.
        assertThatThrownBy(() -> update("UPDATE users SET active=false WHERE id=?", admin.id()))
                .hasMessageContaining("A workspace needs at least one active administrator");
        assertThatThrownBy(() -> update("UPDATE users SET role='VIEWER' WHERE id=?", admin.id()))
                .hasMessageContaining("A workspace needs at least one active administrator");
    }

    @Test
    void administratorResetIssuesAOneTimePasswordThatMustBeChanged() throws Exception {
        var open = signIn(planner.email(), PASSWORD);
        String key = id();
        var reset = json(command(admin, "/api/admin/users/" + planner.id() + "/reset-password", null, key)
                .andExpect(status().isOk()));
        String temporary = reset.get("temporaryPassword").toString();
        assertThat(temporary).hasSize(23).matches("[a-zA-Z2-9]{5}(-[a-zA-Z2-9]{5}){3}");
        assertThat(reset.get("must_change_password")).isEqualTo(true);
        read(open, "/api/auth/me").andExpect(status().isUnauthorized());

        // Shown once: the idempotent replay never returns it and it is not stored in clear text anywhere.
        command(admin, "/api/admin/users/" + planner.id() + "/reset-password", null, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").value(nullValue()));
        assertThat(count(
                        "SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id=? AND response::text LIKE ?",
                        t,
                        "%" + temporary + "%"))
                .isZero();
        assertThat(count(
                        "SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND detail LIKE ?",
                        t,
                        "%" + temporary + "%"))
                .isZero();

        loginAttempt(planner.email(), PASSWORD).andExpect(status().isUnauthorized());
        loginAttempt(planner.email(), temporary)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        var session = signIn(planner.email(), temporary);
        read(session, "/api/auth/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        read(session, "/api/state?days=7")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        write(session, "/api/inventory/warehouses", Map.of("name", "X", "code", "X1", "kind", "RAW"))
                .andExpect(status().isForbidden());

        write(session, "/api/auth/password", new AccountCommands.ChangePassword(temporary, "too-short"))
                .andExpect(status().isBadRequest());
        write(session, "/api/auth/password", new AccountCommands.ChangePassword("not-the-password", NEW_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Your current password is incorrect"));
        write(session, "/api/auth/password", new AccountCommands.ChangePassword(temporary, NEW_PASSWORD))
                .andExpect(status().isOk());
        read(session, "/api/state?days=7").andExpect(status().isOk());
        read(session, "/api/auth/me").andExpect(jsonPath("$.mustChangePassword").value(false));
        loginAttempt(planner.email(), temporary).andExpect(status().isUnauthorized());
        signIn(planner.email(), NEW_PASSWORD);
        assertThat(rows(
                        "SELECT action FROM audit_events WHERE tenant_id=? AND reference=? ORDER BY created_at",
                        t,
                        planner.email()))
                .extracting(r -> r.get("action"))
                .containsExactly("USER_PASSWORD_RESET", "PASSWORD_CHANGED");

        command(admin, "/api/admin/users/" + admin.id() + "/reset-password", null)
                .andExpect(status().isConflict());
        command(warehouse, "/api/admin/users/" + planner.id() + "/reset-password", null)
                .andExpect(status().isForbidden());
        // The planner's password changed twice, so an old principal (session version 0) is signed out.
        query(planner, "/api/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    void selfServiceChangeKeepsThisSessionAndEndsTheOthers() throws Exception {
        var viewer = createUser(t, "VIEWER", PASSWORD);
        var here = signIn(viewer.email(), PASSWORD);
        var elsewhere = signIn(viewer.email(), PASSWORD);
        write(here, "/api/auth/password", new AccountCommands.ChangePassword(PASSWORD, PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Choose a password that is different from your current one"));
        write(here, "/api/auth/password", new AccountCommands.ChangePassword(PASSWORD, viewer.email()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Your password cannot be your email address"));
        write(here, "/api/auth/password", new AccountCommands.ChangePassword(PASSWORD, NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHANGED"));

        read(here, "/api/auth/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
        read(elsewhere, "/api/auth/me").andExpect(status().isUnauthorized());
        loginAttempt(viewer.email(), PASSWORD).andExpect(status().isUnauthorized());
        signIn(viewer.email(), NEW_PASSWORD);
        var account = row("SELECT * FROM users WHERE id=?", viewer.id());
        assertThat(account.get("password_changed_at")).isNotNull();
        assertThat(account.get("last_login_at")).isNotNull();
        assertThat(((Number) account.get("session_version")).intValue()).isEqualTo(1);
        // Neither the password nor an unsalted digest of it reaches the idempotency record.
        assertThat(count(
                        "SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id=? AND action='change-password' AND payload_hash=?",
                        t,
                        sha256(NEW_PASSWORD)))
                .isZero();
    }

    @Test
    void userCreationHasADeterministicIdempotencyHashAndAForcedFirstChange() throws Exception {
        String email = id() + "@example.test";
        String password = "first-sign-in-password-1";
        String key = id();
        var body = new CatalogCommands.User(email, "New planner", password, "PLANNER");
        var created = json(command(admin, "/api/users", body, key).andExpect(status().isOk()));

        var canonical = new LinkedHashMap<String, Object>();
        canonical.put("email", email);
        canonical.put("name", "New planner");
        canonical.put("role", "PLANNER");
        canonical.put("passwordFingerprint", Passwords.fingerprint(t + ":" + key, password));
        assertThat(row("SELECT payload_hash FROM idempotency_keys WHERE tenant_id=? AND request_key=?", t, key)
                        .get("payload_hash"))
                .isEqualTo(sha256(toJson(canonical)));

        command(admin, "/api/users", body, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.get("id")));
        command(admin, "/api/users", new CatalogCommands.User(email, "New planner", password + "x", "PLANNER"), key)
                .andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM users WHERE email=?", email)).isEqualTo(1);
        command(admin, "/api/users", body).andExpect(status().isConflict()); // same email, new key

        loginAttempt(email, password)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        @SuppressWarnings("unchecked")
        var listed = (List<Map<String, Object>>) json(query(admin, "/api/admin/users?q=" + email.substring(0, 20)))
                .get("items");
        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst().get("last_login_at")).isNotNull();
        assertThat(listed.getFirst().get("must_change_password")).isEqualTo(true);
    }

    @Test
    void anotherTenantsUsersAreInvisible() throws Exception {
        var other = createTenant("Other tenant");
        var otherAdmin = createUser(other.id(), "ADMIN");
        command(otherAdmin, "/api/admin/users/" + planner.id(), new AdminCommands.UserUpdate("X", "VIEWER"))
                .andExpect(status().isNotFound());
        command(otherAdmin, "/api/admin/users/" + planner.id() + "/deactivate", null)
                .andExpect(status().isNotFound());
        command(otherAdmin, "/api/admin/users/" + planner.id() + "/reset-password", null)
                .andExpect(status().isNotFound());
        assertThat(names(json(query(otherAdmin, "/api/admin/users")))).containsExactly("ADMIN");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> names(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items"))
                .stream().map(u -> u.get("name")).toList();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
