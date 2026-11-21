package lk.dexlanka.erp.platform;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Spring Security principal stored in the HTTP session; wraps the {@link Actor}. */
public record ErpUser(Actor actor, String hash) implements UserDetails {
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
