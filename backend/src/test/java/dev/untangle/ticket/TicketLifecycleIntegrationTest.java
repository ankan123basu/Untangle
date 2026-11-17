package dev.untangle.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The ticket lifecycle over HTTP (§5, §11 "Lifecycle", "Validation", "Concurrency"). */
class TicketLifecycleIntegrationTest extends IntegrationTest {

  private Agent owner;
  private String ownerBearer;
  private String otherAgentBearer;
  private String leadBearer;

  @BeforeEach
  void signIn() {
    owner = createAgent(Role.AGENT);
    ownerBearer = bearer(owner);
    otherAgentBearer = bearer(Role.AGENT);
    leadBearer = bearer(Role.LEAD);
  }

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  // ---- helpers ------------------------------------------------------------------------------

  private MvcTestResult fetch(String id, String bearer) {
    return get("/api/v1/tickets/" + id, bearer);
  }

  private MvcTestResult transition(
      String bearer, String id, String target, long version, String note, String reason) {
    String json =
        "{\"targetStatus\":\"%s\",\"version\":%d%s%s}"
            .formatted(
                target,
                version,
                note == null ? "" : ",\"note\":\"" + note + "\"",
                reason == null ? "" : ",\"reason\":\"" + reason + "\"");
    return post("/api/v1/tickets/" + id + "/transitions", bearer, json);
  }

  private MvcTestResult assign(String bearer, String id, String agentId, long version) {
    String agent = agentId == null ? "null" : "\"" + agentId + "\"";
    return call(
        HttpMethod.PUT,
        "/api/v1/tickets/" + id + "/assignee",
        bearer,
        "{\"agentId\":%s,\"version\":%d}".formatted(agent, version));
  }

  /** Creates a ticket owned by {@link #owner} and drives it into {@code state} as the lead. */
  private MvcTestResult ticketIn(String state) {
    MvcTestResult t = createTicket(leadBearer);
    String id = id(t);
    if (state.equals("OPEN_UNASSIGNED")) return t;
    t = assign(leadBearer, id, owner.getId().toString(), version(t));
    if (state.equals("OPEN")) return t;
    t = transition(leadBearer, id, "IN_PROGRESS", version(t), null, null);
    if (state.equals("IN_PROGRESS")) return t;
    t = transition(leadBearer, id, "RESOLVED", version(t), "Refund reprocessed", null);
    if (state.equals("RESOLVED")) return t;
    t = transition(leadBearer, id, "CLOSED", version(t), null, null);
    assertThat(t).hasStatusOk();
    return t;
  }

  private List<String> timelineTypes(String id) {
    return json(get("/api/v1/tickets/" + id + "/comments", leadBearer), "$[*].type");
  }

  // ---- create & validate --------------------------------------------------------------------

  @Test
  void createsATicketWithNumberSlaCreatorAndTimeline() {
    MvcTestResult created = createTicket(ownerBearer);

    assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith(id(created));
    assertThat((String) json(created, "$.ticketNumber")).matches("UNT-\\d{6,}");
    assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("OPEN");
    assertThat(created).bodyJson().extractingPath("$.source").isEqualTo("MANUAL");
    assertThat(created)
        .bodyJson()
        .extractingPath("$.createdBy.id")
        .isEqualTo(owner.getId().toString());
    Instant start = Instant.parse(json(created, "$.slaStartedAt"));
    Instant due = Instant.parse(json(created, "$.slaDueAt"));
    assertThat(Duration.between(start, due)).as("HIGH = 4h").isEqualTo(Duration.ofHours(4));
    assertThat(timelineTypes(id(created))).containsExactly("SYSTEM");
  }

