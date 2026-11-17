package dev.untangle.common.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * "At most {@code capacity} requests per {@code period}" (token bucket, refilled smoothly).
 *
 * @param capacity burst size and number of tokens refilled per period
 * @param period refill window
 */
public record RateLimitPolicy(long capacity, Duration period) {

  public RateLimitPolicy {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1");
    }
    Objects.requireNonNull(period, "period");
    if (period.isNegative() || period.isZero()) {
      throw new IllegalArgumentException("period must be positive");
    }
  }
}
