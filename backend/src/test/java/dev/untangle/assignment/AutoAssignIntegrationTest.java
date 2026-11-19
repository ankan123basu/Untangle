package dev.untangle.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.assignment.application.AutoAssignViews.AutoAssignRun;
import dev.untangle.assignment.application.AutoAssigner;
import dev.untangle.support.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** Automatic assignment end to end (ADR-029): API, queue locking, timeline and notifications. */
class AutoAssignIntegrationTest extends IntegrationTest {

  @Autowired AutoAssigner assigner;

  private String lead;

  @BeforeEach
  void setUp() {
    lead = bearer(Role.LEAD);
  }

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  private void switchOn(int cap) {
    var result =
        call(
            HttpMethod.PUT,
            "/api/v1/auto-assign/settings",
            lead,
            "{\"enabled\":true,\"maxOpenPerAgent\":%d}".formatted(cap));
    assertThat(result).hasStatusOk();
  }

  private UUID ticket(String category, String priority) {
    var result =
        post(
            "/api/v1/tickets",
            lead,
            """
            {"subject":"Needs help","description":"Customer cannot complete the payment.",
             "customerName":"Asha Rao","category":"%s","priority":"%s"}\
            """
                .formatted(category, priority));
    assertThat(result).hasStatus(201);
    return UUID.fromString(id(result));
  }

  private UUID assignee(UUID ticket) {
    return jdbc.queryForObject(
        "SELECT assigned_agent_id FROM tickets WHERE id = ?", UUID.class, ticket);
  }

  @Test
  void isOffUntilALeadSwitchesItOn() {
    Agent agent = createAgent(Role.AGENT);
    UUID waiting = ticket("BILLING", "HIGH");
    assertThat(assignee(waiting)).isNull();

    var overview = get("/api/v1/auto-assign", lead);
    assertThat(overview).hasStatusOk();
    assertThat((Boolean) json(overview, "$.enabled")).isFalse();
    assertThat((Integer) json(overview, "$.waiting")).isEqualTo(1);
    assertThat((List<String>) json(overview, "$.agents[?(@.autoAssign == true)].agentId"))
        .containsExactly(agent.getId().toString());
  }

