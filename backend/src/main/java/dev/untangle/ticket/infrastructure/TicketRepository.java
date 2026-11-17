package dev.untangle.ticket.infrastructure;

import dev.untangle.ticket.domain.Ticket;
import dev.untangle.ticket.domain.TicketStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Ticket persistence. {@link JpaSpecificationExecutor} powers the dynamic list filters (status,
 * priority, assignee, search...) in Phase 3 with type-safe, injection-proof criteria queries.
 */
public interface TicketRepository
    extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

  Optional<Ticket> findByTicketNumber(String ticketNumber);

  /**
   * Webhook idempotency: after the unique index rejects a duplicate insert, load the original
   * ticket to return it. Uses {@code ux_tickets_partner_event}.
   */
  Optional<Ticket> findByPartnerIdAndExternalEventId(UUID partnerId, String externalEventId);

  long countByStatusIn(Collection<TicketStatus> statuses);

  /** "Deactivate agent with open tickets" rule (409 AGENT_HAS_OPEN_TICKETS). */
  boolean existsByAssignedAgentIdAndStatusIn(UUID agentId, Collection<TicketStatus> statuses);
}
