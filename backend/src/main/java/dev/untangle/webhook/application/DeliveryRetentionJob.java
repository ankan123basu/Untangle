package dev.untangle.webhook.application;

import dev.untangle.webhook.infrastructure.WebhookDeliveryRepository;
import java.time.Clock;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes webhook delivery logs older than 30 days (§8: data minimisation), daily, one instance.
 */
@Component
@ConditionalOnProperty(
    name = "app.escalation.scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DeliveryRetentionJob {

  private static final Logger log = LoggerFactory.getLogger(DeliveryRetentionJob.class);

  private final WebhookDeliveryRepository deliveries;
  private final Duration retention;
  private final Clock clock;

  public DeliveryRetentionJob(
      WebhookDeliveryRepository deliveries,
      @Value("${app.webhook.delivery-retention:30d}") Duration retention,
      Clock clock) {
    this.deliveries = deliveries;
    this.retention = retention;
    this.clock = clock;
  }

  @Scheduled(cron = "${app.webhook.retention-cron:0 15 3 * * *}")
  @SchedulerLock(name = "webhook-delivery-retention", lockAtMostFor = "PT30M")
  @Transactional
  public void purge() {
    int deleted = deliveries.deleteReceivedBefore(clock.instant().minus(retention));
    log.info("Webhook delivery retention: deleted {} row(s) older than {}", deleted, retention);
  }
}
