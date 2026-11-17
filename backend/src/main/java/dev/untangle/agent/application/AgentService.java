package dev.untangle.agent.application;

import dev.untangle.agent.application.AgentCommands.CreateAgent;
import dev.untangle.agent.application.AgentCommands.UpdateAgent;
import dev.untangle.agent.application.AgentViews.AgentAdminView;
import dev.untangle.agent.application.AgentViews.AgentOption;
import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.agent.infrastructure.AgentRepository;
import dev.untangle.common.error.ApiException;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.common.util.Masking;
import dev.untangle.ticket.application.TicketWorkloadQueries;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team management (who: admins; why: onboarding, role changes, offboarding).
 *
 * <p>Safety rules (MASTER_PROMPT §6.2), each with a test:
 *
 * <ul>
 *   <li>the last active admin can't be demoted or deactivated → {@code 409 LAST_ADMIN};
 *   <li>you can't deactivate yourself → {@code 409 CANNOT_DEACTIVATE_SELF};
 *   <li>an agent with unresolved tickets can't be deactivated → {@code 409 AGENT_HAS_OPEN_TICKETS}
 *       (reassign first, so no ticket is orphaned).
 * </ul>
 *
 * Agents are never deleted (A13).
 */
@Service
public class AgentService {

  private static final Logger log = LoggerFactory.getLogger(AgentService.class);

  private final AgentRepository agents;
  private final PasswordEncoder passwordEncoder;
  private final TicketWorkloadQueries ticketQueries;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public AgentService(
      AgentRepository agents,
      PasswordEncoder passwordEncoder,
      TicketWorkloadQueries ticketQueries,
      ApplicationEventPublisher events,
      Clock clock) {
    this.agents = agents;
    this.passwordEncoder = passwordEncoder;
    this.ticketQueries = ticketQueries;
    this.events = events;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<AgentOption> listActiveOptions() {
    return agents.findAllByActiveTrueOrderByFullNameAsc().stream().map(AgentOption::of).toList();
  }

  @Transactional(readOnly = true)
  public List<AgentAdminView> listForAdmin() {
    return agents.findAllByOrderByActiveDescFullNameAsc().stream()
        .map(a -> AgentAdminView.of(a, clock.instant()))
        .toList();
  }

  @Transactional
  public AgentAdminView create(CreateAgent command) {
    String email = command.email().strip();
    if (agents.existsByEmailIgnoreCase(email)) {
      throw new ApiException(ErrorCode.EMAIL_TAKEN, "An agent with this email already exists");
    }
    Agent agent =
        new Agent(
            command.fullName(), email, passwordEncoder.encode(command.password()), command.role());
    try {
      agents.saveAndFlush(agent);
    } catch (DataIntegrityViolationException raceLost) {
      // Two admins created the same email at the same moment; the unique index caught it.
      throw new ApiException(ErrorCode.EMAIL_TAKEN, "An agent with this email already exists");
    }
    log.info(
        "Agent created id={} role={} email={}",
        agent.getId(),
        agent.getRole(),
        Masking.email(email));
    return AgentAdminView.of(agent, clock.instant());
  }

  @Transactional
  public AgentAdminView update(UUID agentId, UpdateAgent command) {
    Agent agent = load(agentId);
    if (command.role() != null && command.role() != agent.getRole()) {
      if (agent.getRole() == Role.ADMIN && agent.isActive()) {
        ensureAnotherActiveAdminExists(agentId);
      }
      agent.changeRole(command.role());
      events.publishEvent(new AgentAccessChangedEvent(agentId, false));
      log.info("Agent role changed id={} role={}", agentId, command.role());
    }
    if (command.fullName() != null) {
      agent.rename(command.fullName());
    }
    agents.flush();
    return AgentAdminView.of(agent, clock.instant());
  }

  @Transactional
  public AgentAdminView setActive(UUID agentId, boolean active, UUID actingAgentId) {
    Agent agent = load(agentId);
    if (agent.isActive() == active) {
      return AgentAdminView.of(agent, clock.instant()); // idempotent
    }
    if (!active) {
      if (agentId.equals(actingAgentId)) {
        throw new ApiException(ErrorCode.CANNOT_DEACTIVATE_SELF);
      }
      if (agent.getRole() == Role.ADMIN) {
        ensureAnotherActiveAdminExists(agentId);
      }
      if (ticketQueries.hasUnresolvedTicketsAssignedTo(agentId)) {
        throw new ApiException(
            ErrorCode.AGENT_HAS_OPEN_TICKETS,
            "Reassign this agent's open and in-progress tickets before deactivating them");
      }
      agent.deactivate();
    } else {
      agent.activate();
    }
    agents.flush();
    events.publishEvent(new AgentAccessChangedEvent(agentId, !active));
    log.info("Agent {} id={}", active ? "activated" : "deactivated", agentId);
    return AgentAdminView.of(agent, clock.instant());
  }

  private Agent load(UUID agentId) {
    return agents.findById(agentId).orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND));
  }

  /** Row-locks all active admins, then checks someone other than {@code agentId} remains. */
  private void ensureAnotherActiveAdminExists(UUID agentId) {
    boolean anotherAdmin =
        agents.lockActiveByRole(Role.ADMIN).stream().anyMatch(a -> !a.getId().equals(agentId));
    if (!anotherAdmin) {
      throw new ApiException(ErrorCode.LAST_ADMIN, "At least one active admin must remain");
    }
  }
}
