package dev.untangle.common;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** RFC 9457 error format, correlation ids and security headers (ADR-007, Sections 6.3, 16). */
class ErrorHandlingIntegrationTest extends IntegrationTest {

  private String adminBearer;

  @BeforeEach
  void signIn() {
    adminBearer = bearer(Role.ADMIN);
  }

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  private MvcTestResult createAgent(String body, String requestId) {
    var request =
        mvc.post()
            .uri("/api/v1/agents")
            .header(HttpHeaders.AUTHORIZATION, adminBearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (requestId != null) {
      request = request.header("X-Request-Id", requestId);
    }
    return request.exchange();
  }

  @Test
  void validationErrorsAreProblemDetailsWithFieldErrorsAndTraceId() {
    MvcTestResult result =
        createAgent(
            "{\"fullName\":\"X\",\"email\":\"not-an-email\",\"password\":\"tiny\"}",
            "test-request-0001");

    assertThat(result).hasStatus(400);
    assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(400);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.type")
        .isEqualTo("urn:untangle:problem:validation-failed");
    assertThat(result).bodyJson().extractingPath("$.traceId").isEqualTo("test-request-0001");
    assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo("test-request-0001");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .contains("fullName", "email", "password", "role");
    assertThat(body(result))
        .as("no rejected values, no internals")
        .doesNotContain("tiny", "not-an-email", "Exception", "jakarta");
  }

  @Test
  void malformedJsonIsA400WithoutParserInternals() {
    MvcTestResult result = createAgent("{\"fullName\": ", null);

    assertThat(result).hasStatus(400);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Malformed JSON request body");
    assertThat(body(result)).doesNotContain("jackson", "Unexpected");
  }

  @Test
  void unsafeRequestIdsAreReplaced() {
    MvcTestResult result =
        mvc.get()
            .uri("/api/v1/auth/me")
            .header(HttpHeaders.AUTHORIZATION, adminBearer)
            .header("X-Request-Id", "evil\nINFO forged log line")
            .exchange();

    assertThat(result.getResponse().getHeader("X-Request-Id"))
        .isNotBlank()
        .doesNotContain("evil", "\n");
  }

  @Test
  void requestIdIsGeneratedWhenMissing() {
    MvcTestResult result = mvc.get().uri("/api/v1/auth/me").exchange();
    assertThat(result.getResponse().getHeader("X-Request-Id")).hasSize(36);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.traceId")
        .isEqualTo(result.getResponse().getHeader("X-Request-Id"));
  }

  @Test
  void unknownRouteIsA404Problem() {
    MvcTestResult result =
        mvc.get().uri("/api/v1/nope").header(HttpHeaders.AUTHORIZATION, adminBearer).exchange();

    assertThat(result).hasStatus(404);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
  }

  @Test
  void wrongMethodIsA405Problem() {
    MvcTestResult result =
        mvc.delete()
            .uri("/api/v1/auth/me")
            .header(HttpHeaders.AUTHORIZATION, adminBearer)
            .exchange();

    assertThat(result).hasStatus(405);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
  }

  @Test
  void apiResponsesCarrySecurityHeaders() {
    MvcTestResult result =
        mvc.get().uri("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, adminBearer).exchange();

    assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(result.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
    assertThat(result.getResponse().getHeader("Referrer-Policy"))
        .isEqualTo("strict-origin-when-cross-origin");
    assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
  }
}
