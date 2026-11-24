package lk.dexlanka.erp.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Health, CSRF bootstrap, current identity, the signed-in user's password change and the demo-only persona switch.
 * Login/logout are Spring Security URLs.
 */
@RestController
class AuthController {
    private final Store s;
    private final UserDetailsService users;
    private final PasswordService passwords;
    private final boolean demo;

    AuthController(
            Store s, UserDetailsService users, PasswordService passwords, @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.users = users;
        this.passwords = passwords;
        this.demo = demo;
    }

    @GetMapping("/api/health")
    Map<String, Object> health() {
        s.db().queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "UP");
    }

    @GetMapping("/api/auth/config")
    Map<String, Object> config() {
        return Map.of("demo", demo, "version", "0.1.0");
    }

    @GetMapping("/api/auth/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    /** The signed-in user. {@code mustChangePassword} is true after an administrator reset (and for new accounts). */
    @GetMapping("/api/auth/me")
    Map<String, Object> me(Authentication auth) {
        var a = Actor.of(auth);
        var account = s.one(
                "SELECT must_change_password,last_login_at,password_changed_at FROM users WHERE tenant_id=? AND id=?",
                a.tenant(),
                a.id());
        var result = new LinkedHashMap<String, Object>();
        result.put("id", a.id());
        result.put("name", a.name());
        result.put("email", a.email());
        result.put("role", a.role());
        result.put("tenant", s.one("SELECT id,name,currency,demo FROM tenants WHERE id=?", a.tenant()));
        result.put("mustChangePassword", Boolean.TRUE.equals(account.get("must_change_password")));
        result.put("lastLoginAt", account.get("last_login_at"));
        result.put("passwordChangedAt", account.get("password_changed_at"));
        return result;
    }

    /**
     * Changes the signed-in user's password (current password required). The user's other sessions end on their next
     * request; this session continues under a new session id.
     */
    @PostMapping("/api/auth/password")
    Map<String, Object> changePassword(
            Authentication auth,
            @Valid @RequestBody AccountCommands.ChangePassword c,
            @RequestHeader("Idempotency-Key") String k,
            HttpServletRequest req,
            HttpServletResponse res) {
        var result = passwords.change(Actor.of(auth), c, k);
        signIn(users.loadUserByUsername(Actor.of(auth).email()), req, res);
        return result;
    }

    @PostMapping("/api/auth/demo")
    Map<String, Object> demo(
            HttpServletRequest req, HttpServletResponse res, @RequestParam(defaultValue = "admin") String role) {
        if (!demo) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!Set.of("admin", "planner", "operator", "warehouse", "quality").contains(role))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown demo role");
        UserDetails u;
        try {
            u = users.loadUserByUsername("demo." + role + "@dexlanka.test");
        } catch (UsernameNotFoundException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This demo persona is deactivated; an administrator can reactivate it under Administration → Users");
        }
        signIn(u, req, res);
        s.db().update(
                        "UPDATE users SET last_login_at=now() WHERE id=?",
                        ((ErpUser) u).actor().id());
        return Map.of("authenticated", true);
    }

    /** Stores {@code u} as the session principal under a fresh session id. */
    private static void signIn(UserDetails u, HttpServletRequest req, HttpServletResponse res) {
        req.getSession();
        req.changeSessionId();
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        SecurityContextHolder.setContext(ctx);
        new HttpSessionSecurityContextRepository().saveContext(ctx, req, res);
    }
}
