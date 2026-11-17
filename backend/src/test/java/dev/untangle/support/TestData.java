package dev.untangle.support;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.Ticket;
import dev.untangle.webhook.domain.Partner;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Small factories for valid test entities. Each call produces unique emails/codes. */
public final class TestData {

  private TestData() {}

  public static Agent agent(Role role) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return new Agent("Test " + role, "test." + unique + "@untangle.test", "{noop}x", role);
  }

  public static Partner partner() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return new Partner("partner-" + unique, "Partner " + unique, "ciphertext");
  }

  public static Ticket manualTicket(Agent creator) {
    return Ticket.manual(
        "Card blocked abroad",
        "My card was blocked while travelling and I cannot pay.",
        "Rahul Sharma",
        "rahul@example.com",
        Category.ACCOUNT,
        Priority.HIGH,
        creator,
        Instant.now(),
        Instant.now().plus(4, ChronoUnit.HOURS));
  }

  public static Ticket webhookTicket(Partner partner, String eventId) {
    return Ticket.fromWebhook(
        "Refund not received",
        "I cancelled my order 7 days ago and the refund has not arrived.",
        "Priya Nair",
        null,
        Category.REFUND,
        Priority.MEDIUM,
        partner,
        eventId,
        Instant.now(),
        Instant.now().plus(24, ChronoUnit.HOURS));
  }
}
