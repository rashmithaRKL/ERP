package lk.dexlanka.erp.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * Session authentication (form login at {@code /api/auth/login}), CSRF on every unsafe method, JSON error bodies and
 * the login rate limit. Every route except the few {@code permitAll} ones requires a signed-in user; role checks happen
 * in the domain services via {@link Actor#allow}.
 */
@Configuration
class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService users(Store s) {
        return email -> {
            var rows = s.list("SELECT * FROM users WHERE lower(email)=lower(?) AND active=true", email);
            if (rows.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
            var u = rows.getFirst();
            return new ErpUser(
                    new Actor(
                            u.get("id").toString(),
                            u.get("tenant_id").toString(),
                            u.get("email").toString(),
                            u.get("name").toString(),
                            u.get("role").toString()),
                    u.get("password_hash").toString());
        };
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(
                        a -> a.requestMatchers("/api/auth/csrf", "/api/auth/config", "/api/auth/demo", "/api/health")
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
                .formLogin(f -> f.loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email")
                        .successHandler((req, res, auth) -> {
                            res.setContentType("application/json");
                            res.getWriter().write("{\"authenticated\":true}");
                        })
                        .failureHandler((req, res, e) -> {
                            res.setStatus(401);
                            res.setContentType("application/json");
                            res.getWriter().write("{\"message\":\"Email or password is incorrect\"}");
                        })
                        .permitAll())
                .logout(l ->
                        l.logoutUrl("/api/auth/logout").logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, err) -> {
                            res.setStatus(401);
                            res.setContentType("application/json");
                            res.getWriter().write("{\"message\":\"Please sign in\"}");
                        })
                        .accessDeniedHandler((req, res, err) -> {
                            res.setStatus(403);
                            res.setContentType("application/json");
                            res.getWriter()
                                    .write(
                                            "{\"message\":\"Access denied or session token expired. Refresh and try again.\"}");
                        }))
                .headers(h ->
                        h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        http.addFilterBefore(new LoginRateLimit(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
