package dev.untangle.security;

import dev.untangle.agent.domain.Role;
import dev.untangle.security.rbac.Permission;
import java.util.Set;
import java.util.UUID;

/**
 * Who is calling: the principal of every authenticated request. Controllers receive it with
 * {@code @AuthenticationPrincipal AuthenticatedAgent me}.
 *
 * <p>Built from the database (via a short-TTL cache), not from JWT claims, so role changes and
 * deactivation are honoured even while an older token is still valid.
 */
public record AuthenticatedAgent(
    UUID id, String email, String fullName, Role role, Set<Permission> permissions) {

  public boolean can(Permission permission) {
    return permissions.contains(permission);
  }
}
