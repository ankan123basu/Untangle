package dev.untangle.common.error;

/**
 * A {@code 429} that also tells the client when to retry (sent as the {@code Retry-After} header).
 */
public class RateLimitExceededException extends ApiException {

  private final long retryAfterSeconds;

  public RateLimitExceededException(long retryAfterSeconds) {
    super(ErrorCode.RATE_LIMITED, "Too many requests. Try again in " + retryAfterSeconds + "s.");
    this.retryAfterSeconds = retryAfterSeconds;
    with("retryAfterSeconds", retryAfterSeconds);
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
