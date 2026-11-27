package lk.dexlanka.erp.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request bodies of the signed-in user's own account commands ({@code /api/auth/*}). */
public final class AccountCommands {
    private AccountCommands() {}

    /**
     * Self-service password change, also used to replace a temporary password after an administrator reset. The new
     * password follows {@link Passwords}: {@value Passwords#MIN_LENGTH} to {@value Passwords#MAX_LENGTH} characters.
     */
    public record ChangePassword(
            @NotBlank @Size(max = Passwords.MAX_LENGTH) String currentPassword,

            @NotBlank @Size(min = Passwords.MIN_LENGTH, max = Passwords.MAX_LENGTH)
            String newPassword) {}

    /** V17: the second step of a sign-in, an authenticator code (6 digits) or an unused recovery code. */
    public record MfaCode(@NotBlank @Size(max = 32) String code) {}

    /**
     * V17: turn on two-step sign-in, regenerate recovery codes or turn it off. {@code code} is the authenticator code (or,
     * except when turning it on, a recovery code).
     */
    public record MfaConfirm(
            @NotBlank @Size(max = Passwords.MAX_LENGTH) String currentPassword,
            @NotBlank @Size(max = 32) String code) {}
}
