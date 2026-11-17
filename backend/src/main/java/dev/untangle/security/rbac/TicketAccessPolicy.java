package dev.untangle.security.rbac;

import static dev.untangle.security.rbac.Permission.*;

import dev.untangle.common.error.ApiException;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.security.AuthenticatedAgent;
import dev.untangle.ticket.domain.Ticket;
import dev.untangle.ticket.domain.TicketAction;
import dev.untangle.ticket.domain.TicketStateMachine;
import dev.untangle.ticket.domain.TicketStatus;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides whether a person may perform an action on a specific ticket. It combines the closed rule
 * (§5.2), role permissions (§6.2), ownership ("am I the assignee?") and the state machine (§5.1).
 *
 * <p><b>One evaluator, two uses.</b> Services call {@link #require} before every mutation, and
 * {@code GET /tickets/{id}} calls {@link #evaluateAll} to build {@code allowedActions}. Because
 * both use the same code, the UI can never offer a button the server would reject (ADR-015).
 *
 * <p>Check order, so the reason shown for a disabled button is the most useful one:
 *
 * <ol>
 *   <li>closed → {@code TICKET_CLOSED} (everything except REOPEN);
 *   <li>permission + ownership → {@code ACCESS_DENIED};
 *   <li>state machine → {@code INVALID_STATUS_TRANSITION};
 *   <li>preconditions, e.g. START needs an assignee → {@code ASSIGNEE_REQUIRED}.
 * </ol>
 */
@Component
public class TicketAccessPolicy {

  /** Result for one action. {@code reason} is null when allowed. */
  public record Decision(TicketAction action, boolean allowed, ErrorCode reason) {
    static Decision allow(TicketAction action) {
      return new Decision(action, true, null);
    }

    static Decision deny(TicketAction action, ErrorCode reason) {
      return new Decision(action, false, reason);
    }
  }

  /** Every action at once, for the ticket detail view. */
  public record Evaluation(Set<TicketAction> allowed, List<Decision> denied) {}

  public Evaluation evaluateAll(Ticket ticket, AuthenticatedAgent me) {
    Set<TicketAction> allowed = EnumSet.noneOf(TicketAction.class);
    List<Decision> denied = new ArrayList<>();
    for (TicketAction action : TicketAction.values()) {
      Decision decision = evaluate(action, ticket, me);
      if (decision.allowed()) {
        allowed.add(action);
      } else {
        denied.add(decision);
      }
    }
    return new Evaluation(allowed, denied);
  }

  /** Throws the matching {@link ApiException} unless the action is allowed. */
  public void require(TicketAction action, Ticket ticket, AuthenticatedAgent me) {
    Decision decision = evaluate(action, ticket, me);
    if (!decision.allowed()) {
      throw new ApiException(decision.reason(), explain(decision, ticket));
    }
  }

  public Decision evaluate(TicketAction action, Ticket ticket, AuthenticatedAgent me) {
    TicketStatus status = ticket.getStatus();
    if (ticket.isClosed() && action != TicketAction.REOPEN) {
      return Decision.deny(action, ErrorCode.TICKET_CLOSED);
    }
    boolean mine = ticket.isAssignedTo(me.id());

    if (!hasPermission(action, status, mine, me)) {
      return Decision.deny(action, ErrorCode.ACCESS_DENIED);
    }

    return switch (action) {
      case EDIT, ASSIGN, COMMENT -> Decision.allow(action);
      case SELF_ASSIGN ->
          ticket.getAssignedAgent() == null && status == TicketStatus.OPEN
              ? Decision.allow(action)
              : Decision.deny(action, ErrorCode.ACCESS_DENIED);
      case ESCALATE ->
          status.isUnresolved()
              ? Decision.allow(action)
              : Decision.deny(action, ErrorCode.INVALID_STATUS_TRANSITION);
      case START_PROGRESS, RELEASE, RESOLVE, RETURN_TO_PROGRESS, CLOSE, REOPEN -> {
        if (TicketStateMachine.targetOf(action, status).isEmpty()) {
          yield Decision.deny(action, ErrorCode.INVALID_STATUS_TRANSITION);
        }
        if (action == TicketAction.START_PROGRESS && ticket.getAssignedAgent() == null) {
          yield Decision.deny(action, ErrorCode.ASSIGNEE_REQUIRED);
        }
        yield Decision.allow(action);
      }
    };
  }

  private static boolean hasPermission(
      TicketAction action, TicketStatus status, boolean mine, AuthenticatedAgent me) {
    return switch (action) {
      case EDIT -> me.can(TICKET_EDIT_ANY) || (mine && me.can(TICKET_EDIT_OWN));
      case ASSIGN -> me.can(TICKET_ASSIGN_ANY);
      case SELF_ASSIGN -> me.can(TICKET_SELF_ASSIGN);
      case START_PROGRESS, RELEASE, RESOLVE, RETURN_TO_PROGRESS ->
          me.can(TICKET_TRANSITION_ANY) || (mine && me.can(TICKET_TRANSITION_OWN));
      case CLOSE ->
          me.can(TICKET_CLOSE)
              || (status == TicketStatus.RESOLVED && mine && me.can(TICKET_CLOSE_OWN_RESOLVED));
      case REOPEN -> me.can(TICKET_REOPEN);
      case COMMENT -> me.can(TICKET_COMMENT);
      case ESCALATE -> me.can(TICKET_ESCALATE);
    };
  }

  private static String explain(Decision decision, Ticket ticket) {
    return switch (decision.reason()) {
      case TICKET_CLOSED -> "Ticket " + ticket.getTicketNumber() + " is closed. Reopen it first.";
      case ACCESS_DENIED ->
          "You are not allowed to " + humanize(decision.action()) + " this ticket";
      case INVALID_STATUS_TRANSITION ->
          "Cannot " + humanize(decision.action()) + " a ticket that is " + ticket.getStatus();
      case ASSIGNEE_REQUIRED -> "Assign the ticket before starting work on it";
      default -> decision.reason().title();
    };
  }

  private static String humanize(TicketAction action) {
    return action.name().toLowerCase().replace('_', ' ');
  }
}
