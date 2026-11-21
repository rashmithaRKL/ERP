package lk.dexlanka.erp;

import java.io.Serializable;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;

record Actor(String id,String tenant,String email,String name,String role) implements Serializable {
    void allow(String... roles) { if(!role.equals("ADMIN") && !Set.of(roles).contains(role)) throw new AccessDeniedException("Your role cannot perform this action"); }
}
