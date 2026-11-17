package dev.untangle.agent.application;

import dev.untangle.agent.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies for agent management, validated with Bean Validation at the controller. */
public final class AgentCommands {

  private AgentCommands() {}

  /**
   * Password: at least 10 characters (Section 6.1). At most 72, because BCrypt silently ignores
   * everything after byte 72, so allowing more would give a false sense of security.
   */
  public record CreateAgent(
      @NotBlank @Size(min = 2, max = 120) String fullName,
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 10, max = 72) String password,
      @NotNull Role role) {

    @Override
    public String toString() {
      return "CreateAgent[email=" + email + ", role=" + role + ", password=<redacted>]";
    }
  }

  /** Partial update: {@code null} fields are left unchanged. */
  public record UpdateAgent(@Size(min = 2, max = 120) String fullName, Role role) {}

  public record SetActive(@NotNull Boolean active) {}
}
