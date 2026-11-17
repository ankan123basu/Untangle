package dev.untangle.webhook.hmac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.untangle.security.crypto.CryptoConfig.CryptoProperties;
import dev.untangle.security.crypto.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for the two crypto primitives of Phase 4: HMAC signatures and AES-GCM secrets. */
class WebhookCryptoTest {

  private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

  // ---- HMAC -------------------------------------------------------------------------------

  /**
   * Independent reference value computed with OpenSSL: {@code printf '1700000000.{"a":1}' | openssl
   * dgst -sha256 -hmac 'top-secret'}
   */
  @Test
  void matchesAnIndependentlyComputedSignature() {
    assertThat(HmacSignatures.sign("top-secret", "1700000000", BODY))
        .isEqualTo("sha256=" + OPENSSL_REFERENCE);
  }

  static final String OPENSSL_REFERENCE =
      "d2025794836f47aaa594a141e200ddfcf1ac5c703eeec4de43cd38a72262c5f3";

  @Test
  void verifiesItsOwnSignature() {
    String sig = HmacSignatures.sign("s3cret", "1700000000", BODY);
    assertThat(HmacSignatures.verify("s3cret", "1700000000", BODY, sig)).isTrue();
  }

  @Test
  void anyChangeBreaksTheSignature() {
    String sig = HmacSignatures.sign("s3cret", "1700000000", BODY);
    byte[] whitespace = "{\"a\": 1}".getBytes(StandardCharsets.UTF_8);

    assertThat(HmacSignatures.verify("s3cret", "1700000000", whitespace, sig))
        .as("whitespace")
        .isFalse();
    assertThat(HmacSignatures.verify("s3cret", "1700000001", BODY, sig)).as("timestamp").isFalse();
    assertThat(HmacSignatures.verify("other", "1700000000", BODY, sig)).as("secret").isFalse();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "abc",
        "sha256=",
        "sha1=0123456789abcdef0123456789abcdef01234567",
        "sha256=ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
        "sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeg"
      })
  void rejectsMalformedSignatureHeaders(String header) {
    assertThat(HmacSignatures.isWellFormed(header)).isFalse();
    assertThat(HmacSignatures.verify("s", "1", BODY, header)).isFalse();
  }

  // ---- AES-GCM ----------------------------------------------------------------------------

  private final SecretCipher cipher = new SecretCipher(new byte[32]);

  @Test
  void encryptsWithAFreshIvEachTimeAndDecryptsBack() {
    String a = cipher.encrypt("partner-secret", "acme-bank");
    String b = cipher.encrypt("partner-secret", "acme-bank");

    assertThat(a).startsWith("v1:").isNotEqualTo(b).doesNotContain("partner-secret");
    assertThat(cipher.decrypt(a, "acme-bank")).isEqualTo("partner-secret");
  }

  @Test
  void ciphertextIsBoundToItsPartner() {
    String stored = cipher.encrypt("partner-secret", "acme-bank");
    assertThatThrownBy(() -> cipher.decrypt(stored, "globex-pay"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tamperingAndWrongKeysAreDetected() {
    String stored = cipher.encrypt("partner-secret", "acme-bank");
    String tampered =
        stored.substring(0, stored.length() - 2) + (stored.endsWith("A") ? "BB" : "AA");
    byte[] otherKey = new byte[32];
    otherKey[0] = 1;

    assertThatThrownBy(() -> cipher.decrypt(tampered, "acme-bank"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SecretCipher(otherKey).decrypt(stored, "acme-bank"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cipher.decrypt("placeholder:x", "acme-bank"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** §11: "server started without an encryption key (fails fast)". */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  ", "not base64!", "AAAA"})
  void refusesToStartWithoutAValid256BitKey(String key) {
    assertThatThrownBy(() -> new CryptoProperties(key)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void acceptsA32ByteKeyAndNeverPrintsIt() {
    String key = Base64.getEncoder().encodeToString(new byte[32]);
    assertThat(new CryptoProperties(key).toString()).doesNotContain(key).contains("<redacted>");
  }
}
