package lk.dexlanka.erp.platform;

import static lk.dexlanka.erp.platform.Store.require;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/auth/mfa/*} (V17): the signed-in user's two-step sign-in. See {@link MfaService}. */
@RestController
@RequestMapping("/api/auth/mfa")
class MfaController {
    private final MfaService mfa;
    private final UserDetailsService users;
    private final Store s;

    MfaController(MfaService mfa, UserDetailsService users, Store s) {
        this.mfa = mfa;
        this.users = users;
        this.s = s;
    }

    /** A new secret for the authenticator app; kept in this session until {@code /enable} confirms it. */
    @PostMapping("/setup")
    Map<String, Object> setup(Authentication auth, HttpServletRequest req) {
        var result = mfa.setup(Actor.of(auth));
        req.getSession().setAttribute(MfaService.ENROLLING, result.get("secret"));
        return result;
    }

    @PostMapping("/enable")
    Map<String, Object> enable(
            Authentication auth,
            @Valid @RequestBody AccountCommands.MfaConfirm c,
            @RequestHeader("Idempotency-Key") String k,
            HttpServletRequest req,
            HttpServletResponse res) {
        var session = req.getSession(false);
        var enrolling = session == null ? null : (String) session.getAttribute(MfaService.ENROLLING);
        var result = mfa.enable(Actor.of(auth), c, k, enrolling);
        if (session != null) session.removeAttribute(MfaService.ENROLLING);
        AuthController.signIn(users.loadUserByUsername(Actor.of(auth).email()), req, res);
        return result;
    }

    /** The code step of a password sign-in; completes the sign-in under a new session id. */
    @PostMapping("/verify")
    Map<String, Object> verify(
            Authentication auth, @Valid @RequestBody AccountCommands.MfaCode c, HttpServletRequest req) {
        var session = req.getSession(false);
        require(
                session != null && session.getAttribute(MfaService.PENDING) != null,
                "No sign-in is waiting for a code. Sign in again");
        var a = Actor.of(auth);
        var result = new LinkedHashMap<>(mfa.verify(a, c.code()));
        session.removeAttribute(MfaService.PENDING);
        session.setAttribute(MfaService.PASSWORD_SESSION, true);
        req.changeSessionId();
        s.db().update("UPDATE users SET last_login_at=now() WHERE id=?", a.id());
        result.put("authenticated", true);
        result.put(
                "mustChangePassword",
                Boolean.TRUE.equals(s.db().queryForObject(
                                "SELECT must_change_password FROM users WHERE id=?", Boolean.class, a.id())));
        return result;
    }

    @PostMapping("/recovery-codes")
    Map<String, Object> regenerate(
            Authentication auth,
            @Valid @RequestBody AccountCommands.MfaConfirm c,
            @RequestHeader("Idempotency-Key") String k) {
        return mfa.regenerate(Actor.of(auth), c, k);
    }

    @PostMapping("/disable")
    Map<String, Object> disable(
            Authentication auth,
            @Valid @RequestBody AccountCommands.MfaConfirm c,
            @RequestHeader("Idempotency-Key") String k,
            HttpServletRequest req,
            HttpServletResponse res) {
        var result = mfa.disable(Actor.of(auth), c, k);
        AuthController.signIn(users.loadUserByUsername(Actor.of(auth).email()), req, res);
        return result;
    }
}
