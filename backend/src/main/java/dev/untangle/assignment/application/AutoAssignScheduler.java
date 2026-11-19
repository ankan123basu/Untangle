package dev.untangle.assignment.application;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps the waiting queue every 30 seconds on exactly one instance (ShedLock), when auto-assign is
 * on. Catches what the on-create trigger couldn't place: tickets released or handed back, and
 * tickets that waited because everyone was at the cap.
 */
@Component
@EnableConfigurationProperties(AutoAssignProperties.class)
@ConditionalOnProperty(
    name = "app.assignment.scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
class AutoAssignScheduler {

  private final AutoAssigner assigner;
  private final Clock clock;

  AutoAssignScheduler(AutoAssigner assigner, Clock clock) {
    this.assigner = assigner;
    this.clock = clock;
  }

  @Scheduled(cron = "${app.assignment.cron:*/30 * * * * *}")
  @SchedulerLock(name = "auto-assign", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
  void run() {
    if (assigner.isEnabled()) {
      assigner.assignWaiting(clock.instant());
    }
  }
}
