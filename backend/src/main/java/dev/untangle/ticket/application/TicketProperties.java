package dev.untangle.ticket.application;

import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.SlaPolicy;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.tickets.*}. SLA targets per priority (§5.4) and the configurable close rule (§6.2).
 */
@ConfigurationProperties("app.tickets")
public record TicketProperties(boolean agentCanCloseOwnResolved, Map<Priority, Duration> sla) {

  public TicketProperties {
    sla = sla == null || sla.isEmpty() ? Map.of() : new EnumMap<>(sla);
  }

  public SlaPolicy slaPolicy() {
    return sla.isEmpty() ? SlaPolicy.defaults() : new SlaPolicy(sla);
  }
}
