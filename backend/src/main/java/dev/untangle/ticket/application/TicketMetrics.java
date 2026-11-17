package dev.untangle.ticket.application;

import dev.untangle.ticket.application.TicketEvents.TicketCreated;
import dev.untangle.ticket.application.TicketEvents.TicketEscalated;
import dev.untangle.ticket.application.TicketEvents.TicketReopened;
import dev.untangle.ticket.application.TicketEvents.TicketTransitioned;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Business metrics (§16) from ticket events, counted only after commit so a rolled-back change is
 * never counted. Prometheus names: {@code ticket_creations_total{source}} (the client reserves the
 * {@code _created} suffix, so §16's {@code tickets_created_total} isn't possible), {@code
 * ticket_transitions_total{from,to}}, {@code sla_breaches_total{level}}, {@code
 * ticket_escalations_total{mode}}.
 */
@Component
class TicketMetrics {

  private final MeterRegistry registry;

  TicketMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  @TransactionalEventListener(fallbackExecution = true)
  void created(TicketCreated event) {
    registry.counter("ticket.creations", "source", event.source().name()).increment();
  }

  @TransactionalEventListener(fallbackExecution = true)
  void transitioned(TicketTransitioned event) {
    registry.counter("ticket.transitions", "from", event.from(), "to", event.to()).increment();
  }

  @TransactionalEventListener(fallbackExecution = true)
  void reopened(TicketReopened event) {
    registry.counter("ticket.transitions", "from", "CLOSED", "to", "OPEN").increment();
  }

  @TransactionalEventListener(fallbackExecution = true)
  void escalated(TicketEscalated event) {
    registry
        .counter("ticket.escalations", "mode", event.automatic() ? "automatic" : "manual")
        .increment();
    if (event.automatic()) {
      registry.counter("sla.breaches", "level", String.valueOf(event.level())).increment();
    }
  }
}
