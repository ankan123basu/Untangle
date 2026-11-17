package dev.untangle.agent.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.bootstrap-admin.*}: the first admin, created on startup only when no active admin
 * exists (A12). Lives in the agent feature, not in security config, so the dependency stays one-way
 * (security → agent).
 */
@ConfigurationProperties("app.bootstrap-admin")
public record BootstrapAdminProperties(String email, String password, String fullName) {

  public boolean isConfigured() {
    return email != null && !email.isBlank() && password != null && !password.isBlank();
  }

  @Override
  public String toString() {
    return "BootstrapAdminProperties[email=" + email + ", password=<redacted>]";
  }
}
