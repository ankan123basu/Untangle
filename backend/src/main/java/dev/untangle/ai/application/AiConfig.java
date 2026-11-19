package dev.untangle.ai.application;

import dev.untangle.ai.domain.LlmClient;
import dev.untangle.ai.infrastructure.GroqChatClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

  /**
   * Where the AI teammate works (ADR-028): one virtual thread per ticket, so a slow model call
   * never holds up the request that assigned the ticket.
   */
  @Bean(name = "aiTeammateExecutor", destroyMethod = "close")
  ExecutorService aiTeammateExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
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
