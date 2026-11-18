package dev.untangle.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Under overload the connection pool runs dry (found by the stress test, docs/scalability.md). The
 * client must get a fast, honest 503 with Retry-After, not a generic 500.
 */
class OverloadHandlingTest {

  @Test
  void poolExhaustionIsA503WithRetryAfterAndNoInternals() {
    var cause =
        new SQLTransientConnectionException(
            "untangle-db - Connection is not available, request timed out after 5000ms");
    var ex = new CannotCreateTransactionException("Could not open JPA EntityManager", cause);

    ResponseEntity<ProblemDetail> response = new GlobalExceptionHandler().handleNoConnection(ex);

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
    ProblemDetail body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getProperties()).containsEntry("code", "SERVICE_BUSY");
    assertThat(body.getDetail()).doesNotContain("untangle-db", "JPA", "5000ms");
  }
}
