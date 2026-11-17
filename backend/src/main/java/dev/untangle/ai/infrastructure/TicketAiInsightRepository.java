package dev.untangle.ai.infrastructure;

import dev.untangle.ai.domain.InsightKind;
import dev.untangle.ai.domain.TicketAiInsight;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketAiInsightRepository extends JpaRepository<TicketAiInsight, UUID> {

  /** The latest summary or draft for a ticket, so the UI can show it without calling the AI. */
  Optional<TicketAiInsight> findFirstByTicketIdAndKindOrderByCreatedAtDesc(
      UUID ticketId, InsightKind kind);
}
