package lk.dexlanka.erp.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * At most 10 sign-in attempts per remote address per minute (per process). Added to the security filter chain by
 * {@link SecurityConfig}; deliberately not a Spring bean, so it is not also registered as a servlet filter.
 */
class LoginRateLimit extends OncePerRequestFilter {
    private final Map<String, ArrayDeque<Long>> attempts = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (req.getMethod().equals("POST") && req.getRequestURI().equals("/api/auth/login")) {
            long now = System.currentTimeMillis();
            synchronized (attempts) {
                attempts.entrySet()
                        .removeIf(e -> e.getValue().isEmpty() || e.getValue().getLast() < now - 60000);
                var times = attempts.computeIfAbsent(req.getRemoteAddr(), k -> new ArrayDeque<>());
                while (!times.isEmpty() && times.getFirst() < now - 60000) times.removeFirst();
                if (times.size() >= 10) {
                    res.setStatus(429);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"message\":\"Too many sign-in attempts. Try again in one minute.\"}");
                    return;
                }
                times.add(now);
            }
        }
        chain.doFilter(req, res);
    }
}
