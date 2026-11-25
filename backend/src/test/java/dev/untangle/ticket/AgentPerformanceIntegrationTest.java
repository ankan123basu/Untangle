package dev.untangle.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Agent performance stats (ADR-030): resolution facts and the leads' report. */
class AgentPerformanceIntegrationTest extends IntegrationTest {

  private Agent lead;
  private String leadBearer;

  @BeforeEach
  void setUp() {
    lead = createAgent(Role.LEAD);
    leadBearer = bearer(lead);
  }

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  // ---- helpers ------------------------------------------------------------------------------

  private MvcTestResult assign(String ticketId, Agent to, long version) {
    var result =
        call(
            HttpMethod.PUT,
            "/api/v1/tickets/" + ticketId + "/assignee",
            leadBearer,
            "{\"agentId\":\"%s\",\"version\":%d}".formatted(to.getId(), version));
    assertThat(result).hasStatusOk();
    return result;
  }

  private MvcTestResult move(String bearer, String ticketId, String to, long version) {
    String extra =
        switch (to) {
          case "RESOLVED" -> ",\"note\":\"Refund reprocessed and confirmed by the customer.\"";
          case "IN_PROGRESS", "CLOSED" -> ",\"reason\":\"Customer says it is still broken\"";
          default -> "";
        };
    var result =
        post(
            "/api/v1/tickets/" + ticketId + "/transitions",
            bearer,
            "{\"targetStatus\":\"%s\",\"version\":%d%s}".formatted(to, version, extra));
    assertThat(result).hasStatusOk();
    return result;
  }

  /** Creates a ticket, gives it to {@code agent}, who starts and resolves it. */
  private String resolvedBy(Agent agent, String resolverBearer) {
    var ticket = createTicket(leadBearer);
    String id = id(ticket);
    var assigned = assign(id, agent, version(ticket));
    var started = move(bearer(agent), id, "IN_PROGRESS", version(assigned));
    move(resolverBearer, id, "RESOLVED", version(started));
    return id;
  }

  private List<Map<String, Object>> resolutions(String ticketId) {
    return jdbc.queryForList(
        "SELECT * FROM ticket_resolutions WHERE ticket_id = ?::uuid ORDER BY resolved_at",
        ticketId);
  }

  private Map<String, Object> row(MvcTestResult report, Agent agent) {
    List<Map<String, Object>> rows =
        json(report, "$.agents[?(@.agentId == '" + agent.getId() + "')]");
    assertThat(rows).as("row for " + agent.getFullName()).hasSize(1);
    return rows.getFirst();
  }

  /** Inserts a resolution fact directly: handled for {@code handle}, opened {@code wait} before. */
  private void fact(Agent agent, Duration ago, Duration handle, Duration wait, boolean metSla) {
    String ticketId = id(createTicket(leadBearer));
    Instant resolved = Instant.now().minus(ago);
    jdbc.update(
        """
        INSERT INTO ticket_resolutions (ticket_id, agent_id, priority, category, opened_at,
                                        assigned_at, resolved_at, sla_due_at)
        VALUES (?::uuid, ?, 'HIGH', 'BILLING', ?, ?, ?, ?)
        """,
        ticketId,
        agent.getId(),
        Timestamp.from(resolved.minus(wait)),
        Timestamp.from(resolved.minus(handle)),
        Timestamp.from(resolved),
        Timestamp.from(metSla ? resolved.plusSeconds(60) : resolved.minusSeconds(60)));
  }

  // ---- recording ----------------------------------------------------------------------------

