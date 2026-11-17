package dev.untangle.ticket.application;

import dev.untangle.comment.application.Timeline;
import dev.untangle.ticket.application.TicketEvents.TicketChanged;
import dev.untangle.ticket.application.TicketEvents.TicketEscalated;
import dev.untangle.ticket.domain.SlaPolicy;
import dev.untangle.ticket.domain.Ticket;
import dev.untangle.ticket.domain.TicketStatus;
import dev.untangle.ticket.infrastructure.TicketRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises the escalation level of tickets that breached their SLA (§5.4, R4).
 *
 * <p>Level n is due once {@code n × target(priority)} has passed since the SLA clock start: level 1
 * at the deadline, level 2 at twice the target, and so on, capped at {@code maxLevel}.
 *
 * <p><b>Idempotent:</b> a ticket is only touched when its due level is higher than its current
 * level ({@link Ticket#escalateToAtLeast}), so running twice (or on two instances) never escalates
 * twice. Candidate rows are locked ({@code SELECT ... FOR UPDATE}), so a concurrent run waits and
 * then sees the raised level.
 */
@Service
public class SlaBreachProcessor {

  private static final Logger log = LoggerFactory.getLogger(SlaBreachProcessor.class);
  private static final int PAGE = 200;
  private static final int MAX_PER_RUN = 5_000;

  private final TicketRepository tickets;
  private final Timeline timeline;
  private final SlaPolicy slaPolicy;
  private final ApplicationEventPublisher events;

  public SlaBreachProcessor(
      TicketRepository tickets,
      Timeline timeline,
      SlaPolicy slaPolicy,
      ApplicationEventPublisher events) {
    this.tickets = tickets;
    this.timeline = timeline;
    this.slaPolicy = slaPolicy;
    this.events = events;
  }

  /**
   * @return how many tickets were escalated in this run
   */
  @Transactional
  public int escalateBreaches(Instant now, int maxLevel) {
    int escalated = 0;
    int scanned = 0;
    for (int page = 0; scanned < MAX_PER_RUN; page++) {
      List<Ticket> batch =
          tickets.lockBreachCandidates(TicketStatus.UNRESOLVED, now, PageRequest.of(page, PAGE));
      for (Ticket ticket : batch) {
        int due = dueLevel(ticket, now, maxLevel);
        if (ticket.escalateToAtLeast(due)) {
          record(ticket, due);
          escalated++;
        }
      }
      scanned += batch.size();
      if (batch.size() < PAGE) {
        break;
      }
    }
    if (escalated > 0) {
      log.info("SLA scan escalated {} ticket(s) (scanned {})", escalated, scanned);
    }
    return escalated;
  }

  /** Visible for tests: which level a ticket should be at, now. */
  int dueLevel(Ticket ticket, Instant now, int maxLevel) {
    Duration target = slaPolicy.targetFor(ticket.getPriority());
    long elapsedTargets =
        Duration.between(ticket.getSlaStartedAt(), now).toMillis() / target.toMillis();
    return (int) Math.min(maxLevel, elapsedTargets);
  }

  private void record(Ticket ticket, int level) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("event", "SLA_BREACH");
    metadata.put("level", level);
    metadata.put("slaDueAt", ticket.getSlaDueAt().toString());
    metadata.put("automatic", true);
    timeline.system(ticket, null, "SLA breached: escalated to level " + level, metadata);
    UUID assignee = ticket.getAssignedAgent() == null ? null : ticket.getAssignedAgent().getId();
    events.publishEvent(
        new TicketEscalated(
            ticket.getId(), ticket.getTicketNumber(), level, assignee, null, true, null));
    events.publishEvent(new TicketChanged(ticket.getId()));
  }
}