  @ParameterizedTest(name = "{0} → 400 on field {1}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          "priority":"high"              | priority
          "priority":"URGENT"            | priority
          "priority":null                | priority
          "priority":""                  | priority
          "category":"FOOD"              | category
          "customerName":"   "           | customerName
          "subject":"abc"                | subject
          "customerEmail":"not-an-email" | customerEmail
          """)
  void rejectsInvalidTickets(String override, String field) {
    String key = override.substring(1, override.indexOf('"', 1));
    String body = VALID_TICKET.replaceAll("\"" + key + "\":\"[^\"]*\"", override.strip());

    MvcTestResult result = post("/api/v1/tickets", ownerBearer, body);

    assertThat(result).hasStatus(400);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    assertThat((List<String>) json(result, "$.errors[*].field")).contains(field);
  }

  @Test
  void rejectsADescriptionOver5000Characters() {
    String body = VALID_TICKET.replace("My card was blocked while travelling.", "x".repeat(5001));
    MvcTestResult result = post("/api/v1/tickets", ownerBearer, body);
    assertThat(result).hasStatus(400);
    assertThat((List<String>) json(result, "$.errors[*].field")).contains("description");
  }

  @Test
  void agentsMayAssignNewTicketsOnlyToThemselves() {
    String toSelf = VALID_TICKET.replace("}", ",\"assigneeId\":\"" + owner.getId() + "\"}");
    String toOther =
        VALID_TICKET.replace("}", ",\"assigneeId\":\"" + createAgent(Role.AGENT).getId() + "\"}");

    assertThat(post("/api/v1/tickets", ownerBearer, toSelf)).hasStatus(201);
    assertThat(post("/api/v1/tickets", ownerBearer, toOther)).hasStatus(403);
    assertThat(post("/api/v1/tickets", leadBearer, toOther)).hasStatus(201);
  }

  // ---- happy path -----------------------------------------------------------------------------

  @Test
  void fullLifecycleFromIntakeToReopen() {
    MvcTestResult t = createTicket(leadBearer);
    String id = id(t);

    t = assign(ownerBearer, id, owner.getId().toString(), version(t)); // self-assign
    assertThat(t).hasStatusOk();
    t = transition(ownerBearer, id, "IN_PROGRESS", version(t), null, null);
    assertThat(t).bodyJson().extractingPath("$.firstResponseAt").isNotNull();
    t = transition(ownerBearer, id, "RESOLVED", version(t), "Refund reprocessed via NEFT", null);
    assertThat(t).bodyJson().extractingPath("$.status").isEqualTo("RESOLVED");
    assertThat(t).bodyJson().extractingPath("$.resolvedAt").isNotNull();

    assertThat(transition(ownerBearer, id, "CLOSED", version(t), null, null))
        .as("agents can't close by default")
        .hasStatus(403);
    t = transition(leadBearer, id, "CLOSED", version(t), null, null);
    assertThat(t).bodyJson().extractingPath("$.closedAt").isNotNull();

    t =
        post(
            "/api/v1/tickets/" + id + "/reopen",
            leadBearer,
            "{\"reason\":\"Customer says refund still missing\",\"version\":%d}"
                .formatted(version(t)));
    assertThat(t).hasStatusOk();
    assertThat(t).bodyJson().extractingPath("$.status").isEqualTo("OPEN");
    assertThat(t).bodyJson().extractingPath("$.reopenedCount").isEqualTo(1);
    assertThat(t).bodyJson().extractingPath("$.assignee.id").isEqualTo(owner.getId().toString());
    assertThat(t).bodyJson().extractingPath("$.closedAt").isNull();

    assertThat(timelineTypes(id))
        .containsExactly(
            "SYSTEM", // created
            "SYSTEM", // assigned
            "SYSTEM", // OPEN -> IN_PROGRESS
            "RESOLUTION_NOTE",
            "SYSTEM", // IN_PROGRESS -> RESOLVED
            "SYSTEM", // RESOLVED -> CLOSED
            "SYSTEM"); // reopened
  }

  @Test
  void releaseClearsTheAssignee() {
    MvcTestResult t = ticketIn("IN_PROGRESS");
    MvcTestResult released = transition(ownerBearer, id(t), "OPEN", version(t), null, null);
    assertThat(released).hasStatusOk();
    assertThat(released).bodyJson().extractingPath("$.assignee").isNull();
  }

  @Test
  void returningToProgressNeedsAReason() {
    MvcTestResult t = ticketIn("RESOLVED");
    assertThat(transition(ownerBearer, id(t), "IN_PROGRESS", version(t), null, null))
        .hasStatus(400);
    MvcTestResult ok =
        transition(ownerBearer, id(t), "IN_PROGRESS", version(t), null, "Fix did not work");
    assertThat(ok).hasStatusOk();
    assertThat(ok).bodyJson().extractingPath("$.resolvedAt").isNull();
  }

  @Test
  void closingAnOpenTicketNeedsAReason() {
    MvcTestResult t = createTicket(leadBearer);
    MvcTestResult noReason = transition(leadBearer, id(t), "CLOSED", version(t), null, null);
    assertThat(noReason).hasStatus(400);
    assertThat((List<String>) json(noReason, "$.errors[*].field")).contains("reason");
    assertThat(transition(leadBearer, id(t), "CLOSED", version(t), null, "Duplicate of UNT-000001"))
        .hasStatusOk();
  }

  // ---- rule violations ----------------------------------------------------------------------

  @ParameterizedTest(name = "{0} → {1} is rejected")
  @CsvSource({
    "OPEN, RESOLVED",
    "OPEN, OPEN",
    "IN_PROGRESS, CLOSED",
    "IN_PROGRESS, IN_PROGRESS",
    "RESOLVED, OPEN",
    "RESOLVED, RESOLVED"
  })
  void rejectsDisallowedTransitions(String from, String to) {
    MvcTestResult t = ticketIn(from);

    MvcTestResult result = transition(leadBearer, id(t), to, version(t), "note", "reason");

    assertThat(result).hasStatus(409);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_STATUS_TRANSITION");
  }

  @Test
  void cannotStartWorkOnAnUnassignedTicket() {
    MvcTestResult t = createTicket(leadBearer);
    MvcTestResult result = transition(leadBearer, id(t), "IN_PROGRESS", version(t), null, null);
    assertThat(result).hasStatus(409);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ASSIGNEE_REQUIRED");
  }

  @Test
  void resolvingRequiresANote() {
    MvcTestResult t = ticketIn("IN_PROGRESS");
    MvcTestResult result = transition(ownerBearer, id(t), "RESOLVED", version(t), "   ", null);
    assertThat(result).hasStatus(400);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("RESOLUTION_NOTE_REQUIRED");
  }

  @ParameterizedTest(name = "{0} on a CLOSED ticket → 409 TICKET_CLOSED")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          PATCH | /api/v1/tickets/{id}             | {"priority":"LOW","version":{v}}
          PUT   | /api/v1/tickets/{id}/assignee    | {"agentId":null,"version":{v}}
          POST  | /api/v1/tickets/{id}/comments    | {"body":"hello"}
          POST  | /api/v1/tickets/{id}/escalate    | {"reason":"angry customer"}
          POST  | /api/v1/tickets/{id}/transitions | {"targetStatus":"OPEN","version":{v}}
          POST  | /api/v1/tickets/{id}/transitions | {"targetStatus":"IN_PROGRESS","reason":"x","version":{v}}
          """)
  void closedTicketsRejectEveryMutation(HttpMethod method, String uri, String body) {
    MvcTestResult t = ticketIn("CLOSED");

    MvcTestResult result =
        call(
            method,
            uri.replace("{id}", id(t)),
            leadBearer,
            body.replace("{v}", Long.toString(version(t))));

    assertThat(result).hasStatus(409);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("TICKET_CLOSED");
  }

