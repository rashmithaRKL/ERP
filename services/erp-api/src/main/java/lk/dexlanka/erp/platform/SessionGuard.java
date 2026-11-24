package lk.dexlanka.erp.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Revalidates the signed-in principal against {@code users} on every authenticated request (one primary-key lookup):
 *
 * <ul>
 *   <li><b>Revocation</b> — a deactivated or deleted account, or a principal whose {@code sessionVersion} is older than
 *       {@code users.session_version} (deactivation, password reset or change), loses its session: the session is
 *       invalidated and the request continues anonymously, so protected routes answer 401.
 *   <li><b>Fresh identity</b> — a changed name, email or role (an administrator edit) replaces the session principal in
 *       place, so a demotion takes effect on the user's next request without signing them out.
 *   <li><b>Forced password change</b> — while {@code must_change_password} is set, only the identity, CSRF, sign-out and
 *       password-change routes are served; everything else answers 403 with {@code code=PASSWORD_CHANGE_REQUIRED}.
 * </ul>
 *
 * <p>A per-user version in the database (rather than an in-memory session registry) works across restarts and several
 * API processes. Added to the security filter chain by {@link SecurityConfig}; deliberately not a Spring bean.
 */
class SessionGuard extends OncePerRequestFilter {
    static final Set<String> OPEN_DURING_PASSWORD_CHANGE = Set.of(
            "/api/health",
            "/api/auth/config",
            "/api/auth/csrf",
            "/api/auth/me",
            "/api/auth/demo",
            "/api/auth/logout",
            "/api/auth/password");

    private final JdbcTemplate db;
    private final HttpSessionSecurityContextRepository sessions = new HttpSessionSecurityContextRepository();

    SessionGuard(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ErpUser user) {
            var rows = db.queryForList(
                    "SELECT tenant_id,email,name,role,active,session_version,must_change_password FROM users WHERE id=?",
                    user.actor().id());
            var row = rows.isEmpty() ? null : rows.getFirst();
            if (row == null
                    || !Boolean.TRUE.equals(row.get("active"))
                    || !user.actor().tenant().equals(row.get("tenant_id"))
                    || ((Number) row.get("session_version")).intValue() != user.sessionVersion()) {
                var session = req.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
            } else {
                var actor = user.actor();
                if (!actor.email().equals(row.get("email"))
                        || !actor.name().equals(row.get("name"))
                        || !actor.role().equals(row.get("role"))) {
                    var fresh = new ErpUser(
                            new Actor(
                                    actor.id(),
                                    actor.tenant(),
                                    Objects.toString(row.get("email")),
                                    Objects.toString(row.get("name")),
                                    Objects.toString(row.get("role"))),
                            user.hash(),
                            user.sessionVersion());
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(fresh, null, fresh.getAuthorities()));
                    SecurityContextHolder.setContext(context);
                    if (req.getSession(false) != null) sessions.saveContext(context, req, res);
                }
                if (Boolean.TRUE.equals(row.get("must_change_password"))
                        && !OPEN_DURING_PASSWORD_CHANGE.contains(req.getRequestURI())) {
                    res.setStatus(403);
                    res.setContentType("application/json");
                    res.getWriter()
                            .write(
                                    "{\"message\":\"Change your temporary password to continue\",\"code\":\"PASSWORD_CHANGE_REQUIRED\"}");
                    return;
                }
            }
        }
        chain.doFilter(req, res);
    }
}
