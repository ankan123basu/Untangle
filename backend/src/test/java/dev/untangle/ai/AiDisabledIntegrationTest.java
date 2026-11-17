package dev.untangle.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** §11 "AI: missing key → feature disabled". The rest of the app is unaffected. */
class AiDisabledIntegrationTest extends IntegrationTest {

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  @Test
  void withoutAKeyAiIsReportedDisabledAndEndpointsReturn503() {
    String bearer = bearer(Role.AGENT);

    assertThat(get("/api/v1/ai/status", bearer))
        .bodyJson()
        .extractingPath("$.enabled")
        .isEqualTo(false);
    MvcTestResult triage =
        post(
            "/api/v1/ai/triage",
            bearer,
            "{\"subject\":\"Refund\",\"description\":\"Refund missing\"}");
    assertThat(triage).hasStatus(503);
    assertThat(triage).bodyJson().extractingPath("$.code").isEqualTo("AI_UNAVAILABLE");

    assertThat(createTicket(bearer)).as("core features work without AI").hasStatus(201);
  }
}
