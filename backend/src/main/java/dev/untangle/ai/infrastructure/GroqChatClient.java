package dev.untangle.ai.infrastructure;

import dev.untangle.ai.domain.LlmClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Groq's OpenAI-compatible Chat Completions API ({@code POST {baseUrl}/chat/completions}).
 *
 * <p>Resilience (§10):
 *
 * <ul>
 *   <li>connect timeout 3 s, read timeout {@code app.ai.timeout} (8 s): a slow provider never holds
 *       a request thread for long;
 *   <li>circuit breaker: after repeated failures, calls fail instantly for 30 s instead of piling
 *       up on a provider that's down;
 *   <li>JSON mode ({@code response_format: json_object}) and a low temperature for predictable,
 *       parseable answers.
 * </ul>
 */
public class GroqChatClient implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(GroqChatClient.class);

  private final RestClient http;
  private final String model;
  private final int maxTokens;
  private final CircuitBreaker breaker;

  public GroqChatClient(
      String baseUrl,
      String apiKey,
      String model,
      Duration timeout,
      int maxTokens,
      CircuitBreaker breaker) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(timeout);
    this.http =
        RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    this.model = model;
    this.maxTokens = maxTokens;
    this.breaker = breaker;
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public String completeJson(String systemPrompt, String userContent) {
    Map<String, Object> request =
        Map.of(
            "model",
            model,
            "temperature",
            0.2,
            "max_tokens",
            maxTokens,
            "response_format",
            Map.of("type", "json_object"),
            "messages",
            List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)));
    try {
      return breaker.executeSupplier(() -> call(request));
    } catch (CallNotPermittedException open) {
      throw new AiUnavailableException("AI circuit is open (provider failing recently)", open);
    } catch (AiUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      log.warn("AI provider call failed: {}", e.getClass().getSimpleName());
      throw new AiUnavailableException("AI provider call failed", e);
    }
  }

  @SuppressWarnings("unchecked")
  private String call(Map<String, Object> request) {
    Map<String, Object> response =
        http.post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(Map.class);
    try {
      List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
      Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
      return String.valueOf(message.get("content"));
    } catch (RuntimeException malformed) {
      throw new AiUnavailableException(
          "AI provider returned an unexpected response shape", malformed);
    }
  }
}
