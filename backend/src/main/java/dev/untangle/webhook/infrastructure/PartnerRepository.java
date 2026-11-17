package dev.untangle.webhook.infrastructure;

import dev.untangle.webhook.domain.Partner;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerRepository extends JpaRepository<Partner, UUID> {

  /** Webhook step 3: look up the partner named in the {@code X-Partner-Id} header. */
  Optional<Partner> findByCode(String code);
}
