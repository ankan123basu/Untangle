package dev.untangle.ticket.application;

import static dev.untangle.ticket.infrastructure.TicketSpecifications.assignedTo;
import static dev.untangle.ticket.infrastructure.TicketSpecifications.escalated;
import static dev.untangle.ticket.infrastructure.TicketSpecifications.slaBreached;
import static dev.untangle.ticket.infrastructure.TicketSpecifications.unassigned;
import static dev.untangle.ticket.infrastructure.TicketSpecifications.unresolved;

import dev.untangle.ticket.application.TicketEvents.TicketChanged;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.TicketStatus;
import dev.untangle.ticket.infrastructure.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The dashboard's numbers, cached for {@value #TTL_SECONDS} s (§15) and dropped as soon as any
 * ticket change commits. The team-wide counts are one shared entry; "my open tickets" is cached per
 * agent. Backed by Caffeine, or by Redis under the {@code redis} profile so all instances share it
 * (see {@code ScalingConfig}).
 *
 * <p>Only the SLA-breached count can go stale without an event (a deadline simply passes); it is at
 * most {@value #TTL_SECONDS} s behind.
 */
@Component
public class DashboardCounts {

  public static final String CACHE_MANAGER = "dashboardCacheManager";
  public static final String TEAM = "dashboardTeam";
  public static final String MINE = "dashboardMine";
  public static final int TTL_SECONDS = 30;

  /** Everything on the dashboard except the per-agent number. */
  public record TeamCounts(
      long unresolved,
      Map<TicketStatus, Long> byStatus,
      Map<Priority, Long> unresolvedByPriority,
      long slaBreached,
      long escalated,
      long unassigned,
      Instant generatedAt) {}

  private final TicketRepository tickets;
  private final Clock clock;

  public DashboardCounts(TicketRepository tickets, Clock clock) {
    this.tickets = tickets;
    this.clock = clock;
  }

  @Cacheable(cacheManager = CACHE_MANAGER, cacheNames = TEAM, key = "'team'")
  @Transactional(readOnly = true)
  public TeamCounts team() {
    Instant now = clock.instant();

    Map<TicketStatus, Long> byStatus = new EnumMap<>(TicketStatus.class);
    for (TicketStatus status : TicketStatus.values()) byStatus.put(status, 0L);
    tickets.countByStatus().forEach(row -> byStatus.put(row.getStatus(), row.getCount()));

    Map<Priority, Long> byPriority = new EnumMap<>(Priority.class);
    for (Priority priority : Priority.values()) byPriority.put(priority, 0L);
    tickets
        .countByPriorityForStatuses(TicketStatus.UNRESOLVED)
        .forEach(row -> byPriority.put(row.getPriority(), row.getCount()));

    long open = byStatus.get(TicketStatus.OPEN) + byStatus.get(TicketStatus.IN_PROGRESS);
    return new TeamCounts(
        open,
        byStatus,
        byPriority,
        tickets.count(slaBreached(now)),
        tickets.count(unresolved().and(escalated())),
        tickets.count(unresolved().and(unassigned())),
        now);
  }

  @Cacheable(cacheManager = CACHE_MANAGER, cacheNames = MINE, key = "#agentId")
  @Transactional(readOnly = true)
  public Long myOpen(UUID agentId) {
    return tickets.count(unresolved().and(assignedTo(agentId)));
  }

  /**
   * Any committed ticket change can move any number, so drop everything. {@code beforeInvocation}
   * makes Spring use {@code Cache.invalidate()}, which is immediate; a plain {@code clear()} may be
   * deferred (Redis clears asynchronously), so the next request could still see old numbers.
   */
  @TransactionalEventListener(fallbackExecution = true)
  @CacheEvict(
      cacheManager = CACHE_MANAGER,
      cacheNames = {TEAM, MINE},
      allEntries = true,
      beforeInvocation = true)
  public void onTicketChanged(TicketChanged event) {
    // eviction only
  }
}
