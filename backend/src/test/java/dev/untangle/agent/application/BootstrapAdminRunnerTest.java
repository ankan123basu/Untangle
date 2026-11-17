package dev.untangle.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.agent.infrastructure.AgentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

/** A12: the first admin comes from environment variables, and only when none exists. */
class BootstrapAdminRunnerTest {

  private final AgentRepository agents = mock(AgentRepository.class);

  private BootstrapAdminRunner runner(String email, String password) {
    return new BootstrapAdminRunner(
        agents,
        NoOpPasswordEncoder.getInstance(),
        new BootstrapAdminProperties(email, password, "First Admin"));
  }

  @Test
  void createsTheFirstAdminWhenNoneExists() {
    when(agents.countByRoleAndActiveTrue(Role.ADMIN)).thenReturn(0L);

    runner("first@untangle.test", "Long-Enough-1").run(null);

    ArgumentCaptor<Agent> saved = ArgumentCaptor.forClass(Agent.class);
    verify(agents).save(saved.capture());
    assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
    assertThat(saved.getValue().getEmail()).isEqualTo("first@untangle.test");
  }

  @Test
  void doesNothingWhenAnAdminAlreadyExists() {
    when(agents.countByRoleAndActiveTrue(Role.ADMIN)).thenReturn(1L);

    runner("first@untangle.test", "Long-Enough-1").run(null);

    verify(agents, never()).save(any());
  }

  @Test
  void doesNothingWhenNotConfigured() {
    when(agents.countByRoleAndActiveTrue(Role.ADMIN)).thenReturn(0L);

    runner(null, null).run(null);

    verify(agents, never()).save(any());
  }

  @Test
  void refusesAWeakBootstrapPassword() {
    when(agents.countByRoleAndActiveTrue(Role.ADMIN)).thenReturn(0L);

    assertThatThrownBy(() -> runner("first@untangle.test", "short").run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least 10");
  }

  @Test
  void neverTakesOverAnExistingAccount() {
    when(agents.countByRoleAndActiveTrue(Role.ADMIN)).thenReturn(0L);
    when(agents.existsByEmailIgnoreCase("taken@untangle.test")).thenReturn(true);

    runner("taken@untangle.test", "Long-Enough-1").run(null);

    verify(agents, never()).save(any());
  }
}
