package dev.untangle.webhook.infrastructure;

import dev.untangle.webhook.domain.DeliveryOutcome;
import dev.untangle.webhook.domain.WebhookDelivery;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface WebhookDeliveryRepository
    extends JpaRepository<WebhookDelivery, UUID>, JpaSpecificationExecutor<WebhookDelivery> {

  /** The delivery that originally created a ticket (to compare payload fingerprints, R3). */
  Optional<WebhookDelivery> findFirstByTicketIdAndOutcome(UUID ticketId, DeliveryOutcome outcome);

  /** Admin log: partner and ticket are shown, so load them in the same query. */
  @Override
  @EntityGraph(attributePaths = {"partner", "ticket"})
  Page<WebhookDelivery> findAll(Specification<WebhookDelivery> spec, Pageable pageable);

  /** 30-day retention job (Section 8). One set-based DELETE, not a load-then-delete loop. */
  @Modifying
  @Query("delete from WebhookDelivery d where d.receivedAt < :cutoff")
  int deleteReceivedBefore(Instant cutoff);
}
