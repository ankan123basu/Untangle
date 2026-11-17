package dev.untangle.agent.application;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.agent.infrastructure.AgentRepository;
import dev.untangle.common.util.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A12: there is no public sign-up, so a fresh installation needs a first admin. On startup, if no
 * active admin exists and {@code BOOTSTRAP_ADMIN_EMAIL}/{@code BOOTSTRAP_ADMIN_PASSWORD} are set,
 * create one. If an admin already exists (e.g. demo data), do nothing; this never overwrites
 * anyone.
 */
@Component
@EnableConfigurationProperties(BootstrapAdminProperties.class)
class BootstrapAdminRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);
  static final int MIN_PASSWORD_LENGTH = 10;

  private final AgentRepository agents;
  private final PasswordEncoder passwordEncoder;
  private final BootstrapAdminProperties config;

  BootstrapAdminRunner(
      AgentRepository agents, PasswordEncoder passwordEncoder, BootstrapAdminProperties config) {
    this.agents = agents;
    this.passwordEncoder = passwordEncoder;
    this.config = config;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (agents.countByRoleAndActiveTrue(Role.ADMIN) > 0) {
      return;
    }
    if (!config.isConfigured()) {
      log.warn(
          "No active admin exists. Set BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD and"
              + " restart to create the first admin.");
      return;
    }
    if (config.password().length() < MIN_PASSWORD_LENGTH) {
      throw new IllegalStateException(
          "BOOTSTRAP_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
    }
    if (agents.existsByEmailIgnoreCase(config.email())) {
      log.warn(
          "Bootstrap admin email {} belongs to an existing non-admin or inactive agent; not changing"
              + " it.",
          Masking.email(config.email()));
      return;
    }
    String name =
        config.fullName() == null || config.fullName().isBlank()
            ? "Administrator"
            : config.fullName();
    Agent admin =
        new Agent(name, config.email(), passwordEncoder.encode(config.password()), Role.ADMIN);
    agents.save(admin);
    log.info("Bootstrap admin created email={}", Masking.email(config.email()));
  }
}
