package dev.untangle.webhook.application;

import dev.untangle.security.crypto.SecretCipher;
import dev.untangle.webhook.infrastructure.PartnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo profile only. The SQL seed can't encrypt (the key lives in an env var), so it leaves a
 * {@code placeholder:} secret on the demo partner {@code acme-bank}. On startup this replaces it
 * with {@code app.webhook.demo-partner-secret}, encrypted with this installation's key, so the
 * partner simulator and the docs' curl examples work out of the box.
 */
@Component
@Profile("demo")
class DemoPartnerSecretInitializer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoPartnerSecretInitializer.class);
  static final String DEMO_PARTNER = "acme-bank";

  private final PartnerRepository partners;
  private final SecretCipher cipher;
  private final WebhookProperties properties;

  DemoPartnerSecretInitializer(
      PartnerRepository partners, SecretCipher cipher, WebhookProperties properties) {
    this.partners = partners;
    this.cipher = cipher;
    this.properties = properties;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    String secret = properties.demoPartnerSecret();
    if (secret == null || secret.isBlank()) {
      return;
    }
    partners
        .findByCode(DEMO_PARTNER)
        .filter(p -> p.getSecretCiphertext().startsWith("placeholder:"))
        .ifPresent(p -> {
          p.replaceSecret(cipher.encrypt(secret, p.getCode()));
          log.info(
              "Demo partner '{}' secret initialised from app.webhook.demo-partner-secret",
              DEMO_PARTNER);
        });
  }
}
