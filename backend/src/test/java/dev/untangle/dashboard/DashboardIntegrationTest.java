package dev.untangle.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Dashboard numbers (requirement 6, §11 "Dashboard"). */
class DashboardIntegrationTest extends IntegrationTest {

  private Agent riya;
  private String riyaBearer;
  private String leadBearer;

  @BeforeEach
  void signIn() {
    riya = createAgent(Role.AGENT);
    riyaBearer = bearer(riya);
    leadBearer = bearer(Role.LEAD);
  }

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  private long number(MvcTestResult result, String path) {
    return ((Number) json(result, path)).longValue();
  }

  private long listTotal(String query) {
    return number(get("/api/v1/tickets?" + query, riyaBearer), "$.totalElements");
  }

  @Test
  void zeroTicketsMeansZerosNotErrors() {
    MvcTestResult summary = get("/api/v1/dashboard/summary", riyaBearer);

    assertThat(summary).hasStatusOk();
    assertThat(number(summary, "$.unresolved")).isZero();
    assertThat(number(summary, "$.byStatus.CLOSED")).isZero();
    assertThat(number(summary, "$.unresolvedByPriority.CRITICAL")).isZero();
    assertThat(number(summary, "$.slaBreached")).isZero();
  }

  @Test
  void everyCountMatchesTheEquivalentListFilter() {
    for (int i = 0; i < 5; i++) {
      UUID id = UUID.fromString(id(createTicket(leadBearer)));
      switch (i) {
        case 0 ->
            jdbc.update(
                "UPDATE tickets SET sla_due_at = now() - interval '1 hour' WHERE id = ?", id);
        case 1 ->
            jdbc.update(
                "UPDATE tickets SET assigned_agent_id = ?, status = 'IN_PROGRESS', escalation_level = 2 WHERE id = ?",
                riya.getId(),
                id);
        case 2 ->
            jdbc.update("UPDATE tickets SET assigned_agent_id = ? WHERE id = ?", riya.getId(), id);
        case 3 ->
            jdbc.update("UPDATE tickets SET status = 'CLOSED', closed_at = now() WHERE id = ?", id);
        default -> {}
      }
    }

    MvcTestResult s = get("/api/v1/dashboard/summary", riyaBearer);
    String unresolved = "status=OPEN&status=IN_PROGRESS";

    assertThat(number(s, "$.unresolved")).isEqualTo(listTotal(unresolved)).isEqualTo(4);
    assertThat(number(s, "$.slaBreached")).isEqualTo(listTotal("slaBreached=true")).isEqualTo(1);
    assertThat(number(s, "$.escalated"))
        .isEqualTo(listTotal("escalated=true&" + unresolved))
        .isEqualTo(1);
    assertThat(number(s, "$.unassigned"))
        .isEqualTo(listTotal("unassigned=true&" + unresolved))
        .isEqualTo(2);
    assertThat(number(s, "$.myOpen")).isEqualTo(listTotal("mine=true&" + unresolved)).isEqualTo(2);
    assertThat(number(s, "$.byStatus.CLOSED")).isEqualTo(listTotal("status=CLOSED")).isEqualTo(1);
    assertThat(number(s, "$.unresolvedByPriority.HIGH"))
        .isEqualTo(listTotal("priority=HIGH&" + unresolved));
  }

  @Test
  void trendsCoverEveryDayIncludingEmptyOnes() {
    createTicket(leadBearer);
    createTicket(leadBearer);

    MvcTestResult trends = get("/api/v1/dashboard/trends?days=3", riyaBearer);

    assertThat(trends).hasStatusOk();
    List<String> dates = json(trends, "$[*].date");
    assertThat(dates).hasSize(3);
    assertThat(dates.get(2)).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
    assertThat(number(trends, "$[2].created")).isEqualTo(2);
    assertThat(number(trends, "$[0].created")).isZero();
    assertThat(get("/api/v1/dashboard/trends?days=0", riyaBearer)).hasStatus(400);
    assertThat(get("/api/v1/dashboard/trends?days=91", riyaBearer)).hasStatus(400);
  }

  @Test
  void workloadIsForLeadsAndCountsPerAgent() {
    UUID id = UUID.fromString(id(createTicket(leadBearer)));
    jdbc.update("UPDATE tickets SET assigned_agent_id = ? WHERE id = ?", riya.getId(), id);

    MvcTestResult workload = get("/api/v1/dashboard/agents-workload", leadBearer);

    assertThat(workload).hasStatusOk();
    List<Number> open = json(workload, "$[?(@.agentId=='" + riya.getId() + "')].open");
    assertThat(open).extracting(Number::longValue).containsExactly(1L);
    assertThat(get("/api/v1/dashboard/agents-workload", riyaBearer)).hasStatus(403);
  }
}
