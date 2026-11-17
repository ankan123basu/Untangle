package dev.untangle.common.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;

/**
 * Where token buckets live: in this JVM ({@link LocalBucketSource}, default) or in Redis so every
 * backend instance shares one limit (the {@code redis} profile, ADR-005).
 */
public interface BucketSource {

  Bucket bucket(String key, RateLimitPolicy policy);

  static BucketConfiguration configuration(RateLimitPolicy policy) {
    return BucketConfiguration.builder()
        .addLimit(limit ->
            limit.capacity(policy.capacity()).refillGreedy(policy.capacity(), policy.period()))
        .build();
  }
}
