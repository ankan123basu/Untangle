package dev.untangle.agent.application;

import dev.untangle.agent.infrastructure.AgentRepository;
import dev.untangle.config.CacheConfig;
import java.util.Optional;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only agent lookups for other features (security, auth). Other features never touch {@code
 * AgentRepository} directly (ArchUnit rule); they come through here.
 */
@Service
public class AgentDirectory {

  private final AgentRepository agents;

  public AgentDirectory(AgentRepository agents) {
    this.agents = agents;
  }

  /**
   * Cached for {@code app.security.principal-cache-ttl} (30 s): called on <b>every</b>
   * authenticated request, so caching keeps it to roughly one query per agent per 30 s. {@link
   * AgentCacheEvictor} evicts it after any role/active change commits.
   */
  @Cacheable(cacheNames = CacheConfig.AGENT_PRINCIPAL, key = "#agentId")
  @Transactional(readOnly = true)
  public Optional<AgentSnapshot> findSnapshot(UUID agentId) {
    return agents.findById(agentId).map(AgentSnapshot::of);
  }

  /** Active leads, who are told about escalations and SLA breaches. */
  @Transactional(readOnly = true)
  public java.util.List<UUID> activeLeadIds() {
    return agents.findAllByRoleAndActiveTrue(dev.untangle.agent.domain.Role.LEAD).stream()
        .map(dev.untangle.agent.domain.Agent::getId)
        .toList();
  }

  /** Uncached, for decisions that must see the latest state (token refresh). */
  @Transactional(readOnly = true)
  public Optional<AgentSnapshot> findFresh(UUID agentId) {
    return agents.findById(agentId).map(AgentSnapshot::of);
  }
}
