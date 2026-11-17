package dev.untangle.webhook.application;

import dev.untangle.common.ratelimit.RateLimitPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.webhook.*}: limits of the partner webhook (§7).
 *
 * @param maxBodyBytes largest accepted body (64 KB); bigger → 413 before anything else is read
 * @param timestampTolerance accepted clock difference between partner and us, both directions
 *     (replay window)
 * @param rotationGrace how long a rotated-out secret keeps working
 * @param perIpLimit cheap flood protection applied before any signature work (R2)
 * @param demoPartnerSecret demo profile only: the plaintext secret given to the seeded partner
 */
@ConfigurationProperties("app.webhook")
public record WebhookProperties(
    int maxBodyBytes,
    Duration timestampTolerance,
    Duration rotationGrace,
    RateLimitPolicy perIpLimit,
    String demoPartnerSecret) {

  public WebhookProperties {
    maxBodyBytes = maxBodyBytes <= 0 ? 64 * 1024 : maxBodyBytes;
    timestampTolerance = timestampTolerance == null ? Duration.ofMinutes(5) : timestampTolerance;
    rotationGrace = rotationGrace == null ? Duration.ofHours(24) : rotationGrace;
    perIpLimit = perIpLimit == null ? new RateLimitPolicy(300, Duration.ofMinutes(1)) : perIpLimit;
  }

  @Override
  public String toString() {
    return "WebhookProperties[maxBodyBytes="
        + maxBodyBytes
        + ", timestampTolerance="
        + timestampTolerance
        + ", rotationGrace="
        + rotationGrace
        + "]";
  }
}
