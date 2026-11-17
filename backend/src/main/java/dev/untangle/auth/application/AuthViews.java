package dev.untangle.auth.application;

import dev.untangle.agent.domain.Role;
import dev.untangle.security.rbac.Permission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.SortedSet;
import java.util.UUID;

/** Request and response shapes of the auth API. */
public final class AuthViews {

  private AuthViews() {}

  public record LoginRequest(
      @NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 200) String password) {

    @Override
    public String toString() {
      return "LoginRequest[email=<masked>, password=<redacted>]";
    }
  }

  /** The signed-in user, including permissions so the UI can hide actions it can't perform. */
  public record CurrentAgent(
      UUID id, String fullName, String email, Role role, SortedSet<Permission> permissions) {}

  /**
   * Body of login and refresh. The refresh token is <b>not</b> here: it travels only in an HttpOnly
   * cookie that JavaScript can't read.
   */
  public record TokenResponse(
      String accessToken,
      String tokenType,
      long expiresIn,
      Instant expiresAt,
      CurrentAgent agent) {}
}
