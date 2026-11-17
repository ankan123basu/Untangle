package dev.untangle.agent.application;

import java.util.UUID;

/**
 * Published when an agent's role or active flag changes. Listeners react <b>after commit</b>:
 *
 * <ul>
 *   <li>{@link AgentCacheEvictor} drops the cached principal, so the change applies on the next
 *       request;
 *   <li>auth revokes all refresh tokens when {@code deactivated} is true.
 * </ul>
 *
 * An event (rather than the agent feature calling auth directly) keeps the dependency one-way: auth
 * depends on agent, never the reverse.
 */
public record AgentAccessChangedEvent(UUID agentId, boolean deactivated) {}
