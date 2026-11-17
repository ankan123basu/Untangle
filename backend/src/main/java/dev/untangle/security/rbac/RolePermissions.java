package dev.untangle.security.rbac;

import static dev.untangle.security.rbac.Permission.*;

import dev.untangle.agent.domain.Role;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The RBAC matrix from MASTER_PROMPT §6.2, the single source of truth for role → permissions.
 *
 * <p>Permissions are computed on the server from the agent's <b>current</b> role on every request;
 * they are not baked into the JWT. Changing a role or this table therefore takes effect within the
 * principal cache TTL, without re-issuing tokens.
 *
 * <p>Roles are cumulative: LEAD has everything AGENT has, ADMIN everything LEAD has.
 */
@Component
public class RolePermissions {

  private static final Set<Permission> AGENT_BASE =
      EnumSet.of(
          TICKET_VIEW,
          DASHBOARD_VIEW,
          TICKET_CREATE,
          TICKET_SELF_ASSIGN,
          TICKET_TRANSITION_OWN,
          TICKET_COMMENT,
          TICKET_EDIT_OWN,
          TICKET_ESCALATE,
          AI_USE);

  private static final Set<Permission> LEAD_EXTRA =
      EnumSet.of(
          TICKET_ASSIGN_ANY,
          TICKET_TRANSITION_ANY,
          TICKET_CLOSE,
          TICKET_REOPEN,
          TICKET_EDIT_ANY,
          AGENT_WORKLOAD_VIEW);

  private static final Set<Permission> ADMIN_EXTRA =
      EnumSet.of(AGENT_MANAGE, PARTNER_MANAGE, WEBHOOK_LOG_VIEW);

  private final Map<Role, Set<Permission>> matrix = new EnumMap<>(Role.class);

  public RolePermissions(
      @Value("${app.tickets.agent-can-close-own-resolved:false}")
          boolean agentCanCloseOwnResolved) {
    Set<Permission> agent = EnumSet.copyOf(AGENT_BASE);
    if (agentCanCloseOwnResolved) {
      agent.add(TICKET_CLOSE_OWN_RESOLVED);
    }
    Set<Permission> lead = EnumSet.copyOf(agent);
    lead.addAll(LEAD_EXTRA);
    lead.add(TICKET_CLOSE_OWN_RESOLVED); // implied by TICKET_CLOSE, listed for clarity
    Set<Permission> admin = EnumSet.copyOf(lead);
    admin.addAll(ADMIN_EXTRA);

    matrix.put(Role.AGENT, Collections.unmodifiableSet(agent));
    matrix.put(Role.LEAD, Collections.unmodifiableSet(lead));
    matrix.put(Role.ADMIN, Collections.unmodifiableSet(admin));
  }

  public Set<Permission> of(Role role) {
    return matrix.get(role);
  }

  public boolean has(Role role, Permission permission) {
    return of(role).contains(permission);
  }
}
