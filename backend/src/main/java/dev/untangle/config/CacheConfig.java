package dev.untangle.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-memory caches (Caffeine). Each cache gets an explicit TTL and size bound, never "forever".
 *
 * <ul>
 *   <li>{@value #AGENT_PRINCIPAL}: the agent behind a JWT (role, active flag). TTL is short (30 s)
 *       because it decides whether a deactivated agent is still let in. The instance that makes a
 *       change evicts the entry immediately; other instances catch up within the TTL (R8).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig {

  public static final String AGENT_PRINCIPAL = "agentPrincipal";

  @Bean
  CacheManager cacheManager(
      @Value("${app.security.principal-cache-ttl:30s}") Duration principalTtl) {
    CaffeineCacheManager manager = new CaffeineCacheManager();
    manager.registerCustomCache(
        AGENT_PRINCIPAL,
        Caffeine.newBuilder().expireAfterWrite(principalTtl).maximumSize(10_000).build());
    return manager;
  }
}
