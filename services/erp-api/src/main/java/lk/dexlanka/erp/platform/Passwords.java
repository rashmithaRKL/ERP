package lk.dexlanka.erp.platform;

import static lk.dexlanka.erp.platform.Store.require;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The password rule shared by first-start bootstrap, account creation, self-service changes and administrator resets:
 * 16 to 128 characters (long passphrases instead of composition rules), not the account's email address. Hashing is
 * bcrypt (cost 12, {@code SecurityConfig}).
 */
public final class Passwords {
    public static final int MIN_LENGTH = 16;
    public static final int MAX_LENGTH = 128;

    /** No 0/O, 1/l/I: temporary passwords are read off a screen and typed. */
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    /** 409 unless {@code password} satisfies the rule for the account {@code email}. */
    public static void requireAcceptable(String password, String email) {
        require(
                password != null && password.length() >= MIN_LENGTH && password.length() <= MAX_LENGTH,
                "Use a password of " + MIN_LENGTH + " to " + MAX_LENGTH + " characters");
        require(
                email == null || !password.strip().equalsIgnoreCase(email.strip()),
                "Your password cannot be your email address");
    }

    /** A one-time temporary password such as {@code k7Qm2-Hx9pT-4bWcz-Ra3Ln} (23 characters, ~117 bits). */
    public static String temporary() {
        var out = new StringBuilder();
        for (int group = 0; group < 4; group++) {
            if (group > 0) out.append('-');
            for (int i = 0; i < 5; i++) out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    /**
     * A salted SHA-256 of a password for an idempotency payload, so that the stored request hash never equals an
     * unsalted digest of the password itself. {@code salt} is per request (the actor and the Idempotency-Key).
     */
    public static String fingerprint(String salt, String password) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest((salt + "\u0000" + password).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
