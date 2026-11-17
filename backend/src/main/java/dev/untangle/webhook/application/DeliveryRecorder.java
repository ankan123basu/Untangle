package dev.untangle.webhook.application;

import dev.untangle.ticket.domain.Ticket;
import dev.untangle.webhook.domain.DeliveryOutcome;
import dev.untangle.webhook.domain.Partner;
import dev.untangle.webhook.domain.WebhookDelivery;
import dev.untangle.webhook.infrastructure.WebhookDeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one {@code webhook_deliveries} row per attempt (§7.2 step 9), in its <b>own</b>
 * transaction, so the audit row survives even when the request itself fails and rolls back. Admins
 * see the precise reason here; the partner only ever got a generic 401.
 */
@Service
public class DeliveryRecorder {

  private static final Logger log = LoggerFactory.getLogger(DeliveryRecorder.class);
  private static final int MAX = 128;

  private final WebhookDeliveryRepository deliveries;
  private final MeterRegistry meters;

  public DeliveryRecorder(WebhookDeliveryRepository deliveries, MeterRegistry meters) {
    this.deliveries = deliveries;
    this.meters = meters;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      String partnerCodeRaw,
      Partner partner,
      String eventId,
      DeliveryOutcome outcome,
      Ticket ticket,
      String reason,
      String sourceIp,
      String payloadSha256) {
    deliveries.save(
        WebhookDelivery.builder()
            .partner(partner)
            .partnerCodeRaw(truncate(partnerCodeRaw, MAX))
            .eventId(truncate(eventId, MAX))
            .outcome(outcome)
            .ticket(ticket)
            .reason(truncate(reason, 500))
            .sourceIp(truncate(sourceIp, 45))
            .payloadSha256(payloadSha256)
            .build());
    // webhook_requests_total{outcome}: every exit of the pipeline passes through here (§7.2).
    meters.counter("webhook.requests", "outcome", outcome.name()).increment();
    if (outcome == DeliveryOutcome.CREATED || outcome == DeliveryOutcome.DUPLICATE) {
      log.info("Webhook {} partner={} event={}", outcome, partnerCodeRaw, eventId);
    } else {
      log.warn("Webhook rejected outcome={} partner={} reason={}", outcome, partnerCodeRaw, reason);
    }
  }

  private static String truncate(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }
}
