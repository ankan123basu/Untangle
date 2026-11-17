package dev.untangle.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import java.time.Duration;

/**
 * In-memory buckets in a Caffeine cache that forgets idle keys, so memory stays bounded even under
 * a flood of distinct IPs. Limits are per backend instance.
 */
public class LocalBucketSource implements BucketSource {

  private final Cache<String, Bucket> buckets =
      Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(30)).maximumSize(100_000).build();

  @Override
  public Bucket bucket(String key, RateLimitPolicy policy) {
    return buckets.get(
        key,
        ignored ->
            Bucket.builder()
                .addLimit(limit ->
                    limit
                        .capacity(policy.capacity())
                        .refillGreedy(policy.capacity(), policy.period()))
                .build());
  }
}
