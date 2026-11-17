package dev.untangle.escalation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.untangle.ticket.application.SlaBreachProcessor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The scheduled trigger (disabled in integration tests, which call the processor directly). */
class SlaEscalationSchedulerTest {

  @Test
  void eachRunScansAtTheCurrentInstantWithTheConfiguredMaximumLevel() {
    SlaBreachProcessor processor = mock(SlaBreachProcessor.class);
    Instant now = Instant.parse("2026-11-18T10:15:00Z");
    var scheduler =
        new SlaEscalationScheduler(
            processor, new EscalationProperties(true, null, 2), Clock.fixed(now, ZoneOffset.UTC));

    scheduler.run();

    verify(processor).escalateBreaches(now, 2);
  }

  @Test
  void propertiesFallBackToSafeDefaults() {
    EscalationProperties defaults = new EscalationProperties(true, " ", 0);
    assertThat(defaults.cron()).isEqualTo("0 * * * * *");
    assertThat(defaults.maxLevel()).isEqualTo(3);

    EscalationProperties custom = new EscalationProperties(false, "0 */5 * * * *", 5);
    assertThat(custom.cron()).isEqualTo("0 */5 * * * *");
    assertThat(custom.maxLevel()).isEqualTo(5);
  }
}
