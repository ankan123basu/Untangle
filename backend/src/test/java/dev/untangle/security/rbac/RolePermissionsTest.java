package dev.untangle.security.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The RBAC table of MASTER_PROMPT §6.2, transcribed row by row. If someone edits {@link
 * RolePermissions}, this test shows exactly which cell of the agreed matrix changed.
 */
class RolePermissionsTest {

  private final RolePermissions defaults = new RolePermissions(false);

  @ParameterizedTest(name = "{0}: AGENT={1} LEAD={2} ADMIN={3}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          # permission                 | AGENT | LEAD  | ADMIN
          TICKET_VIEW                  | true  | true  | true
          DASHBOARD_VIEW               | true  | true  | true
          TICKET_CREATE                | true  | true  | true
          TICKET_SELF_ASSIGN           | true  | true  | true
          TICKET_ASSIGN_ANY            | false | true  | true
          TICKET_TRANSITION_OWN        | true  | true  | true
          TICKET_TRANSITION_ANY        | false | true  | true
          TICKET_CLOSE                 | false | true  | true
          TICKET_CLOSE_OWN_RESOLVED    | false | true  | true
          TICKET_REOPEN                | false | true  | true
          TICKET_COMMENT               | true  | true  | true
          TICKET_EDIT_OWN              | true  | true  | true
          TICKET_EDIT_ANY              | false | true  | true
          TICKET_ESCALATE              | true  | true  | true
          AGENT_WORKLOAD_VIEW          | false | true  | true
          AI_USE                       | true  | true  | true
          AGENT_MANAGE                 | false | false | true
          PARTNER_MANAGE               | false | false | true
          WEBHOOK_LOG_VIEW             | false | false | true
          """)
  void matchesTheAgreedMatrix(Permission permission, boolean agent, boolean lead, boolean admin) {
    assertThat(defaults.has(Role.AGENT, permission)).as("AGENT").isEqualTo(agent);
    assertThat(defaults.has(Role.LEAD, permission)).as("LEAD").isEqualTo(lead);
    assertThat(defaults.has(Role.ADMIN, permission)).as("ADMIN").isEqualTo(admin);
  }

  @Test
  void everyPermissionIsCoveredByTheMatrixTest() {
    assertThat(Permission.values()).hasSize(19);
  }

  @Test
  void rolesAreCumulative() {
    assertThat(defaults.of(Role.LEAD)).containsAll(defaults.of(Role.AGENT));
    assertThat(defaults.of(Role.ADMIN)).containsAll(defaults.of(Role.LEAD));
  }

  @Test
  void agentsMayCloseTheirOwnResolvedTicketsWhenConfigured() {
    RolePermissions configured = new RolePermissions(true);
    assertThat(configured.has(Role.AGENT, Permission.TICKET_CLOSE_OWN_RESOLVED)).isTrue();
    assertThat(configured.has(Role.AGENT, Permission.TICKET_CLOSE)).isFalse();
  }
}
