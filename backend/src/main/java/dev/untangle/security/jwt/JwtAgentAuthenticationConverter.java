package dev.untangle.security.jwt;

import dev.untangle.agent.application.AgentDirectory;
import dev.untangle.agent.application.AgentSnapshot;
import dev.untangle.security.AuthenticatedAgent;
import dev.untangle.security.rbac.Permission;
import dev.untangle.security.rbac.RolePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a <b>verified</b> JWT (signature, expiry and issuer already checked by the decoder) into
 * our {@link AgentAuthenticationToken}.
 *
 * <p>It looks the agent up by the token's subject (cached for a few seconds) and:
 *
 * <ul>
 *   <li>rejects deleted or deactivated agents → 401 even though the JWT itself is still valid
 *       (Section 6.1, edge case "deactivated agent with a still-valid JWT");
 *   <li>uses the agent's <b>current</b> role for permissions, not the role claim in the token.
 * </ul>
 *
 * Authorities = every {@link Permission} name plus {@code ROLE_<role>}, so
 * {@code @PreAuthorize("hasAuthority('AGENT_MANAGE')")} works.
 */
@Component
public class JwtAgentAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

  private final AgentDirectory agentDirectory;
  private final RolePermissions rolePermissions;

  public JwtAgentAuthenticationConverter(
      AgentDirectory agentDirectory, RolePermissions rolePermissions) {
    this.agentDirectory = agentDirectory;
    this.rolePermissions = rolePermissions;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    UUID agentId;
    try {
      agentId = UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new BadCredentialsException("Token subject is not an agent id");
    }

    AgentSnapshot agent =
        agentDirectory
            .findSnapshot(agentId)
            .orElseThrow(() -> new BadCredentialsException("Unknown agent"));
    if (!agent.active()) {
      throw new DisabledException("Agent is deactivated");
    }

    Set<Permission> permissions = rolePermissions.of(agent.role());
    List<GrantedAuthority> authorities = new ArrayList<>(permissions.size() + 1);
    permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
    authorities.add(new SimpleGrantedAuthority("ROLE_" + agent.role().name()));

    AuthenticatedAgent principal =
        new AuthenticatedAgent(
            agent.id(), agent.email(), agent.fullName(), agent.role(), permissions);
    return new AgentAuthenticationToken(principal, jwt, authorities);
  }
}
