package dev.untangle.assignment.application;

import dev.untangle.ticket.application.TicketEvents.TicketCreated;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Assigns a new ticket as soon as it is created (when auto-assign is on). If that fails or nobody
 * has room, the ticket simply waits for the next sweep ({@link AutoAssignScheduler}); creating the
 * ticket never fails because of auto-assignment.
 */
@Component
class AutoAssignTriggers {

  private static final Logger log = LoggerFactory.getLogger(AutoAssignTriggers.class);

  private final AutoAssigner assigner;
  private final Clock clock;

  AutoAssignTriggers(AutoAssigner assigner, Clock clock) {
    this.assigner = assigner;
    this.clock = clock;
  }

  @TransactionalEventListener
  void onCreated(TicketCreated event) {
    try {
      assigner.assignNew(event.ticketId(), clock.instant());
    } catch (RuntimeException e) {
      log.warn(
          "Auto-assign of new ticket {} failed; it waits for the next sweep: {}",
          event.ticketNumber(),
          e.getClass().getSimpleName());
    }
  }
}