  @Test
  void resolvingRecordsOneFactCreditedToTheAssignee() {
    Agent agent = createAgent(Role.AGENT);
    String id = resolvedBy(agent, bearer(agent));

    var rows = resolutions(id);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst())
        .containsEntry("agent_id", agent.getId())
        .containsEntry("resolved_by_agent_id", agent.getId())
        .containsEntry("priority", "HIGH")
        .containsEntry("reopened_at", null);
    assertThat(
            jdbc.queryForObject(
                "SELECT assigned_at IS NOT NULL FROM tickets WHERE id = ?::uuid",
                Boolean.class,
                id))
        .isTrue();
  }

  @Test
  void aLeadResolvingOnSomeonesBehalfCreditsTheAssignee() {
    Agent agent = createAgent(Role.AGENT);
    String id = resolvedBy(agent, leadBearer);

    assertThat(resolutions(id).getFirst())
        .containsEntry("agent_id", agent.getId())
        .containsEntry("resolved_by_agent_id", lead.getId());
  }

  @Test
  void aResolutionThatDoesNotStickIsMarkedAndANewOneIsRecorded() {
    Agent agent = createAgent(Role.AGENT);
    String agentBearer = bearer(agent);
    String id = resolvedBy(agent, agentBearer);

    // Sent back to work, then resolved again.
    var back =
        move(leadBearer, id, "IN_PROGRESS", version(get("/api/v1/tickets/" + id, leadBearer)));
    move(agentBearer, id, "RESOLVED", version(back));

    // Closed, reopened: the second resolution doesn't stick either.
    var closed = move(leadBearer, id, "CLOSED", version(get("/api/v1/tickets/" + id, leadBearer)));
    var reopened =
        post(
            "/api/v1/tickets/" + id + "/reopen",
            leadBearer,
            "{\"reason\":\"Customer wrote back\",\"version\":%d}".formatted(version(closed)));
    assertThat(reopened).hasStatusOk();

    var rows = resolutions(id);
    assertThat(rows).hasSize(2).allSatisfy(r -> assertThat(r.get("reopened_at")).isNotNull());

    var report = get("/api/v1/dashboard/agent-performance", leadBearer);
    assertThat(row(report, agent))
        .extractingByKey("metrics", InstanceOfAssertFactories.MAP)
        .containsEntry("resolved", 2)
        .containsEntry("reopenPercent", 100);
  }

  // ---- report -------------------------------------------------------------------------------

  @Test
  void reportsMediansAverageSlaAndOnlyCountsThePeriod() {
    Agent fast = createAgent(Role.AGENT);
    Agent idle = createAgent(Role.AGENT);
    Duration h = Duration.ofHours(1);
    fact(fast, Duration.ofDays(1), Duration.ofMinutes(10), h, true);
    fact(fast, Duration.ofDays(2), Duration.ofMinutes(20), h.multipliedBy(2), true);
    fact(fast, Duration.ofDays(3), Duration.ofMinutes(60), h.multipliedBy(3), false);
    fact(
        fast,
        Duration.ofDays(40),
        Duration.ofMinutes(500),
        h.multipliedBy(10),
        false); // outside 30 days

    var report = get("/api/v1/dashboard/agent-performance?days=30", leadBearer);

    assertThat(report).hasStatusOk();
    assertThat((Integer) json(report, "$.days")).isEqualTo(30);
    assertThat(row(report, fast))
        .containsEntry("ranked", true)
        .containsEntry("ai", false)
        .extractingByKey("metrics", InstanceOfAssertFactories.MAP)
        .containsEntry("resolved", 3)
        .containsEntry("medianHandleSeconds", 1200)
        .containsEntry("avgHandleSeconds", 1800)
        .containsEntry("medianResolutionSeconds", 7200)
        .containsEntry("slaMetPercent", 67)
        .containsEntry("reopenPercent", 0);
    assertThat(row(report, idle))
        .containsEntry("ranked", false)
        .extractingByKey("metrics", InstanceOfAssertFactories.MAP)
        .containsEntry("resolved", 0)
        .containsEntry("medianHandleSeconds", null)
        .containsEntry("slaMetPercent", null);
    assertThat((Integer) json(report, "$.team.resolved")).isEqualTo(3);

    var wider = get("/api/v1/dashboard/agent-performance?days=90", leadBearer);
    assertThat((Integer) json(wider, "$.team.resolved")).isEqualTo(4);
  }

  @Test
  void leadsWithoutResolutionsAreLeftOutButAppearOnceTheyResolve() {
    Agent otherLead = createAgent(Role.LEAD);
    var before = get("/api/v1/dashboard/agent-performance", leadBearer);
    assertThat((List<?>) json(before, "$.agents[?(@.agentId == '" + otherLead.getId() + "')]"))
        .isEmpty();

    fact(otherLead, Duration.ofHours(1), Duration.ofMinutes(5), Duration.ofMinutes(30), true);
    var after = get("/api/v1/dashboard/agent-performance", leadBearer);
    assertThat(row(after, otherLead)).containsEntry("role", "LEAD");
  }

  @Test
  void onlyLeadsAndAdminsSeeItAndThePeriodIsValidated() {
    assertThat(get("/api/v1/dashboard/agent-performance", bearer(Role.AGENT))).hasStatus(403);
    assertThat(get("/api/v1/dashboard/agent-performance", bearer(Role.ADMIN))).hasStatusOk();
    assertThat(get("/api/v1/dashboard/agent-performance?days=0", leadBearer)).hasStatus(400);
    assertThat(get("/api/v1/dashboard/agent-performance?days=366", leadBearer)).hasStatus(400);
    assertThat(get("/api/v1/dashboard/agent-performance", null)).hasStatus(401);
  }

  @Test
  void theDatabaseRejectsAnImpossibleTimeline() {
    Agent agent = createAgent(Role.AGENT);
    String ticketId = id(createTicket(leadBearer));
    Instant now = Instant.now();
    assertThatThrownBy(() ->
            jdbc.update(
                """
                INSERT INTO ticket_resolutions (ticket_id, agent_id, priority, category,
                    opened_at, assigned_at, resolved_at, sla_due_at)
                VALUES (?::uuid, ?, 'LOW', 'OTHER', ?, ?, ?, ?)
                """,
                ticketId,
                agent.getId(),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(60)), // assigned after resolved
                Timestamp.from(now),
                Timestamp.from(now)))
        .hasMessageContaining("ck_resolutions_order");
  }
}
