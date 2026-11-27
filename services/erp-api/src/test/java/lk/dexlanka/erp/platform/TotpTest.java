package lk.dexlanka.erp.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** V17: RFC 6238 appendix B vectors (SHA-1, last six digits), Base32 round trips and the accepted window. */
class TotpTest {
    static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheRfc6238TestVectors() {
        assertThat(RFC_SECRET).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.code(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void base32RoundTripsAndNewSecretsAre160Bits() {
        String secret = Totp.newSecret();
        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.unbase32(secret)).hasSize(20);
        assertThat(Totp.base32(Totp.unbase32(secret))).isEqualTo(secret);
    }

    @Test
    void acceptsOneStepEitherSideAndNothingElse() {
        long now = 1_700_000_000L;
        long step = now / 30;
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step), now)).hasValue(step);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now)).hasValue(step - 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now)).hasValue(step + 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now)).isEmpty();
        assertThat(Totp.match(RFC_SECRET, "12345", now)).isEmpty();
        assertThat(Totp.match(RFC_SECRET, "abcdef", now)).isEmpty();
    }

    @Test
    void uriCarriesIssuerAccountAndParameters() {
        assertThat(Totp.uri("DexLanka Manufacturing Pro", "a@b.test", "ABC"))
                .isEqualTo(
                        "otpauth://totp/DexLanka%20Manufacturing%20Pro:a%40b.test?secret=ABC&issuer=DexLanka%20Manufacturing%20Pro&algorithm=SHA1&digits=6&period=30");
    }
}