  @Test
  void reopeningANonClosedTicketIs409AndNeedsAReason() {
    MvcTestResult open = createTicket(leadBearer);
    MvcTestResult notClosed =
        post(
            "/api/v1/tickets/" + id(open) + "/reopen",
            leadBearer,
            "{\"reason\":\"why\",\"version\":%d}".formatted(version(open)));
    assertThat(notClosed).hasStatus(409);
    assertThat(notClosed)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("INVALID_STATUS_TRANSITION");

    MvcTestResult closed = ticketIn("CLOSED");
    assertThat(
            post(
                "/api/v1/tickets/" + id(closed) + "/reopen",
                leadBearer,
                "{\"reason\":\"\",\"version\":%d}".formatted(version(closed))))
        .hasStatus(400);
  }

  @Test
  void reopenDropsADeactivatedAssignee() {
    MvcTestResult closed = ticketIn("CLOSED");
    jdbc.update("UPDATE agents SET active = false WHERE id = ?", owner.getId());

    MvcTestResult reopened =
        post(
            "/api/v1/tickets/" + id(closed) + "/reopen",
            leadBearer,
            "{\"reason\":\"still broken\",\"version\":%d}".formatted(version(closed)));

    assertThat(reopened).hasStatusOk();
    assertThat(reopened).bodyJson().extractingPath("$.assignee").isNull();
  }

