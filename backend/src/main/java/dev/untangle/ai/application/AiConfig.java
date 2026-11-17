package dev.untangle.ai.application;

import dev.untangle.ai.domain.LlmClient;
import dev.untangle.ai.infrastructure.GroqChatClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfig {

  /** Opens after 50% failures over the last 10 calls (min 5); stays open 30 s, then probes. */
  @Bean
  CircuitBreaker aiCircuitBreaker() {
    return CircuitBreaker.of(
        "groq",
        CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .build());
  }

  @Bean
  LlmClient llmClient(AiProperties properties, CircuitBreaker aiCircuitBreaker) {
    return new GroqChatClient(
        properties.baseUrl(),
        properties.apiKey() == null ? "" : properties.apiKey(),
        properties.model(),
        properties.timeout(),
        properties.maxTokens(),
        aiCircuitBreaker);
  }
}
