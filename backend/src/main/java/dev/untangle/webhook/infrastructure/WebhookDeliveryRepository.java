package dev.untangle.webhook.infrastructure;

import dev.untangle.webhook.domain.WebhookDelivery;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface WebhookDeliveryRepository
    extends JpaRepository<WebhookDelivery, UUID>, JpaSpecificationExecutor<WebhookDelivery> {

  /** 30-day retention job (Section 8). One set-based DELETE, not a load-then-delete loop. */
  @Modifying
  @Query("delete from WebhookDelivery d where d.receivedAt < :cutoff")
  int deleteReceivedBefore(Instant cutoff);
}
