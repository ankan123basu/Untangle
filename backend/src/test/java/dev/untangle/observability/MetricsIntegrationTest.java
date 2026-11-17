package dev.untangle.observability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * §16: business metrics reach the Prometheus scrape, and the probe paths the containers use are
 * public. (In the {@code prod} profile the scrape lives on the unpublished management port; tests
 * run actuator on the main port.)
 */
class MetricsIntegrationTest extends IntegrationTest {

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  private String scrape() {
    MvcTestResult result = mvc.get().uri("/actuator/prometheus").exchange();
    assertThat(result).hasStatusOk();
    return body(result);
  }

  @Test
  void ticketCreationAndTransitionsAreCounted() {
    String lead = bearer(Role.LEAD);
    MvcTestResult ticket = createTicket(lead);
    String closeAsSpam =
        "{\"targetStatus\":\"CLOSED\",\"reason\":\"duplicate of another ticket\",\"version\":%d}"
            .formatted(version(ticket));
    assertThat(post("/api/v1/tickets/" + id(ticket) + "/transitions", lead, closeAsSpam))
        .hasStatusOk();

    String metrics = scrape();

    assertThat(metrics)
        .containsPattern("ticket_creations_total\\{[^}]*source=\"MANUAL\"[^}]*\\} [1-9]")
        .containsPattern(
            "ticket_transitions_total\\{[^}]*from=\"OPEN\"[^}]*to=\"CLOSED\"[^}]*\\} [1-9]")
        .contains("application=\"untangle\"");
  }

  @Test
  void rejectedWebhooksAreCountedByOutcome() {
    assertThat(
            mvc.post()
                .uri("/api/v1/webhooks/partners/tickets")
                .contentType("application/json")
                .content("{}")
                .exchange())
        .hasStatus(401);

    assertThat(scrape())
        .containsPattern("webhook_requests_total\\{[^}]*outcome=\"REJECTED_[A-Z_]+\"[^}]*\\} [1-9]")
        .contains("webhook_processing_seconds_count");
  }

  @Test
  void probesAreOnThePublicPathsTheContainersUse() {
    assertThat(mvc.get().uri("/readyz").exchange()).hasStatusOk();
    assertThat(mvc.get().uri("/livez").exchange()).hasStatusOk();
  }
}
