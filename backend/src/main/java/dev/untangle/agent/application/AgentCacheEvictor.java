package dev.untangle.agent.application;

import dev.untangle.config.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts the cached principal <b>after</b> the change commits. Evicting earlier (inside the
 * transaction) would let a concurrent request re-cache the old state for another 30 seconds.
 */
@Component
class AgentCacheEvictor {

  private final CacheManager cacheManager;

  AgentCacheEvictor(CacheManager cacheManager) {
    this.cacheManager = cacheManager;
  }

  @TransactionalEventListener
  void onAccessChanged(AgentAccessChangedEvent event) {
    Cache cache = cacheManager.getCache(CacheConfig.AGENT_PRINCIPAL);
    if (cache != null) {
      cache.evict(event.agentId());
    }
  }
}
