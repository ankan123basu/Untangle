package dev.untangle.ticket.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Every one of the 16 from→to pairs, against the table in MASTER_PROMPT §5.1. */
class TicketStateMachineTest {

  @ParameterizedTest(name = "{0} → {1}: allowed={2} via {3}")
  @CsvSource(
      nullValues = "-",
      textBlock =
          """
          OPEN,        OPEN,        false, -
          OPEN,        IN_PROGRESS, true,  START_PROGRESS
          OPEN,        RESOLVED,    false, -
          OPEN,        CLOSED,      true,  CLOSE
          IN_PROGRESS, OPEN,        true,  RELEASE
          IN_PROGRESS, IN_PROGRESS, false, -
          IN_PROGRESS, RESOLVED,    true,  RESOLVE
          IN_PROGRESS, CLOSED,      false, -
          RESOLVED,    OPEN,        false, -
          RESOLVED,    IN_PROGRESS, true,  RETURN_TO_PROGRESS
          RESOLVED,    RESOLVED,    false, -
          RESOLVED,    CLOSED,      true,  CLOSE
          CLOSED,      OPEN,        true,  REOPEN
          CLOSED,      IN_PROGRESS, false, -
          CLOSED,      RESOLVED,    false, -
          CLOSED,      CLOSED,      false, -
          """)
  void matchesTheTransitionTable(
      TicketStatus from, TicketStatus to, boolean allowed, TicketAction action) {
    assertThat(TicketStateMachine.isAllowed(from, to)).isEqualTo(allowed);
    assertThat(TicketStateMachine.actionFor(from, to).orElse(null)).isEqualTo(action);
  }

  @ParameterizedTest(name = "{0} → {1}: reason={2}, note={3}")
  @CsvSource({
    "OPEN, CLOSED, true, false",
    "RESOLVED, IN_PROGRESS, true, false",
    "CLOSED, OPEN, true, false",
    "IN_PROGRESS, RESOLVED, false, true",
    "OPEN, IN_PROGRESS, false, false",
    "RESOLVED, CLOSED, false, false",
  })
  void knowsWhichMovesNeedAReasonOrANote(
      TicketStatus from, TicketStatus to, boolean reason, boolean note) {
    assertThat(TicketStateMachine.requiresReason(from, to)).isEqualTo(reason);
    assertThat(TicketStateMachine.requiresResolutionNote(to)).isEqualTo(note);
  }
}
