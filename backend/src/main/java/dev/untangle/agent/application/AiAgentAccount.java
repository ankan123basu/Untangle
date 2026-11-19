package dev.untangle.agent.application;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.AgentKind;
import dev.untangle.agent.infrastructure.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The AI teammate's account (ADR-028): "Unty", a plain AGENT that can be assigned tickets but can
 * never sign in.
 *
 * <p>Created by the application, not by a migration, so a fresh database or a test reset always
 * gets it back. Admins can deactivate it from the Agents screen (the kill switch); this class never
 * re-activates it.
 */
@Service
public class AiAgentAccount {

  private static final Logger log = LoggerFactory.getLogger(AiAgentAccount.class);
  public static final String NAME = "Unty";
  public static final String EMAIL = "unty@untangle.local";

  private final AgentRepository agents;

  public AiAgentAccount(AgentRepository agents) {
    this.agents = agents;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    try {
      snapshot();
    } catch (RuntimeException e) {
      // Never block start-up for the AI teammate; it is created on first use instead.
      log.warn("Could not prepare the AI teammate yet: {}", e.getMessage());
    }
  }

  /** The AI teammate, created if it doesn't exist yet. Safe to call concurrently. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AgentSnapshot snapshot() {
    return agents.findFirstByKind(AgentKind.AI).map(AgentSnapshot::of).orElseGet(this::create);
  }

  private AgentSnapshot create() {
    try {
      Agent ai = agents.saveAndFlush(Agent.aiTeammate(NAME, EMAIL));
      log.info("AI teammate created id={}", ai.getId());
      return AgentSnapshot.of(ai);
    } catch (DataIntegrityViolationException raced) {
      // Another request created it at the same moment (unique index on kind = 'AI').
      return agents.findFirstByKind(AgentKind.AI).map(AgentSnapshot::of).orElseThrow(() -> raced);
    }
  }
}
