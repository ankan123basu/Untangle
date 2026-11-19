package dev.untangle.assignment.application;

import dev.untangle.agent.application.AgentService;
import dev.untangle.agent.application.AgentViews.AgentOption;
import dev.untangle.agent.domain.Role;
import dev.untangle.assignment.application.AutoAssignViews.AutoAssignRun;
import dev.untangle.assignment.domain.AgentRouting;
import dev.untangle.assignment.domain.AssignmentPlanner;
import dev.untangle.assignment.domain.AssignmentPlanner.Candidate;
import dev.untangle.assignment.domain.AssignmentPlanner.Job;
import dev.untangle.assignment.domain.AssignmentPlanner.Pick;
import dev.untangle.assignment.domain.AutoAssignSettings;
import dev.untangle.assignment.infrastructure.AgentRoutingRepository;
import dev.untangle.assignment.infrastructure.AutoAssignSettingsRepository;
import dev.untangle.ticket.application.TicketQueue;
import dev.untangle.ticket.application.TicketQueue.QueuedTicket;
import dev.untangle.ticket.application.TicketQueue.Workload;
import dev.untangle.ticket.application.TicketService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs automatic assignment (ADR-029): builds the {@link AssignmentPlanner} from who takes work and
 * their current load, walks the waiting queue most urgent first, and assigns each ticket through
 * {@link TicketService#autoAssign} (timeline, notification and events as for any assignment).
 *
 * <p>Safe to run concurrently: queue rows are locked {@code FOR UPDATE SKIP LOCKED}, so two runs
 * never take the same ticket. The per-agent cap is a soft limit if a lead assigns by hand while a
 * run is in progress (off by at most the tickets of that one run).
 */
@Service
public class AutoAssigner {

  private static final Logger log = LoggerFactory.getLogger(AutoAssigner.class);
  static final int PAGE = 200;
  static final int MAX_SCANNED_PER_RUN = 10_000;

  private final TicketQueue queue;
  private final TicketService tickets;
  private final AgentService agents;
  private final AgentRoutingRepository routing;
  private final AutoAssignSettingsRepository settings;

  public AutoAssigner(
      TicketQueue queue,
      TicketService tickets,
      AgentService agents,
      AgentRoutingRepository routing,
      AutoAssignSettingsRepository settings) {
    this.queue = queue;
    this.tickets = tickets;
    this.agents = agents;
    this.routing = routing;
    this.settings = settings;
  }

  @Transactional(readOnly = true)
  public boolean isEnabled() {
    return currentSettings().isEnabled();
  }

  /** Assigns as much of the waiting queue as agents have room for, most urgent SLA first. */
  @Transactional
  public AutoAssignRun assignWaiting(Instant now) {
    AssignmentPlanner planner = planner();
    int assigned = 0;
    int scanned = 0;
    QueuedTicket cursor = null;
    while (planner.hasCapacity() && scanned < MAX_SCANNED_PER_RUN) {
      List<QueuedTicket> batch = queue.lockNext(PAGE, cursor);
      for (QueuedTicket ticket : batch) {
        if (!planner.hasCapacity()) {
          break;
        }
        if (assign(planner, ticket, now)) {
          assigned++;
        }
      }
      scanned += batch.size();
      if (batch.size() < PAGE) {
        break;
      }
      cursor = batch.getLast();
    }
    long waiting = queue.waitingCount();
    if (assigned > 0) {
      log.info("Auto-assign run assigned={} scanned={} waiting={}", assigned, scanned, waiting);
    }
    return new AutoAssignRun(assigned, waiting);
  }

  /**
   * Assigns one new ticket straight away (called after it is created). Runs in its own transaction
   * because the creating transaction has already committed.
   *
   * @return whether it was assigned (if not, it waits for the next run)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean assignNew(UUID ticketId, Instant now) {
    if (!currentSettings().isEnabled()) {
      return false;
    }
    Optional<QueuedTicket> ticket = queue.lockIfWaiting(ticketId);
    return ticket.isPresent() && assign(planner(), ticket.get(), now);
  }

  private boolean assign(AssignmentPlanner planner, QueuedTicket ticket, Instant now) {
    Job job = new Job(ticket.id(), ticket.category(), ticket.priority(), ticket.releasedById());
    Optional<Pick> pick = planner.assign(job);
    if (pick.isEmpty()) {
      return false;
    }
    UUID agentId = pick.get().agentId();
    if (!tickets.autoAssign(ticket.id(), agentId, pick.get().reason())) {
      return false;
    }
    // Picked means they take work: with no row yet that is the default, so the new row says true.
    routing.markAutoAssigned(agentId, true, now);
    return true;
  }

  /** Who takes automatic work right now, with their load. */
  AssignmentPlanner planner() {
    Map<UUID, AgentRouting> configured =
        routing.findAll().stream()
            .collect(Collectors.toMap(AgentRouting::getAgentId, Function.identity()));
    Map<UUID, Workload> loads = queue.workloads();
    List<Candidate> candidates = new ArrayList<>();
    for (AgentOption agent : agents.listActiveOptions()) {
      AgentRouting r = configured.get(agent.id());
      boolean takesWork =
          r == null
              ? AgentRouting.defaultAutoAssign(agent.role() == Role.AGENT, agent.ai())
              : r.isAutoAssign();
      if (!takesWork) {
        continue;
      }
      Workload load = loads.get(agent.id());
      candidates.add(
          new Candidate(
              agent.id(),
              agent.fullName(),
              agent.ai(),
              r == null ? Set.of() : r.getSkills(),
              load == null ? Map.of() : load.byPriority(),
              r == null ? null : r.getLastAutoAssignedAt()));
    }
    return new AssignmentPlanner(candidates, currentSettings().getMaxOpenPerAgent());
  }

  AutoAssignSettings currentSettings() {
    return settings.findFirstByOrderByCreatedAtAsc().orElseGet(AutoAssignSettings::defaults);
  }
}
