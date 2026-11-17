package dev.untangle.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Section 6.3: fail fast at startup if a required secret is missing or too weak. */
class SecurityPropertiesTest {

  private static String base64OfLength(int bytes) {
    return Base64.getEncoder().encodeToString(new byte[bytes]);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void refusesToStartWithoutJwtSecret(String secret) {
    assertThatThrownBy(() -> new SecurityProperties.Jwt(secret, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET is not set");
  }

  @Test
  void refusesSecretsShorterThan256Bits() {
    assertThatThrownBy(() -> new SecurityProperties.Jwt(base64OfLength(31), null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("too short");
  }

  @Test
  void refusesNonBase64Secrets() {
    assertThatThrownBy(() -> new SecurityProperties.Jwt("not base64 !!", null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("base64");
  }

  @Test
  void acceptsA256BitSecretAndAppliesDefaults() {
    SecurityProperties.Jwt jwt = new SecurityProperties.Jwt(base64OfLength(32), null, null);
    assertThat(jwt.issuer()).isEqualTo("untangle");
    assertThat(jwt.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void neverPrintsTheSecret() {
    String secret = base64OfLength(48);
    assertThat(new SecurityProperties.Jwt(secret, null, null).toString())
        .doesNotContain(secret)
        .contains("<redacted>");
  }
}
