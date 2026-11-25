package dev.untangle.security.rbac;

import static dev.untangle.ticket.domain.TicketAction.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.security.AuthenticatedAgent;
import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.SlaPolicy;
import dev.untangle.ticket.domain.Ticket;
import dev.untangle.ticket.domain.TicketAction;
import dev.untangle.ticket.domain.TicketStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure unit tests for the per-ticket rules: role × ownership × status → allowed, or the reason
 * code. No Spring, no database: tickets are driven into each state through their own behaviour
 * methods.
 */
class TicketAccessPolicyTest {

  private final RolePermissions roles = new RolePermissions(false);
  private final TicketAccessPolicy policy = new TicketAccessPolicy();
  private final Instant now = Instant.parse("2026-11-18T10:00:00Z");

  private final Agent owner = agent(Role.AGENT);
  private final Agent other = agent(Role.AGENT);

  private static Agent agent(Role role) {
    Agent agent = new Agent("Test " + role, UUID.randomUUID() + "@x.test", "h", role);
    ReflectionTestUtils.setField(agent, "id", UUID.randomUUID());
    return agent;
  }

  private AuthenticatedAgent principal(Agent agent) {
    return new AuthenticatedAgent(
        agent.getId(),
        agent.getEmail(),
        agent.getFullName(),
        agent.getRole(),
        roles.of(agent.getRole()));
  }

  private AuthenticatedAgent as(String who) {
    return switch (who) {
      case "owner" -> principal(owner);
      case "other" -> principal(other);
      case "lead" -> principal(agent(Role.LEAD));
      case "admin" -> principal(agent(Role.ADMIN));
      default -> throw new IllegalArgumentException(who);
    };
  }

  /** A ticket in {@code status}, assigned to {@link #owner} unless it is "OPEN_UNASSIGNED". */
  private Ticket ticket(String state) {
    Ticket t =
        Ticket.manual(
            "Card blocked",
            "Card blocked while travelling",
            "Rahul",
            null,
            Category.ACCOUNT,
            Priority.HIGH,
            owner,
            now,
            now.plusSeconds(3600));
    if (state.equals("OPEN_UNASSIGNED")) {
      return t;
    }
    t.assignTo(owner, Instant.now());
    switch (TicketStatus.valueOf(state)) {
      case OPEN -> {}
      case IN_PROGRESS -> t.transitionTo(TicketStatus.IN_PROGRESS, now);
      case RESOLVED -> {
        t.transitionTo(TicketStatus.IN_PROGRESS, now);
        t.transitionTo(TicketStatus.RESOLVED, now);
      }
      case CLOSED -> {
        t.transitionTo(TicketStatus.IN_PROGRESS, now);
        t.transitionTo(TicketStatus.RESOLVED, now);
        t.transitionTo(TicketStatus.CLOSED, now);
      }
    }
    return t;
  }

  @ParameterizedTest(name = "{1} on {0} ticket as {2} → {3}")
  @CsvSource(
      nullValues = "ALLOWED",
      textBlock =
          """
          # state          | action             | who   | expected (ALLOWED or error code)
          OPEN_UNASSIGNED  | SELF_ASSIGN        | other | ALLOWED
          OPEN_UNASSIGNED  | START_PROGRESS     | lead  | ASSIGNEE_REQUIRED
          OPEN_UNASSIGNED  | START_PROGRESS     | other | ACCESS_DENIED
          OPEN_UNASSIGNED  | ASSIGN             | other | ACCESS_DENIED
          OPEN_UNASSIGNED  | ASSIGN             | lead  | ALLOWED
          OPEN_UNASSIGNED  | CLOSE              | lead  | ALLOWED
          OPEN_UNASSIGNED  | CLOSE              | other | ACCESS_DENIED
          OPEN             | SELF_ASSIGN        | other | ACCESS_DENIED
          OPEN             | START_PROGRESS     | owner | ALLOWED
          OPEN             | START_PROGRESS     | other | ACCESS_DENIED
          OPEN             | RESOLVE            | owner | INVALID_STATUS_TRANSITION
          OPEN             | EDIT               | owner | ALLOWED
          OPEN             | EDIT               | other | ACCESS_DENIED
          OPEN             | EDIT               | lead  | ALLOWED
          OPEN             | COMMENT            | other | ALLOWED
          OPEN             | ESCALATE           | other | ALLOWED
          OPEN             | REOPEN             | lead  | INVALID_STATUS_TRANSITION
          IN_PROGRESS      | RESOLVE            | owner | ALLOWED
          IN_PROGRESS      | RELEASE            | owner | ALLOWED
          IN_PROGRESS      | RESOLVE            | other | ACCESS_DENIED
          IN_PROGRESS      | RESOLVE            | lead  | ALLOWED
          IN_PROGRESS      | CLOSE              | lead  | INVALID_STATUS_TRANSITION
          RESOLVED         | RETURN_TO_PROGRESS | owner | ALLOWED
          RESOLVED         | CLOSE              | owner | ACCESS_DENIED
          RESOLVED         | CLOSE              | lead  | ALLOWED
          RESOLVED         | ESCALATE           | lead  | INVALID_STATUS_TRANSITION
          CLOSED           | REOPEN             | lead  | ALLOWED
          CLOSED           | REOPEN             | owner | ACCESS_DENIED
          CLOSED           | EDIT               | admin | TICKET_CLOSED
          CLOSED           | ASSIGN             | admin | TICKET_CLOSED
          CLOSED           | COMMENT            | admin | TICKET_CLOSED
          CLOSED           | ESCALATE           | admin | TICKET_CLOSED
          CLOSED           | START_PROGRESS     | admin | TICKET_CLOSED
          """,
      delimiter = '|')
  void decides(String state, TicketAction action, String who, ErrorCode expected) {
    TicketAccessPolicy.Decision decision = policy.evaluate(action, ticket(state), as(who));

    if (expected == null) {
      assertThat(decision.allowed()).as("allowed").isTrue();
    } else {
      assertThat(decision.allowed()).as("allowed").isFalse();
      assertThat(decision.reason()).isEqualTo(expected);
    }
  }

  @Test
  void agentMayCloseOwnResolvedTicketWhenConfigured() {
    RolePermissions permissive = new RolePermissions(true);
    AuthenticatedAgent me =
        new AuthenticatedAgent(
            owner.getId(),
            owner.getEmail(),
            owner.getFullName(),
            Role.AGENT,
            permissive.of(Role.AGENT));
    assertThat(policy.evaluate(CLOSE, ticket("RESOLVED"), me).allowed()).isTrue();
    assertThat(policy.evaluate(CLOSE, ticket("OPEN"), me).allowed()).isFalse();
  }

  @Test
  void onAClosedTicketALeadCanOnlyReopen() {
    var evaluation = policy.evaluateAll(ticket("CLOSED"), as("lead"));
    assertThat(evaluation.allowed()).containsExactly(REOPEN);
    assertThat(evaluation.denied())
        .allSatisfy(d -> assertThat(d.reason()).isEqualTo(ErrorCode.TICKET_CLOSED));
  }

  @Test
  void ownerOfAnInProgressTicketSeesTheRightButtons() {
    var evaluation = policy.evaluateAll(ticket("IN_PROGRESS"), as("owner"));
    assertThat(evaluation.allowed())
        .containsExactlyInAnyOrder(EDIT, RELEASE, RESOLVE, COMMENT, ESCALATE);
  }

  @Test
  void slaHelperComputesFromClockStart() {
    assertThat(SlaPolicy.defaults().dueAt(now, Priority.CRITICAL)).isEqualTo(now.plusSeconds(3600));
  }
}