  @Test
  void priorityChangeRecalculatesTheSlaFromTheSameStart() {
    MvcTestResult t = createTicket(leadBearer);
    MvcTestResult edited =
        call(
            HttpMethod.PATCH,
            "/api/v1/tickets/" + id(t),
            leadBearer,
            "{\"priority\":\"CRITICAL\",\"version\":%d}".formatted(version(t)));

    assertThat(edited).hasStatusOk();
    Instant start = Instant.parse(json(edited, "$.slaStartedAt"));
    Instant due = Instant.parse(json(edited, "$.slaDueAt"));
    assertThat(start).isEqualTo(Instant.parse(json(t, "$.slaStartedAt")));
    assertThat(Duration.between(start, due)).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void staleVersionIsAConflict() {
    MvcTestResult t = createTicket(leadBearer);
    call(
        HttpMethod.PATCH,
        "/api/v1/tickets/" + id(t),
        leadBearer,
        "{\"priority\":\"LOW\",\"version\":%d}".formatted(version(t)));

    MvcTestResult stale =
        call(
            HttpMethod.PATCH,
            "/api/v1/tickets/" + id(t),
            leadBearer,
            "{\"priority\":\"MEDIUM\",\"version\":%d}".formatted(version(t)));

    assertThat(stale).hasStatus(409);
    assertThat(stale).bodyJson().extractingPath("$.code").isEqualTo("VERSION_CONFLICT");
    assertThat(stale).bodyJson().extractingPath("$.currentVersion").isNotNull();
  }

  @Test
  void twoAgentsSelfAssigningAtOnceOnlyOneWins() throws Exception {
    MvcTestResult t = createTicket(leadBearer);
    String id = id(t);
    long version = version(t);
    Agent second = createAgent(Role.AGENT);
    String secondBearer = bearer(second);
    CountDownLatch go = new CountDownLatch(1);

    Callable<Integer> first =
        () -> {
          go.await();
          return assign(ownerBearer, id, owner.getId().toString(), version)
              .getResponse()
              .getStatus();
        };
    Callable<Integer> other =
        () -> {
          go.await();
          return assign(secondBearer, id, second.getId().toString(), version)
              .getResponse()
              .getStatus();
        };
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<Integer> a = pool.submit(first);
      Future<Integer> b = pool.submit(other);
      go.countDown();
      assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
    }
  }

  // ---- ownership & roles ----------------------------------------------------------------------

  @Test
  void agentsCannotWorkOtherAgentsTickets() {
    MvcTestResult t = ticketIn("OPEN");
    String id = id(t);
    long v = version(t);

    assertThat(transition(otherAgentBearer, id, "IN_PROGRESS", v, null, null)).hasStatus(403);
    assertThat(
            call(
                HttpMethod.PATCH,
                "/api/v1/tickets/" + id,
                otherAgentBearer,
                "{\"priority\":\"LOW\",\"version\":%d}".formatted(v)))
        .hasStatus(403);
    assertThat(assign(otherAgentBearer, id, owner.getId().toString(), v)).hasStatus(403);
    assertThat(post("/api/v1/tickets/" + id + "/comments", otherAgentBearer, "{\"body\":\"fyi\"}"))
        .as("anyone may comment")
        .hasStatus(201);
  }

  @Test
  void allowedActionsReflectRoleOwnershipAndState() {
    MvcTestResult t = createTicket(leadBearer);
    String id = id(t);

    MvcTestResult asAgent = fetch(id, otherAgentBearer);
    assertThat((List<String>) json(asAgent, "$.allowedActions"))
        .contains("SELF_ASSIGN", "COMMENT", "ESCALATE")
        .doesNotContain("ASSIGN", "CLOSE", "START_PROGRESS");

    MvcTestResult asLead = fetch(id, leadBearer);
    assertThat((List<String>) json(asLead, "$.allowedActions")).contains("ASSIGN", "CLOSE", "EDIT");
    assertThat((List<String>) json(asLead, "$.deniedActions[?(@.action=='START_PROGRESS')].reason"))
        .containsExactly("ASSIGNEE_REQUIRED");

    MvcTestResult closed = ticketIn("CLOSED");
    assertThat((List<String>) json(fetch(id(closed), leadBearer), "$.allowedActions"))
        .containsExactly("REOPEN");
  }

