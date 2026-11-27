package lk.dexlanka.erp.platform;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238 with the authenticator-app defaults: HMAC-SHA1, 6 digits, 30-second steps)
 * and the Base32 encoding (RFC 4648) the apps read. V17.
 */
public final class Totp {
    static final int DIGITS = 6;
    static final long STEP_SECONDS = 30;
    /** Steps accepted on either side of the server's clock (phone clocks drift). */
    static final int WINDOW = 1;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** A new 160-bit secret, Base32 encoded (32 characters). */
    public static String newSecret() {
        var bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    /** The {@code otpauth://} URI an authenticator app scans as a QR code. */
    public static String uri(String issuer, String account, String secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + enc(issuer) + "&algorithm=SHA1&digits="
                + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** The code for {@code step} (seconds since the epoch divided by {@link #STEP_SECONDS}). */
    static String code(String secret, long step) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(unbase32(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", bin % (int) Math.pow(10, DIGITS));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The time step {@code code} matches within {@link #WINDOW} of {@code epochSeconds}, if any, so the caller can refuse
     * a step that was already used.
     */
    static OptionalLong match(String secret, String code, long epochSeconds) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) return OptionalLong.empty();
        long now = Math.floorDiv(epochSeconds, STEP_SECONDS);
        for (long step = now - WINDOW; step <= now + WINDOW; step++) {
            if (java.security.MessageDigest.isEqual(
                    code(secret, step).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII)))
                return OptionalLong.of(step);
        }
        return OptionalLong.empty();
    }

    static String base32(byte[] bytes) {
        var out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    static byte[] unbase32(String text) {
        String s = text.replace("=", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        var out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : s.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Not Base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
