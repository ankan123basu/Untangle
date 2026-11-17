package dev.untangle.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import dev.untangle.common.ratelimit.BucketSource;
import dev.untangle.common.ratelimit.LocalBucketSource;
import dev.untangle.common.ratelimit.RateLimitPolicy;
import dev.untangle.ticket.application.DashboardCounts;
import dev.untangle.ticket.application.DashboardCounts.TeamCounts;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

/**
 * The two "scale-out" switches of §15, both decided by the {@code redis} profile (ADR-005):
 *
 * <ul>
 *   <li>Dashboard cache ({@link DashboardCounts}): Caffeine per instance, or Redis shared by all
 *       instances. Same 30 s TTL either way; eviction happens on every ticket change.
 *   <li>Rate-limit buckets: in memory per instance, or Redis so a limit of "10 logins a minute"
 *       means 10 across the cluster.
 * </ul>
 *
 * The principal cache in {@link CacheConfig} stays local on purpose (R8).
 */
@Configuration(proxyBeanMethods = false)
public class ScalingConfig {

  private static final Duration TTL = Duration.ofSeconds(DashboardCounts.TTL_SECONDS);

  @Configuration(proxyBeanMethods = false)
  @Profile("!redis")
  static class InMemory {

    @Bean(DashboardCounts.CACHE_MANAGER)
    CacheManager dashboardCacheManager() {
      CaffeineCacheManager manager =
          new CaffeineCacheManager(DashboardCounts.TEAM, DashboardCounts.MINE);
      manager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(10_000));
      return manager;
    }

    @Bean
    BucketSource bucketSource() {
      return new LocalBucketSource();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("redis")
  static class Redis {

    @Bean(DashboardCounts.CACHE_MANAGER)
    CacheManager dashboardCacheManager(RedisConnectionFactory connections) {
      RedisCacheConfiguration base =
          RedisCacheConfiguration.defaultCacheConfig()
              .entryTtl(TTL)
              .disableCachingNullValues()
              .prefixCacheNameWith("untangle:");
      return RedisCacheManager.builder(connections)
          .withInitialCacheConfigurations(
              Map.of(
                  DashboardCounts.TEAM,
                  base.serializeValuesWith(
                      SerializationPair.fromSerializer(
                          new JacksonJsonRedisSerializer<>(TeamCounts.class))),
                  DashboardCounts.MINE,
                  base.serializeValuesWith(
                      SerializationPair.fromSerializer(
                          new JacksonJsonRedisSerializer<>(Long.class)))))
          .disableCreateOnMissingCache()
          .build();
    }

    @Bean(destroyMethod = "shutdown")
    RedisClient bucketRedisClient(
        @Value("${spring.data.redis.host:localhost}") String host,
        @Value("${spring.data.redis.port:6379}") int port) {
      return RedisClient.create(RedisURI.builder().withHost(host).withPort(port).build());
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<byte[], byte[]> bucketRedisConnection(RedisClient client) {
      return client.connect(ByteArrayCodec.INSTANCE);
    }

    @Bean
    BucketSource bucketSource(StatefulRedisConnection<byte[], byte[]> connection) {
      // Keys expire once a bucket would be full again, so idle IPs don't pile up in Redis.
      ProxyManager<byte[]> proxies =
          Bucket4jLettuce.casBasedBuilder(connection)
              .expirationAfterWrite(
                  ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                      Duration.ofMinutes(1)))
              .build();
      return (String key, RateLimitPolicy policy) -> {
        byte[] redisKey = ("untangle:ratelimit:" + key).getBytes(StandardCharsets.UTF_8);
        Bucket bucket = proxies.builder().build(redisKey, () -> BucketSource.configuration(policy));
        return bucket;
      };
    }
  }
}
