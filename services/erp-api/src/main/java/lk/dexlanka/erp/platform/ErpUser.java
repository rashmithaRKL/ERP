package lk.dexlanka.erp.platform;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security principal stored in the HTTP session; wraps the {@link Actor}. {@code sessionVersion} is the user's
 * {@code users.session_version} at sign-in: {@link SessionGuard} invalidates the session once the stored version moves
 * on (deactivation, password reset or change).
 */
public record ErpUser(Actor actor, String hash, int sessionVersion) implements UserDetails {
    /** A principal at session version 0, the version of every account that never had its sessions revoked. */
    public ErpUser(Actor actor, String hash) {
        this(actor, hash, 0);
    }

    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + actor.role()));
    }

    public String getPassword() {
        return hash;
    }

    public String getUsername() {
        return actor.email();
    }
}
