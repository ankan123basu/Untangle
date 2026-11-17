package dev.untangle.escalation.application;

import dev.untangle.ticket.application.SlaBreachProcessor;
import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the SLA scan every minute on exactly one instance (who benefits: leads see breaches at once,
 * so nothing silently rots).
 *
 * <p>{@code lockAtMostFor}: if an instance dies mid-run, the lock frees itself after 5 minutes.
 * {@code lockAtLeastFor}: keeps the lock for 20 s even after a fast run, so another instance whose
 * clock fires a moment later doesn't run the same minute again.
 */
@Component
@EnableConfigurationProperties(EscalationProperties.class)
@ConditionalOnProperty(
    name = "app.escalation.scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
class SlaEscalationScheduler {

  private final SlaBreachProcessor processor;
  private final EscalationProperties properties;
  private final Clock clock;

  SlaEscalationScheduler(
      SlaBreachProcessor processor, EscalationProperties properties, Clock clock) {
    this.processor = processor;
    this.properties = properties;
    this.clock = clock;
  }

  @Scheduled(cron = "${app.escalation.cron:0 * * * * *}")
  @SchedulerLock(name = "sla-escalation", lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")
  void run() {
    processor.escalateBreaches(clock.instant(), properties.maxLevel());
  }
}
