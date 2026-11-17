package dev.untangle.auth.application;

import dev.untangle.agent.application.AgentAccessChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * When an agent is deactivated, revoke all their refresh tokens, so they can't silently get new
 * access tokens. Access tokens they already hold stop working through the principal check in the
 * JWT converter.
 */
@Component
class SessionRevoker {

  private static final Logger log = LoggerFactory.getLogger(SessionRevoker.class);

  private final AuthService authService;

  SessionRevoker(AuthService authService) {
    this.authService = authService;
  }

  @TransactionalEventListener
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void onAccessChanged(AgentAccessChangedEvent event) {
    if (event.deactivated()) {
      int revoked = authService.revokeAllSessions(event.agentId());
      log.info("Revoked {} session(s) of deactivated agentId={}", revoked, event.agentId());
    }
  }
}
