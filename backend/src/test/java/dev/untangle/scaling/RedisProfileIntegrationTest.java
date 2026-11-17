package dev.untangle.scaling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.untangle.agent.domain.Role;
import dev.untangle.common.error.RateLimitExceededException;
import dev.untangle.common.ratelimit.RateLimitPolicy;
import dev.untangle.common.ratelimit.RateLimiter;
import dev.untangle.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The optional {@code redis} profile (§15, ADR-005) against a real Redis, the same image as
 * docker-compose.yml: the dashboard cache and rate-limit buckets live in Redis, so every backend
 * instance sees the same numbers and the same limits.
 */
@ActiveProfiles({"test", "redis"})
class RedisProfileIntegrationTest extends IntegrationTest {

  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);

  static {
    REDIS.start();
  }

  @DynamicPropertySource
  static void redis(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired StringRedisTemplate redis;
  @Autowired RateLimiter limiter;

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  @Test
  void dashboardSummaryIsCachedInRedisAndEvictedOnChange() {
    String lead = bearer(Role.LEAD);
    createTicket(lead);

    assertThat((int) json(get("/api/v1/dashboard/summary", lead), "$.unresolved")).isEqualTo(1);
    assertThat(redis.keys("untangle:dashboardTeam*")).isNotEmpty();
    Long ttl = redis.getExpire(redis.keys("untangle:dashboardTeam*").iterator().next());
    assertThat(ttl).isBetween(1L, 30L);

    createTicket(lead); // commit → TicketChanged → evicted in Redis, i.e. for every instance
    assertThat(redis.keys("untangle:dashboardTeam*")).isEmpty();
    assertThat((int) json(get("/api/v1/dashboard/summary", lead), "$.unresolved")).isEqualTo(2);
  }

  @Test
  void rateLimitBucketsAreSharedThroughRedis() {
    RateLimitPolicy twoPerMinute = new RateLimitPolicy(2, Duration.ofMinutes(1));

    limiter.consume("test", twoPerMinute, "203.0.113.7");
    limiter.consume("test", twoPerMinute, "203.0.113.7");

    assertThatThrownBy(() -> limiter.consume("test", twoPerMinute, "203.0.113.7"))
        .isInstanceOf(RateLimitExceededException.class);
    assertThat(redis.hasKey("untangle:ratelimit:test:203.0.113.7")).isTrue();
  }
}
