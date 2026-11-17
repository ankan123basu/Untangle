package dev.untangle.common.ratelimit;

import dev.untangle.common.error.RateLimitExceededException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Token-bucket rate limiter (Bucket4j). One bucket per (policy, key), for example ("login-ip",
 * "203.0.113.7"). Buckets come from a {@link BucketSource}: in memory per instance by default, or
 * shared in Redis under the {@code redis} profile so limits hold across instances (ADR-005).
 */
@Component
public class RateLimiter {

  private final BucketSource buckets;

  public RateLimiter(BucketSource buckets) {
    this.buckets = buckets;
  }

  /**
   * Takes one token, or throws {@link RateLimitExceededException} (→ 429 with {@code Retry-After}).
   */
  public void consume(String policyName, RateLimitPolicy policy, String key) {
    Bucket bucket = buckets.bucket(policyName + ":" + key, policy);
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      long seconds =
          Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
      throw new RateLimitExceededException(seconds);
    }
  }
}
