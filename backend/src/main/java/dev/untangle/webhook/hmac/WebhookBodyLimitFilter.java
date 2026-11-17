package dev.untangle.webhook.hmac;

import dev.untangle.webhook.application.DeliveryRecorder;
import dev.untangle.webhook.application.WebhookProperties;
import dev.untangle.webhook.domain.DeliveryOutcome;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Step 1 of the webhook pipeline (§7.2): reject bodies over 64 KB <b>before</b> they're buffered,
 * then cache the exact raw bytes for signature verification.
 *
 * <p>Two checks, because {@code Content-Length} can be missing (chunked transfer) or lie: the
 * header is checked first, then the stream is read with a hard cap. Only the webhook path is
 * affected. Runs right after {@code RequestIdFilter}, before Spring Security, so an oversized flood
 * costs almost nothing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class WebhookBodyLimitFilter extends OncePerRequestFilter {

  public static final String PATH = "/api/v1/webhooks/partners/tickets";

  private final WebhookProperties properties;
  private final DeliveryRecorder recorder;

  public WebhookBodyLimitFilter(WebhookProperties properties, DeliveryRecorder recorder) {
    this.properties = properties;
    this.recorder = recorder;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !PATH.equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    int max = properties.maxBodyBytes();
    if (request.getContentLengthLong() > max) {
      reject(request, response);
      return;
    }
    byte[] body = readAtMost(request.getInputStream(), max);
    if (body == null) {
      reject(request, response);
      return;
    }
    chain.doFilter(new CachedBodyRequest(request, body), response);
  }

  /** Reads the whole body, or returns null as soon as it exceeds {@code max} bytes. */
  private static byte[] readAtMost(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 8192));
    byte[] buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = in.read(buffer)) != -1) {
      total += read;
      if (total > max) {
        return null;
      }
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
    recorder.record(
        request.getHeader("X-Partner-Id"),
        null,
        request.getHeader("X-Event-Id"),
        DeliveryOutcome.REJECTED_TOO_LARGE,
        null,
        "body larger than " + properties.maxBodyBytes() + " bytes",
        request.getRemoteAddr(),
        null);
    response.setStatus(413);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"type\":\"urn:untangle:problem:payload-too-large\",\"title\":\"Payload too large\","
                + "\"status\":413,\"code\":\"PAYLOAD_TOO_LARGE\",\"detail\":\"Webhook body must be"
                + " at most "
                + properties.maxBodyBytes()
                + " bytes\"}");
  }

  /** Request whose body can be read any number of times from memory. */
  static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      java.io.ByteArrayInputStream source = new java.io.ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public boolean isFinished() {
          return source.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException();
        }

        @Override
        public int read() {
          return source.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return source.read(b, off, len);
        }
      };
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
