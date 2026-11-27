package lk.dexlanka.erp.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Who must use two-step sign-in (V17): administrators of a real workspace ({@code app.mfa.required-for-admins}, on by
 * default). The demo workspace is exempt, so its persona switch keeps working; anyone may still turn it on. Other roles
 * opt in from their profile.
 */
@Component
public class MfaPolicy {
    private volatile boolean requiredForAdmins;

    MfaPolicy(@Value("${app.mfa.required-for-admins:true}") boolean requiredForAdmins) {
        this.requiredForAdmins = requiredForAdmins;
    }

    public boolean required(String role, boolean demoTenant) {
        return requiredForAdmins && !demoTenant && "ADMIN".equals(role);
    }

    /** Tests only: the test profile turns the requirement off so the existing sign-in tests keep their shape. */
    public void requiredForAdmins(boolean required) {
        this.requiredForAdmins = required;
    }
}
