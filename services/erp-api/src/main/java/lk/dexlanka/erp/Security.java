package lk.dexlanka.erp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.*;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@Configuration
class Security {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService users(Store s) {
        return email -> {
            var rows=s.list("SELECT * FROM users WHERE lower(email)=lower(?) AND active=true",email);
            if(rows.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
            var u=rows.getFirst();
            return new ErpUser(new Actor(u.get("id").toString(),u.get("tenant_id").toString(),u.get("email").toString(),u.get("name").toString(),u.get("role").toString()),u.get("password_hash").toString());
        };
    }
    @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a.requestMatchers("/api/auth/csrf","/api/auth/config","/api/auth/demo","/api/health").permitAll().anyRequest().authenticated())
            .csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
            .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
            .formLogin(f -> f.loginProcessingUrl("/api/auth/login").usernameParameter("email")
                .successHandler((req,res,auth) -> {res.setContentType("application/json");res.getWriter().write("{\"authenticated\":true}");})
                .failureHandler((req,res,e) -> {res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Email or password is incorrect\"}");}).permitAll())
            .logout(l -> l.logoutUrl("/api/auth/logout").logoutSuccessHandler((req,res,auth) -> res.setStatus(204)))
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,err) -> {res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Please sign in\"}");})
                .accessDeniedHandler((req,res,err) -> {res.setStatus(403);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Access denied or session token expired. Refresh and try again.\"}");}))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        http.addFilterBefore(new LoginRateLimit(),org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    record ErpUser(Actor actor,String hash) implements UserDetails {
        public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(new SimpleGrantedAuthority("ROLE_"+actor.role())); }
        public String getPassword() { return hash; }
        public String getUsername() { return actor.email(); }
    }
    static Actor actor(Authentication auth) { return ((ErpUser)auth.getPrincipal()).actor(); }
    static class LoginRateLimit extends org.springframework.web.filter.OncePerRequestFilter {
        private final Map<String,ArrayDeque<Long>> attempts=new ConcurrentHashMap<>();
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws java.io.IOException,ServletException {
            if(req.getMethod().equals("POST") && req.getRequestURI().equals("/api/auth/login")) {
                long now=System.currentTimeMillis();
                synchronized(attempts) {
                    attempts.entrySet().removeIf(e -> e.getValue().isEmpty() || e.getValue().getLast()<now-60000);
                    var times=attempts.computeIfAbsent(req.getRemoteAddr(),k -> new ArrayDeque<>());
                    while(!times.isEmpty() && times.getFirst()<now-60000) times.removeFirst();
                    if(times.size()>=10) { res.setStatus(429);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Too many sign-in attempts. Try again in one minute.\"}");return; }
                    times.add(now);
                }
            }
            chain.doFilter(req,res);
        }
    }
}

@RestController
class AuthController {
    final Store s; final UserDetailsService users; final boolean demo;
    AuthController(Store s,UserDetailsService users,@Value("${app.demo:false}") boolean demo) {this.s=s;this.users=users;this.demo=demo;}
    @GetMapping("/api/health") Map<String,Object> health() { s.db.queryForObject("SELECT 1",Integer.class);return Map.of("status","UP"); }
    @GetMapping("/api/auth/config") Map<String,Object> config() {return Map.of("demo",demo,"version","0.1.0");}
    @GetMapping("/api/auth/csrf") Map<String,String> csrf(CsrfToken token) {return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
    @GetMapping("/api/auth/me") Map<String,Object> me(Authentication auth) {
        var a=Security.actor(auth);return Map.of("id",a.id(),"name",a.name(),"email",a.email(),"role",a.role(),"tenant",s.one("SELECT id,name,currency,demo FROM tenants WHERE id=?",a.tenant()));
    }
    @PostMapping("/api/auth/demo") Map<String,Object> demo(HttpServletRequest req,HttpServletResponse res,@RequestParam(defaultValue="admin") String role) {
        if(!demo) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        if(!Set.of("admin","planner","operator","warehouse","quality").contains(role)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Unknown demo role");
        var u=users.loadUserByUsername("demo."+role+"@dexlanka.test");
        req.getSession();req.changeSessionId();
        var ctx=SecurityContextHolder.createEmptyContext();ctx.setAuthentication(new UsernamePasswordAuthenticationToken(u,null,u.getAuthorities()));SecurityContextHolder.setContext(ctx);
        new HttpSessionSecurityContextRepository().saveContext(ctx,req,res);
        return Map.of("authenticated",true);
    }
}
