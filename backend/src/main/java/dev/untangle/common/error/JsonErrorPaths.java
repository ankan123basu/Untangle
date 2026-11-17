package dev.untangle.common.error;

import java.util.stream.Collectors;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.databind.DatabindException;

/**
 * Extracts the JSON field name from a Jackson 3 deserialisation error, e.g. {@code "priority"} when
 * the client sent {@code "priority": "high"} for an enum. Returns {@code null} when the body isn't
 * valid JSON at all.
 */
final class JsonErrorPaths {

  private JsonErrorPaths() {}

  static String fieldOf(HttpMessageNotReadableException ex) {
    Throwable cause = ex.getCause();
    while (cause != null) {
      if (cause instanceof DatabindException databind && !databind.getPath().isEmpty()) {
        return databind.getPath().stream()
            .map(ref ->
                ref.getPropertyName() != null ? ref.getPropertyName() : "[" + ref.getIndex() + "]")
            .collect(Collectors.joining("."));
      }
      cause = cause.getCause();
    }
    return null;
  }
}
