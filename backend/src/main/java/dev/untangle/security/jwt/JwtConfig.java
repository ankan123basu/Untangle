package dev.untangle.security.jwt;

import dev.untangle.security.SecurityProperties;
import java.time.Clock;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * JWT signing and verification with one symmetric HS256 key (ADR-013).
 *
 * <p>The decoder accepts only HS256 (so an attacker can't switch to {@code alg: none} or another
 * algorithm), and validates expiry (30 s clock-skew tolerance) and our issuer.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

  @Bean
  SecretKey jwtSigningKey(SecurityProperties properties) {
    return new SecretKeySpec(properties.jwt().secretBytes(), "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
    return NimbusJwtEncoder.withSecretKey(jwtSigningKey).algorithm(MacAlgorithm.HS256).build();
  }

  @Bean
  JwtDecoder jwtDecoder(SecretKey jwtSigningKey, SecurityProperties properties, Clock clock) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
    JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
    timestamps.setClock(clock);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            timestamps, new JwtIssuerValidator(properties.jwt().issuer())));
    return decoder;
  }
}
