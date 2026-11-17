package dev.untangle.ticket.application;

import static dev.untangle.ticket.infrastructure.TicketSpecifications.*;

import dev.untangle.agent.domain.Role;
import dev.untangle.ticket.application.DashboardCounts.TeamCounts;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.TicketStatus;
import dev.untangle.ticket.infrastructure.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
  private final DashboardCounts counts;

  public TicketStatistics(TicketRepository tickets, Clock clock, DashboardCounts counts) {
    this.tickets = tickets;
    this.clock = clock;
    this.counts = counts;
  }

  /** Dashboard summary: cached team counts plus this agent's open tickets (§15, 30 s TTL). */
  public Summary summary(UUID currentAgentId) {
    TeamCounts team = counts.team();
    return new Summary(
        team.unresolved(),
        team.byStatus(),
        team.unresolvedByPriority(),
        team.slaBreached(),
        team.escalated(),
        team.unassigned(),
        counts.myOpen(currentAgentId),
        team.generatedAt());
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
