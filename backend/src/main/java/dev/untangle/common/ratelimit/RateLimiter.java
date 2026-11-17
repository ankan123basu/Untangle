package dev.untangle.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.untangle.common.error.RateLimitExceededException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * In-memory token-bucket rate limiter (Bucket4j). One bucket per (policy, key), for example
 * ("login-ip", "203.0.113.7").
 *
 * <p>Buckets live in a Caffeine cache that forgets idle keys, so memory stays bounded even under a
 * flood of distinct IPs. Limits are <b>per backend instance</b>; the optional Redis profile makes
 * them global (ADR-005, documented in scalability.md).
 */
@Component
public class RateLimiter {

  private final Cache<String, Bucket> buckets =
      Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(30)).maximumSize(100_000).build();

  /**
   * Takes one token, or throws {@link RateLimitExceededException} (→ 429 with {@code Retry-After}).
   */
  public void consume(String policyName, RateLimitPolicy policy, String key) {
    Bucket bucket =
        buckets.get(
            policyName + ":" + key,
            ignored ->
                Bucket.builder()
                    .addLimit(limit ->
                        limit
                            .capacity(policy.capacity())
                            .refillGreedy(policy.capacity(), policy.period()))
                    .build());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      long seconds =
          Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
      throw new RateLimitExceededException(seconds);
    }
  }
}
