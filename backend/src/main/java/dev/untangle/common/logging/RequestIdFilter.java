package dev.untangle.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id (Section 16).
 *
 * <ul>
 *   <li>Reuses the caller's {@code X-Request-Id} (nginx sets one) if it looks safe, otherwise
 *       generates a UUID. Untrusted values are length- and charset-checked so they can't inject
 *       fake log lines.
 *   <li>Puts it in the logging MDC, so every log line of this request carries {@code requestId}.
 *   <li>Echoes it in the response header and in every error body as {@code traceId}, so a user can
 *       quote it and we can find the exact logs.
 * </ul>
 *
 * Runs first, before Spring Security, so even 401s are traceable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Request-Id";
  public static final String MDC_KEY = "requestId";
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String requestId =
        incoming != null && SAFE_ID.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
    MDC.put(MDC_KEY, requestId);
    response.setHeader(HEADER, requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
