package dev.untangle.ai.domain;

/**
 * A chat model that answers with a JSON object. Implemented by {@code GroqChatClient}; tests can
 * replace the HTTP endpoint with a fake server.
 *
 * <p>Model output is <b>untrusted</b>. Callers validate every field and never let it trigger an
 * action on its own (§10).
 */
public interface LlmClient {

  /** Model name actually used, stored with every insight for provenance. */
  String model();

  /**
   * @return the raw text of the model's answer (expected to contain a JSON object)
   * @throws AiUnavailableException on timeout, provider error, open circuit or missing key
   */
  String completeJson(String systemPrompt, String userContent);

  /** The provider is unreachable, failing, too slow, or switched off. */
  class AiUnavailableException extends RuntimeException {
    public AiUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
