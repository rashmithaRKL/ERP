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
 *   <li><b>Two-step sign-in (V17)</b> — after a correct password of an account with two-step sign-in on, only the
 *       identity, CSRF, sign-out and code routes are served ({@code code=MFA_REQUIRED}) for
 *       {@value MfaService#PENDING_MILLIS} ms, then the session ends. A password session of a user whom
 *       {@link MfaPolicy} requires to use it, but who has not set it up, is limited to the set-up routes
 *       ({@code code=MFA_SETUP_REQUIRED}); this also covers a user promoted to administrator mid-session.
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

    static final Set<String> OPEN_DURING_MFA = Set.of(
            "/api/health",
            "/api/auth/config",
            "/api/auth/csrf",
            "/api/auth/me",
            "/api/auth/demo",
            "/api/auth/logout",
            "/api/auth/mfa/verify");

    static final Set<String> OPEN_DURING_MFA_SETUP = Set.of(
            "/api/health",
            "/api/auth/config",
            "/api/auth/csrf",
            "/api/auth/me",
            "/api/auth/demo",
            "/api/auth/logout",
            "/api/auth/password",
            "/api/auth/mfa/setup",
            "/api/auth/mfa/enable");

    private final JdbcTemplate db;
    private final MfaPolicy mfa;
    private final HttpSessionSecurityContextRepository sessions = new HttpSessionSecurityContextRepository();

    SessionGuard(JdbcTemplate db, MfaPolicy mfa) {
        this.db = db;
        this.mfa = mfa;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ErpUser user) {
            var rows = db.queryForList(
                    "SELECT u.tenant_id,u.email,u.name,u.role,u.active,u.session_version,u.must_change_password,u.mfa_enabled_at,t.demo FROM users u JOIN tenants t ON t.id=u.tenant_id WHERE u.id=?",
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
                var session = req.getSession(false);
                String uri = req.getRequestURI();
                Object pending = session == null ? null : session.getAttribute(MfaService.PENDING);
                if (pending != null) {
                    if (System.currentTimeMillis() - (Long) pending > MfaService.PENDING_MILLIS) {
                        session.invalidate();
                        SecurityContextHolder.clearContext();
                    } else if (!OPEN_DURING_MFA.contains(uri)) {
                        deny(res, "Enter the code from your authenticator app to finish signing in", "MFA_REQUIRED");
                        return;
                    }
                    chain.doFilter(req, res);
                    return;
                }
                if (Boolean.TRUE.equals(row.get("must_change_password"))
                        && !OPEN_DURING_PASSWORD_CHANGE.contains(uri)) {
                    deny(res, "Change your temporary password to continue", "PASSWORD_CHANGE_REQUIRED");
                    return;
                }
                if (session != null
                        && session.getAttribute(MfaService.PASSWORD_SESSION) != null
                        && row.get("mfa_enabled_at") == null
                        && mfa.required(Objects.toString(row.get("role")), Boolean.TRUE.equals(row.get("demo")))
                        && !OPEN_DURING_MFA_SETUP.contains(uri)) {
                    deny(res, "Set up two-step sign-in to continue", "MFA_SETUP_REQUIRED");
                    return;
                }
            }
        }
        chain.doFilter(req, res);
    }

    private static void deny(HttpServletResponse res, String message, String code) throws IOException {
        res.setStatus(403);
        res.setContentType("application/json");
        res.getWriter().write("{\"message\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }
}
