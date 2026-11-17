package dev.untangle.ai.application;

import dev.untangle.common.ratelimit.RateLimitPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.ai.*}. AI is optional: without {@code GROQ_API_KEY} (or with {@code AI_ENABLED=false})
 * every AI endpoint answers 503 {@code AI_UNAVAILABLE}, {@code GET /ai/status} says disabled, and
 * the UI hides the AI panels. Nothing else depends on it.
 */
@ConfigurationProperties("app.ai")
public record AiProperties(
    boolean enabled,
    String apiKey,
    String baseUrl,
    String model,
    Duration timeout,
    int maxTokens,
    RateLimitPolicy perUserLimit) {

  public AiProperties {
    baseUrl =
        baseUrl == null || baseUrl.isBlank() ? "https://api.groq.com/openai/v1" : baseUrl.strip();
    model = model == null || model.isBlank() ? "openai/gpt-oss-20b" : model.strip();
    timeout = timeout == null ? Duration.ofSeconds(8) : timeout;
    maxTokens = maxTokens <= 0 ? 800 : maxTokens;
    perUserLimit =
        perUserLimit == null ? new RateLimitPolicy(20, Duration.ofMinutes(1)) : perUserLimit;
  }

  /** On only when switched on AND a key is present. */
  public boolean isActive() {
    return enabled && apiKey != null && !apiKey.isBlank();
  }

  @Override
  public String toString() {
    return "AiProperties[enabled="
        + enabled
        + ", apiKey="
        + (apiKey == null || apiKey.isBlank() ? "<none>" : "<redacted>")
        + ", baseUrl="
        + baseUrl
        + ", model="
        + model
        + ", timeout="
        + timeout
        + "]";
  }
}
