package dev.untangle.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** In-app notifications from ticket events, and the bell/drawer API. */
class NotificationIntegrationTest extends IntegrationTest {

  private Agent agent;
  private String agentBearer;
  private String leadBearer;
  private Agent lead;

  @BeforeEach
  void setUp() {
    agent = createAgent(Role.AGENT);
    agentBearer = bearer(agent);
    lead = createAgent(Role.LEAD);
    leadBearer = bearer(lead);
  }

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  private long unread(String bearer) {
    return ((Number) json(get("/api/v1/notifications/unread-count", bearer), "$.count"))
        .longValue();
  }

  private MvcTestResult assignTo(String bearer, MvcTestResult ticket, Agent who) {
    return call(
        HttpMethod.PUT,
        "/api/v1/tickets/" + id(ticket) + "/assignee",
        bearer,
        "{\"agentId\":\"%s\",\"version\":%d}".formatted(who.getId(), version(ticket)));
  }

  @Test
  void assigneeIsNotifiedButNotWhenAssigningThemselves() {
    MvcTestResult t = createTicket(leadBearer);
    assignTo(leadBearer, t, agent);
    assertThat(unread(agentBearer)).isEqualTo(1);

    MvcTestResult mine = createTicket(leadBearer);
    assignTo(agentBearer, mine, agent); // self-assign
    assertThat(unread(agentBearer)).as("no notification for your own action").isEqualTo(1);

    MvcTestResult list = get("/api/v1/notifications", agentBearer);
    assertThat(list).bodyJson().extractingPath("$.content[0].type").isEqualTo("ASSIGNED");
    assertThat((String) json(list, "$.content[0].message")).endsWith("was assigned to you");
    assertThat(list)
        .bodyJson()
        .extractingPath("$.content[0].ticketNumber")
        .isEqualTo(json(t, "$.ticketNumber"));
  }

  @Test
  void manualEscalationNotifiesLeadsAndAssigneeButNotTheActor() {
    Agent otherLead = createAgent(Role.LEAD);
    MvcTestResult t = assignTo(leadBearer, createTicket(leadBearer), agent);
    long before = unread(agentBearer);

    post("/api/v1/tickets/" + id(t) + "/escalate", leadBearer, "{\"reason\":\"VIP customer\"}");

    assertThat(unread(agentBearer)).isEqualTo(before + 1);
    assertThat(unread(bearer(otherLead))).isEqualTo(1);
    assertThat(unread(leadBearer)).as("the lead who escalated").isZero();
    assertThat(
            (String)
                json(
                    get("/api/v1/notifications?unreadOnly=true", agentBearer),
                    "$.content[0].message"))
        .contains("escalated to level 1: VIP customer");
  }

  @Test
  void markReadAndReadAll() {
    MvcTestResult a = createTicket(leadBearer);
    MvcTestResult b = createTicket(leadBearer);
    assignTo(leadBearer, a, agent);
    assignTo(leadBearer, b, agent);
    assertThat(unread(agentBearer)).isEqualTo(2);

    String first = json(get("/api/v1/notifications", agentBearer), "$.content[0].id");
    MvcTestResult read =
        call(HttpMethod.PATCH, "/api/v1/notifications/" + first + "/read", agentBearer, null);
    assertThat(read).bodyJson().extractingPath("$.read").isEqualTo(true);
    assertThat(unread(agentBearer)).isEqualTo(1);

    assertThat(call(HttpMethod.PATCH, "/api/v1/notifications/" + first + "/read", leadBearer, null))
        .as("someone else's notification")
        .hasStatus(404);

    post("/api/v1/notifications/read-all", agentBearer, null);
    assertThat(unread(agentBearer)).isZero();
    assertThat(
            (List<?>) json(get("/api/v1/notifications?unreadOnly=true", agentBearer), "$.content"))
        .isEmpty();
  }
}
