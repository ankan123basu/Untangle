package dev.untangle.security.jwt;

import dev.untangle.security.AuthenticatedAgent;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Spring Security's view of an authenticated request: our principal plus its authorities. */
public class AgentAuthenticationToken extends AbstractAuthenticationToken {

  private final AuthenticatedAgent principal;
  private final transient Jwt jwt;

  public AgentAuthenticationToken(
      AuthenticatedAgent principal, Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.principal = principal;
    this.jwt = jwt;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return jwt;
  }

  @Override
  public AuthenticatedAgent getPrincipal() {
    return principal;
  }

  @Override
  public String getName() {
    return principal.id().toString();
  }
}
