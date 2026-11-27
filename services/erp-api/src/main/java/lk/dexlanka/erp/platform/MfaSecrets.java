package lk.dexlanka.erp.platform;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts authenticator secrets at rest (V17): AES-256-GCM under {@code app.mfa.encryption-key} (the base64 of 32
 * random bytes, {@code MFA_ENCRYPTION_KEY}), with the user id as associated data so a secret copied to another account
 * does not decrypt. The API refuses to start without a valid key; the demo and test profiles ship fixed keys.
 */
@Component
class MfaSecrets {
    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    MfaSecrets(@Value("${app.mfa.encryption-key:}") String encoded) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded.strip());
        } catch (IllegalArgumentException e) {
            raw = new byte[0];
        }
        if (raw.length != 32)
            throw new IllegalStateException(
                    "Set MFA_ENCRYPTION_KEY to the base64 of 32 random bytes (for example: openssl rand -base64 32)");
        key = new SecretKeySpec(raw, "AES");
    }

    String encrypt(String userId, String secret) {
        try {
            var iv = new byte[12];
            RANDOM.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(userId.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.US_ASCII));
            return PREFIX
                    + Base64.getEncoder()
                            .encodeToString(ByteBuffer.allocate(iv.length + sealed.length)
                                    .put(iv)
                                    .put(sealed)
                                    .array());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String decrypt(String userId, String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) throw new IllegalStateException("Unknown secret format");
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            cipher.updateAAD(userId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.US_ASCII);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "The two-step sign-in secret cannot be read; check MFA_ENCRYPTION_KEY or reset the user's two-step sign-in",
                    e);
        }
    }
}
