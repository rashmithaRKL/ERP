package lk.dexlanka.erp.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Health, CSRF bootstrap, current identity and the demo-only persona switch. Login/logout are Spring Security URLs. */
@RestController
class AuthController {
    private final Store s;
    private final UserDetailsService users;
    private final boolean demo;

    AuthController(Store s, UserDetailsService users, @Value("${app.demo:false}") boolean demo) {
        this.s = s;
        this.users = users;
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

    @GetMapping("/api/auth/me")
    Map<String, Object> me(Authentication auth) {
        var a = Actor.of(auth);
        return Map.of(
                "id",
                a.id(),
                "name",
                a.name(),
                "email",
                a.email(),
                "role",
                a.role(),
                "tenant",
                s.one("SELECT id,name,currency,demo FROM tenants WHERE id=?", a.tenant()));
    }

    @PostMapping("/api/auth/demo")
    Map<String, Object> demo(
            HttpServletRequest req, HttpServletResponse res, @RequestParam(defaultValue = "admin") String role) {
        if (!demo) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!Set.of("admin", "planner", "operator", "warehouse", "quality").contains(role))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown demo role");
        var u = users.loadUserByUsername("demo." + role + "@dexlanka.test");
        req.getSession();
        req.changeSessionId();
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        SecurityContextHolder.setContext(ctx);
        new HttpSessionSecurityContextRepository().saveContext(ctx, req, res);
        return Map.of("authenticated", true);
    }
}
