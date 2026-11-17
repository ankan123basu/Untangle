package dev.untangle.ticket.application;

import dev.untangle.ticket.domain.TicketStatus;
import dev.untangle.ticket.infrastructure.TicketRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ticket facts other features ask about (e.g. agent deactivation). */
@Service
public class TicketWorkloadQueries {

  private final TicketRepository tickets;

  public TicketWorkloadQueries(TicketRepository tickets) {
    this.tickets = tickets;
  }

  @Transactional(readOnly = true)
  public boolean hasUnresolvedTicketsAssignedTo(UUID agentId) {
    return tickets.existsByAssignedAgentIdAndStatusIn(agentId, TicketStatus.UNRESOLVED);
  }
}
