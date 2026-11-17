package dev.untangle.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.untangle.common.error.RateLimitExceededException;
import dev.untangle.common.ratelimit.RateLimitPolicy;
import dev.untangle.common.ratelimit.RateLimiter;
import dev.untangle.common.util.Masking;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CommonUtilitiesTest {

  @ParameterizedTest
  @CsvSource(
      nullValues = "NULL",
      value = {
        "rahul.sharma@example.com, r***@example.com",
        "a@b.io, a***@b.io",
        "not-an-email, ***",
        "NULL, <none>"
      })
  void masksEmailsForLogs(String input, String expected) {
    assertThat(Masking.email(input)).isEqualTo(expected);
  }

  @Test
  void rateLimiterAllowsUpToCapacityThenRejectsWithRetryAfter() {
    RateLimiter limiter = new RateLimiter();
    RateLimitPolicy threePerMinute = new RateLimitPolicy(3, Duration.ofMinutes(1));

    for (int i = 0; i < 3; i++) {
      limiter.consume("test", threePerMinute, "key-a");
    }

    assertThatThrownBy(() -> limiter.consume("test", threePerMinute, "key-a"))
        .isInstanceOfSatisfying(
            RateLimitExceededException.class,
            ex -> assertThat(ex.getRetryAfterSeconds()).isBetween(1L, 60L));
  }

  @Test
  void rateLimiterKeysAreIndependent() {
    RateLimiter limiter = new RateLimiter();
    RateLimitPolicy onePerMinute = new RateLimitPolicy(1, Duration.ofMinutes(1));

    limiter.consume("test", onePerMinute, "key-a");
    limiter.consume("test", onePerMinute, "key-b");
    limiter.consume("other-policy", onePerMinute, "key-a");
  }

  @Test
  void rateLimitPolicyRejectsNonsense() {
    assertThatThrownBy(() -> new RateLimitPolicy(0, Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RateLimitPolicy(1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
