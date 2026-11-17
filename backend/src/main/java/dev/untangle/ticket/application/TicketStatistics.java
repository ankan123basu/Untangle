package dev.untangle.ticket.application;

import static dev.untangle.ticket.infrastructure.TicketSpecifications.*;

import dev.untangle.agent.domain.Role;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.TicketStatus;
import dev.untangle.ticket.infrastructure.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates for the dashboard. Counts use the same {@code TicketSpecifications} as the list
 * filters, so every dashboard number equals the total of the matching list view (§11 "counts match
 * the filtered list exactly").
 */
@Service
public class TicketStatistics {

  public record Summary(
      long unresolved,
      Map<TicketStatus, Long> byStatus,
      Map<Priority, Long> unresolvedByPriority,
      long slaBreached,
      long escalated,
      long unassigned,
      long myOpen,
      Instant generatedAt) {}

  public record DayTrend(LocalDate date, long created, long resolved) {}

  public record Workload(
      UUID agentId, String fullName, Role role, long open, long inProgress, long breached) {
    public long total() {
      return open + inProgress;
    }
  }

  private final TicketRepository tickets;
  private final Clock clock;

  public TicketStatistics(TicketRepository tickets, Clock clock) {
    this.tickets = tickets;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Summary summary(UUID currentAgentId) {
    Instant now = clock.instant();

    Map<TicketStatus, Long> byStatus = new EnumMap<>(TicketStatus.class);
    for (TicketStatus status : TicketStatus.values()) byStatus.put(status, 0L);
    tickets.countByStatus().forEach(row -> byStatus.put(row.getStatus(), row.getCount()));

    Map<Priority, Long> byPriority = new EnumMap<>(Priority.class);
    for (Priority priority : Priority.values()) byPriority.put(priority, 0L);
    tickets
        .countByPriorityForStatuses(TicketStatus.UNRESOLVED)
        .forEach(row -> byPriority.put(row.getPriority(), row.getCount()));

    long unresolved = byStatus.get(TicketStatus.OPEN) + byStatus.get(TicketStatus.IN_PROGRESS);
    return new Summary(
        unresolved,
        byStatus,
        byPriority,
        tickets.count(slaBreached(now)),
        tickets.count(unresolved().and(escalated())),
        tickets.count(unresolved().and(unassigned())),
        tickets.count(unresolved().and(assignedTo(currentAgentId))),
        now);
  }

  @Transactional(readOnly = true)
  public List<DayTrend> trends(int days) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    return tickets.dailyTrend(today, days).stream()
        .map(row -> new DayTrend(row.getDay(), row.getCreated(), row.getResolved()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<Workload> workload() {
    return tickets.workload(TicketStatus.UNRESOLVED, clock.instant()).stream()
        .map(row ->
            new Workload(
                row.getAgentId(),
                row.getFullName(),
                row.getRole(),
                row.getOpen(),
                row.getInProgress(),
                row.getBreached()))
        .toList();
  }
}
