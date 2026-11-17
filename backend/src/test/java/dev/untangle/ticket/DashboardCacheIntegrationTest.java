package dev.untangle.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * §15: the dashboard summary is cached (30 s) but never shows stale numbers after a change made
 * through the application, because every committed ticket change evicts it.
 */
class DashboardCacheIntegrationTest extends IntegrationTest {

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  private int unresolved(String bearer) {
    return json(get("/api/v1/dashboard/summary", bearer), "$.unresolved");
  }

  @Test
  void summaryIsServedFromCacheUntilATicketChanges() {
    String lead = bearer(Role.LEAD);
    createTicket(lead);
    assertThat(unresolved(lead)).isEqualTo(1);

    // A row written behind the application's back (no event) is not seen: proof of the cache.
    jdbc.update("UPDATE tickets SET status = 'CLOSED', closed_at = now(), version = version + 1");
    assertThat(unresolved(lead)).isEqualTo(1);

    // Changes through the application commit, publish TicketChanged and evict the cache:
    // the real count is now 2 (the first ticket was closed behind our back), the stale one was 1.
    createTicket(lead);
    createTicket(lead);
    assertThat(unresolved(lead)).isEqualTo(2);
  }

  @Test
  void myOpenIsPerAgent() {
    var owner = createAgent(Role.AGENT);
    String ownerBearer = bearer(owner);
    String otherBearer = bearer(Role.AGENT);
    String toSelf = VALID_TICKET.replace("}", ",\"assigneeId\":\"" + owner.getId() + "\"}");
    assertThat(post("/api/v1/tickets", ownerBearer, toSelf)).hasStatus(201);

    assertThat((int) json(get("/api/v1/dashboard/summary", ownerBearer), "$.myOpen")).isEqualTo(1);
    assertThat((int) json(get("/api/v1/dashboard/summary", otherBearer), "$.myOpen")).isEqualTo(0);
  }
}
