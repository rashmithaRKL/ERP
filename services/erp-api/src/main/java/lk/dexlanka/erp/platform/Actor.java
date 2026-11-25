package lk.dexlanka.erp.platform;

import java.io.Serializable;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/**
 * The authenticated user a command runs as. Tenant ownership always comes from here, never from request JSON.
 *
 * <p>Roles: ADMIN, PLANNER, WAREHOUSE, OPERATOR, QUALITY, SALES, VIEWER, MAINTENANCE (V11). ADMIN passes every
 * {@link #allow} check.
 */
public record Actor(String id, String tenant, String email, String name, String role) implements Serializable {
    /** Throws {@link AccessDeniedException} (HTTP 403) unless the actor is ADMIN or has one of {@code roles}. */
    public void allow(String... roles) {
        if (!role.equals("ADMIN") && !Set.of(roles).contains(role))
            throw new AccessDeniedException("Your role cannot perform this action");
    }

    /** The actor of an authenticated request (controller methods take {@link Authentication} as a parameter). */
    public static Actor of(Authentication auth) {
        return ((ErpUser) auth.getPrincipal()).actor();
    }
}