  @Test
  void assignmentRules() {
    MvcTestResult t = ticketIn("IN_PROGRESS");
    MvcTestResult unassignInProgress = assign(leadBearer, id(t), null, version(t));
    assertThat(unassignInProgress).hasStatus(409);
    assertThat(unassignInProgress)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("ASSIGNEE_REQUIRED");

    Agent inactive = createAgent(Role.AGENT);
    jdbc.update("UPDATE agents SET active = false WHERE id = ?", inactive.getId());
    MvcTestResult toInactive = assign(leadBearer, id(t), inactive.getId().toString(), version(t));
    assertThat(toInactive).hasStatus(409);
    assertThat(toInactive).bodyJson().extractingPath("$.code").isEqualTo("AGENT_INACTIVE");
  }

  // ---- comments, escalation, lookup ---------------------------------------------------------

  @Test
  void commentsAreStoredVerbatimAndServedAsJsonText() {
    MvcTestResult t = createTicket(leadBearer);
    String html = "<script>alert('x')</script> <b>bold</b>";

    MvcTestResult posted =
        post(
            "/api/v1/tickets/" + id(t) + "/comments",
            ownerBearer,
            "{\"body\":\"" + html.replace("'", "\\u0027") + "\"}");

    assertThat(posted).hasStatus(201);
    assertThat(posted).bodyJson().extractingPath("$.body").isEqualTo(html);
    assertThat(posted).bodyJson().extractingPath("$.type").isEqualTo("COMMENT");
    assertThat(posted.getResponse().getContentType()).startsWith("application/json");
    assertThat(posted.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
  }

  @Test
  void systemCommentsCannotBeCreatedThroughTheApi() {
    MvcTestResult t = createTicket(leadBearer);
    assertThat(
            post(
                "/api/v1/tickets/" + id(t) + "/comments",
                leadBearer,
                "{\"body\":\"fake event\",\"type\":\"SYSTEM\"}"))
        .hasStatus(400);
  }

  @Test
  void manualEscalationRaisesTheLevel() {
    MvcTestResult t = createTicket(leadBearer);
    MvcTestResult escalated =
        post(
            "/api/v1/tickets/" + id(t) + "/escalate", ownerBearer, "{\"reason\":\"VIP customer\"}");
    assertThat(escalated).hasStatusOk();
    assertThat(escalated).bodyJson().extractingPath("$.escalationLevel").isEqualTo(1);
    assertThat(post("/api/v1/tickets/" + id(t) + "/escalate", ownerBearer, "{\"reason\":\" \"}"))
        .hasStatus(400);
  }

  @Test
  void lookupByNumberAndUnknownTickets() {
    MvcTestResult t = createTicket(leadBearer);
    String number = json(t, "$.ticketNumber");

    assertThat(get("/api/v1/tickets/by-number/" + number.toLowerCase(), ownerBearer)).hasStatusOk();
    MvcTestResult missing =
        get("/api/v1/tickets/00000000-0000-7000-8000-000000000000", ownerBearer);
    assertThat(missing).hasStatus(404);
    assertThat(missing).bodyJson().extractingPath("$.code").isEqualTo("TICKET_NOT_FOUND");
  }

  @Test
  void bulkAssignSkipsClosedTickets() {
    MvcTestResult open = createTicket(leadBearer);
    MvcTestResult closed = ticketIn("CLOSED");
    Agent target = createAgent(Role.AGENT);

    MvcTestResult result =
        post(
            "/api/v1/tickets/bulk/assign",
            leadBearer,
            "{\"ticketIds\":[\"%s\",\"%s\"],\"agentId\":\"%s\"}"
                .formatted(id(open), id(closed), target.getId()));

    assertThat(result).hasStatusOk();
    assertThat((List<String>) json(result, "$.assigned")).containsExactly(id(open));
    assertThat(result).bodyJson().extractingPath("$.skipped[0].reason").isEqualTo("TICKET_CLOSED");
    assertThat(
            post(
                "/api/v1/tickets/bulk/assign",
                ownerBearer,
                "{\"ticketIds\":[\"%s\"],\"agentId\":\"%s\"}".formatted(id(open), target.getId())))
        .hasStatus(403);
  }
}
