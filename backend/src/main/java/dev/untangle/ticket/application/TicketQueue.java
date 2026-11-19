package dev.untangle.ticket.application;

import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.Ticket;
import dev.untangle.ticket.domain.TicketStatus;
import dev.untangle.ticket.infrastructure.TicketRepository;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The waiting queue as seen by auto-assignment (ADR-029): which tickets are waiting, and how much
 * unresolved work each agent already has. Returns plain records so the assignment feature never
 * touches ticket entities or repositories.
 *
 * <p>The locking reads must run inside the caller's transaction ({@link Propagation#MANDATORY}),
 * because the row locks are what stop two runs from picking the same ticket.
 */
@Service
public class TicketQueue {

  private final TicketRepository tickets;

  public TicketQueue(TicketRepository tickets) {
    this.tickets = tickets;
  }

  /** A waiting ticket. {@code releasedById} is who last gave it back (skip them), or null. */
  public record QueuedTicket(
      UUID id,
      String ticketNumber,
      Category category,
      Priority priority,
      Instant slaDueAt,
      @Nullable UUID releasedById) {

    static QueuedTicket of(Ticket t) {
      return new QueuedTicket(
          t.getId(),
          t.getTicketNumber(),
          t.getCategory(),
          t.getPriority(),
          t.getSlaDueAt(),
          t.getReleasedBy() == null ? null : t.getReleasedBy().getId());
    }
  }

  /** An agent's unresolved tickets, by priority. */
  public record Workload(Map<Priority, Integer> byPriority) {
    public int total() {
      return byPriority.values().stream().mapToInt(Integer::intValue).sum();
    }
  }

  /**
   * Locks the next {@code limit} waiting tickets, most urgent SLA first, after {@code after} (null
   * = from the start). Rows another transaction holds are skipped, not waited for.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<QueuedTicket> lockNext(int limit, @Nullable QueuedTicket after) {
    List<Ticket> batch =
        after == null
            ? tickets.lockQueueHead(limit)
            : tickets.lockQueueAfter(after.slaDueAt(), after.id(), limit);
    return batch.stream().map(QueuedTicket::of).toList();
  }

  /** Locks one ticket if it is still waiting (OPEN and unassigned) and nobody else holds it. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<QueuedTicket> lockIfWaiting(UUID ticketId) {
    return tickets.lockIfWaiting(ticketId).map(QueuedTicket::of);
  }

  @Transactional(readOnly = true)
  public long waitingCount() {
    return tickets.countByStatusAndAssignedAgentIsNull(TicketStatus.OPEN);
  }

  /**
   * Unresolved (OPEN + IN_PROGRESS) tickets per agent and priority. Agents with none are absent.
   */
  @Transactional(readOnly = true)
  public Map<UUID, Workload> workloads() {
    Map<UUID, Map<Priority, Integer>> raw = new HashMap<>();
    for (var row : tickets.countOpenByAgentAndPriority(TicketStatus.UNRESOLVED)) {
      raw.computeIfAbsent(row.getAgentId(), k -> new EnumMap<>(Priority.class))
          .put(row.getPriority(), Math.toIntExact(row.getCount()));
    }
    Map<UUID, Workload> result = new HashMap<>();
    raw.forEach((agent, counts) -> result.put(agent, new Workload(Map.copyOf(counts))));
    return result;
  }
}
