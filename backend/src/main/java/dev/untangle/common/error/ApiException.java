package dev.untangle.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A business-rule failure that maps directly to an API error. Services throw it; {@link
 * GlobalExceptionHandler} turns it into an RFC 9457 Problem Details response.
 *
 * <pre>{@code
 * throw new ApiException(ErrorCode.LAST_ADMIN, "At least one active admin must remain");
 * }</pre>
 *
 * <p>Extra machine-readable fields can be attached with {@link #with(String, Object)} and appear as
 * top-level properties of the problem body. Never attach secrets or PII.
 */
public class ApiException extends RuntimeException {

  private final ErrorCode code;
  private final Map<String, Object> properties = new LinkedHashMap<>();

  public ApiException(ErrorCode code) {
    this(code, code.title());
  }

  public ApiException(ErrorCode code, String detail) {
    super(detail);
    this.code = code;
  }

  public ApiException with(String key, Object value) {
    properties.put(key, value);
    return this;
  }

  public ErrorCode getCode() {
    return code;
  }

  public Map<String, Object> getProperties() {
    return Collections.unmodifiableMap(properties);
  }
}