  @Test
  void aNewTicketGoesToTheLeastBusyAgentWithATimelineLineAndANotification() {
    Agent busy = createAgent(Role.AGENT);
    Agent free = createAgent(Role.AGENT);
    UUID earlier = ticket("BILLING", "CRITICAL");
    jdbc.update("UPDATE tickets SET assigned_agent_id = ? WHERE id = ?", busy.getId(), earlier);
    switchOn(10);

    UUID fresh = ticket("TECHNICAL", "MEDIUM");

    assertThat(assignee(fresh)).isEqualTo(free.getId());
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM ticket_comments WHERE ticket_id = ?"
                    + " AND metadata->>'automatic' = 'true'",
                String.class,
                fresh))
        .startsWith("Auto-assigned to " + free.getFullName());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_agent_id = ? AND type = 'ASSIGNED'",
                Long.class,
                free.getId()))
        .isEqualTo(1);
  }

  @Test
  void theSweepTakesTheMostUrgentFirstAndStopsAtTheCap() {
    Agent a = createAgent(Role.AGENT);
    Agent b = createAgent(Role.AGENT);
    UUID low = ticket("GENERAL", "LOW"); // due in 72 h
    UUID critical = ticket("GENERAL", "CRITICAL"); // due in 1 h
    UUID high = ticket("GENERAL", "HIGH"); // due in 4 h
    switchOn(1);

    var run = post("/api/v1/auto-assign/run", lead, null);

    assertThat(run).hasStatusOk();
    assertThat((Integer) json(run, "$.assigned")).isEqualTo(2);
    assertThat((Integer) json(run, "$.waiting")).isEqualTo(1);
    assertThat(List.of(assignee(critical), assignee(high)))
        .containsExactlyInAnyOrder(a.getId(), b.getId());
    assertThat(assignee(low)).as("everyone is at the cap of 1").isNull();
  }

  @Test
  void leadsAdminsAndTheAiDoNotTakeAutomaticWorkByDefault() {
    createAgent(Role.ADMIN);
    UUID waiting = ticket("GENERAL", "LOW");
    switchOn(10);
    AutoAssignRun run = assigner.assignWaiting(Instant.now());
    assertThat(run.assigned()).isZero();
    assertThat(assignee(waiting)).isNull();
  }

  @Test
  void categoriesRouteTicketsToSpecialists() {
    Agent billing = createAgent(Role.AGENT);
    Agent technical = createAgent(Role.AGENT);
    put(
        "/api/v1/auto-assign/agents/" + billing.getId(),
        "{\"autoAssign\":true,\"skills\":[\"BILLING\"]}");
    put(
        "/api/v1/auto-assign/agents/" + technical.getId(),
        "{\"autoAssign\":true,\"skills\":[\"TECHNICAL\"]}");
    UUID invoice = ticket("BILLING", "LOW");
    UUID outage = ticket("TECHNICAL", "LOW");
    UUID refund = ticket("REFUND", "LOW");
    switchOn(10);

    assigner.assignWaiting(Instant.now());

    assertThat(assignee(invoice)).isEqualTo(billing.getId());
    assertThat(assignee(outage)).isEqualTo(technical.getId());
    assertThat(assignee(refund)).as("nobody handles refunds").isNull();
  }

  @Test
  void anAgentWhoReleasesATicketIsNotHandedItStraightBack() {
    Agent first = createAgent(Role.AGENT);
    switchOn(10);
    UUID id = ticket("GENERAL", "MEDIUM"); // assigned on creation
    assertThat(assignee(id)).isEqualTo(first.getId());

    String firstBearer = bearer(first);
    long v = ((Number) json(get("/api/v1/tickets/" + id, firstBearer), "$.version")).longValue();
    var started =
        post(
            "/api/v1/tickets/" + id + "/transitions",
            firstBearer,
            "{\"targetStatus\":\"IN_PROGRESS\",\"version\":%d}".formatted(v));
    assertThat(started).hasStatusOk();
    var released =
        post(
            "/api/v1/tickets/" + id + "/transitions",
            firstBearer,
            "{\"targetStatus\":\"OPEN\",\"version\":%d}".formatted(version(started)));
    assertThat(released).hasStatusOk();

    assigner.assignWaiting(Instant.now());
    assertThat(assignee(id)).as("only the releaser takes work").isNull();

    Agent second = createAgent(Role.AGENT);
    assigner.assignWaiting(Instant.now());
    assertThat(assignee(id)).isEqualTo(second.getId());
  }

  @Test
  void aDeactivatedAgentGetsNothing() {
    Agent gone = createAgent(Role.AGENT);
    gone.deactivate();
    agentRepository.saveAndFlush(gone);
    UUID waiting = ticket("GENERAL", "LOW");
    switchOn(10);
    assigner.assignWaiting(Instant.now());
    assertThat(assignee(waiting)).isNull();
  }

  @Test
  void concurrentRunsNeverAssignATicketTwice() throws Exception {
    createAgent(Role.AGENT);
    createAgent(Role.AGENT);
    List<UUID> queue = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      queue.add(ticket("GENERAL", i % 2 == 0 ? "LOW" : "HIGH"));
    }
    switchOn(100);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
      List<Future<AutoAssignRun>> runs = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        runs.add(
            pool.submit(() -> {
              start.await();
              return assigner.assignWaiting(Instant.now());
            }));
      }
      start.countDown();
      int total = 0;
      for (Future<AutoAssignRun> run : runs) {
        total += run.get().assigned();
      }
      assertThat(total).isEqualTo(30);
    }
    assertThat(queue).allSatisfy(id -> assertThat(assignee(id)).isNotNull());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_comments WHERE metadata->>'automatic' = 'true'",
                Long.class))
        .as("exactly one automatic assignment per ticket")
        .isEqualTo(30);
  }

  @Test
  void onlyLeadsAndAdminsMayConfigureAndInputIsValidated() {
    String agent = bearer(Role.AGENT);
    assertThat(get("/api/v1/auto-assign", agent)).hasStatus(403);
    assertThat(post("/api/v1/auto-assign/run", agent, null)).hasStatus(403);
    assertThat(
            call(
                HttpMethod.PUT,
                "/api/v1/auto-assign/settings",
                lead,
                "{\"enabled\":true,\"maxOpenPerAgent\":0}"))
        .hasStatus(400);
    assertThat(
            call(
                HttpMethod.PUT,
                "/api/v1/auto-assign/agents/" + UUID.randomUUID(),
                lead,
                "{\"autoAssign\":true,\"skills\":[]}"))
        .hasStatus(404);
    assertThat(
            call(
                HttpMethod.PUT,
                "/api/v1/auto-assign/agents/" + UUID.randomUUID(),
                lead,
                "{\"autoAssign\":true,\"skills\":[\"URGENT\"]}"))
        .hasStatus(400);
  }

  private void put(String uri, String json) {
    assertThat(call(HttpMethod.PUT, uri, lead, json)).hasStatusOk();
  }
}
