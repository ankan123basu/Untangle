package dev.untangle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Phase 0 smoke test: the whole Spring context starts and the health probes that Docker relies on
 * are reachable. If this fails, nothing else can work.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UntangleApplicationTests {

  @Autowired private MockMvcTester mvc;

  @Test
  void contextLoads() {
    assertThat(mvc).isNotNull();
  }

  @Test
  void livenessProbeIsUp() {
    assertThat(mvc.get().uri("/actuator/health/liveness"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.status")
        .isEqualTo("UP");
  }

  @Test
  void readinessProbeIsUp() {
    assertThat(mvc.get().uri("/actuator/health/readiness"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.status")
        .isEqualTo("UP");
  }

  @Test
  void errorResponsesNeverExposeStackTraces() {
    assertThat(mvc.get().uri("/this-route-does-not-exist"))
        .hasStatus4xxClientError()
        .bodyText()
        .doesNotContain("Exception")
        .doesNotContain("at dev.untangle");
  }
}
