package dev.untangle.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Code that needs "now" asks for a {@link Clock} instead of calling {@code Instant.now()}. In tests
 * we can then use a fixed clock to check lockouts, token expiry and SLA deadlines
 * deterministically.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
