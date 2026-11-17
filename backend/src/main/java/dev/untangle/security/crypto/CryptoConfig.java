package dev.untangle.security.crypto;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Master key for encrypting secrets at rest ({@code APP_SECRET_ENCRYPTION_KEY}). Fails fast (§6.3,
 * §11 "server started without an encryption key"): no key, or a key that isn't exactly 32 bytes of
 * base64, stops the application at startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CryptoConfig.CryptoProperties.class)
public class CryptoConfig {

  @ConfigurationProperties("app.crypto")
  public record CryptoProperties(String secretEncryptionKey) {

    public CryptoProperties {
      if (secretEncryptionKey == null || secretEncryptionKey.isBlank()) {
        throw new IllegalStateException(
            "APP_SECRET_ENCRYPTION_KEY is not set. Generate one with: openssl rand -base64 32"
                + " (make up does this automatically in .env)");
      }
      byte[] key;
      try {
        key = Base64.getDecoder().decode(secretEncryptionKey.strip());
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("APP_SECRET_ENCRYPTION_KEY must be base64-encoded", e);
      }
      if (key.length != 32) {
        throw new IllegalStateException(
            "APP_SECRET_ENCRYPTION_KEY must decode to exactly 32 bytes (AES-256), got "
                + key.length);
      }
    }

    byte[] keyBytes() {
      return Base64.getDecoder().decode(secretEncryptionKey.strip());
    }

    @Override
    public String toString() {
      return "CryptoProperties[secretEncryptionKey=<redacted>]";
    }
  }

  @Bean
  SecretCipher secretCipher(CryptoProperties properties) {
    return new SecretCipher(properties.keyBytes());
  }
}
