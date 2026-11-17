package dev.untangle.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * End-to-end auth behaviour over HTTP against a real database (MASTER_PROMPT §6.1, §11
 * "Auth/RBAC").
 */
class AuthIntegrationTest extends IntegrationTest {

  @Autowired JwtEncoder jwtEncoder;

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  private MvcTestResult me(String bearer) {
    return mvc.get().uri("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer).exchange();
  }

  private int failedAttempts(Agent agent) {
    return jdbc.queryForObject(
        "SELECT failed_login_attempts FROM agents WHERE id = ?", Integer.class, agent.getId());
  }

  // ---- Login --------------------------------------------------------------------------------

  @Test
  void loginReturnsShortLivedAccessTokenAndHardenedRefreshCookie() {
    Agent agent = createAgent(Role.AGENT);

    MvcTestResult result = login(agent.getEmail(), PASSWORD);

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.tokenType").isEqualTo("Bearer");
    assertThat(result).bodyJson().extractingPath("$.expiresIn").isEqualTo(900);
    assertThat(result).bodyJson().extractingPath("$.agent.role").isEqualTo("AGENT");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.agent.permissions")
        .asArray()
        .contains("TICKET_VIEW", "TICKET_SELF_ASSIGN")
        .doesNotContain("TICKET_ASSIGN_ANY", "AGENT_MANAGE");
    assertThat(body(result)).doesNotContain("refreshToken");

    String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    assertThat(setCookie)
        .startsWith("untangle_refresh=")
        .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=604800");
    assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
  }

  @Test
  void accessTokenAuthenticatesTheCaller() {
    Agent agent = createAgent(Role.LEAD);

    MvcTestResult result = me(bearer(agent));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(agent.getId().toString());
    assertThat(result).bodyJson().extractingPath("$.role").isEqualTo("LEAD");
  }

  @Test
  void emailIsCaseInsensitive() {
    Agent agent = createAgent(Role.AGENT);
    assertThat(login(agent.getEmail().toUpperCase(), PASSWORD)).hasStatusOk();
  }

  @Test
  void wrongPasswordAndUnknownEmailAreIndistinguishable() {
    Agent agent = createAgent(Role.AGENT);

    MvcTestResult wrongPassword = login(agent.getEmail(), "Wrong-Password-1");
    MvcTestResult unknownEmail = login("nobody-" + UUID.randomUUID() + "@untangle.test", PASSWORD);

    for (MvcTestResult result : new MvcTestResult[] {wrongPassword, unknownEmail}) {
      assertThat(result).hasStatus(401);
      assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTH_INVALID_CREDENTIALS");
      assertThat(result)
          .bodyJson()
          .extractingPath("$.detail")
          .isEqualTo("Invalid email or password");
    }
  }

  @Test
  void accountLocksAfterFiveFailedAttemptsAndRejectsEvenTheRightPassword() {
    Agent agent = createAgent(Role.AGENT);
    for (int attempt = 1; attempt <= 4; attempt++) {
      assertThat(login(agent.getEmail(), "Wrong-Password-1")).hasStatus(401);
    }

    MvcTestResult fifth = login(agent.getEmail(), "Wrong-Password-1");
    assertThat(fifth).hasStatus(423);
    assertThat(fifth).bodyJson().extractingPath("$.code").isEqualTo("AUTH_ACCOUNT_LOCKED");
    assertThat(fifth).bodyJson().extractingPath("$.lockedUntil").isNotNull();

    MvcTestResult rightPasswordWhileLocked = login(agent.getEmail(), PASSWORD);
    assertThat(rightPasswordWhileLocked).hasStatus(423);
  }

  @Test
  void loginWorksAgainOnceTheLockExpires() {
    Agent agent = createAgent(Role.AGENT);
    jdbc.update(
        "UPDATE agents SET locked_until = now() - interval '1 second' WHERE id = ?", agent.getId());

    assertThat(login(agent.getEmail(), PASSWORD)).hasStatusOk();
    assertThat(
            jdbc.queryForObject(
                "SELECT locked_until IS NULL FROM agents WHERE id = ?",
                Boolean.class,
                agent.getId()))
        .isTrue();
  }

  @Test
  void successfulLoginResetsTheFailureCounter() {
    Agent agent = createAgent(Role.AGENT);
    login(agent.getEmail(), "Wrong-Password-1");
    login(agent.getEmail(), "Wrong-Password-1");
    assertThat(failedAttempts(agent)).isEqualTo(2);

    assertThat(login(agent.getEmail(), PASSWORD)).hasStatusOk();
    assertThat(failedAttempts(agent)).isZero();
  }

  @Test
  void deactivatedAgentCannotLogIn() {
    Agent agent = createAgent(Role.AGENT);
    jdbc.update("UPDATE agents SET active = false WHERE id = ?", agent.getId());

    MvcTestResult result = login(agent.getEmail(), PASSWORD);

    assertThat(result).hasStatus(401);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTH_ACCOUNT_DISABLED");
  }

  @Test
  void loginIsRateLimitedPerEmail() {
    String email = "spray-" + UUID.randomUUID() + "@untangle.test";
    for (int i = 0; i < 10; i++) {
      assertThat(login(email, "guess-" + i)).hasStatus(401);
    }

    MvcTestResult limited = login(email, "guess-11");

    assertThat(limited).hasStatus(429);
    assertThat(limited).bodyJson().extractingPath("$.code").isEqualTo("RATE_LIMITED");
    assertThat(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
  }

  @Test
  void loginValidatesItsBody() {
    MvcTestResult result =
        mvc.post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"\"}")
            .exchange();

    assertThat(result).hasStatus(400);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .contains("email", "password");
  }

  // ---- Refresh: rotation and reuse detection ------------------------------------------------

  @Test
  void refreshRotatesTheTokenAndIssuesANewAccessToken() {
    Agent agent = createAgent(Role.AGENT);
    Cookie first = refreshCookie(login(agent.getEmail(), PASSWORD));

    MvcTestResult refreshed = refresh(first);

    assertThat(refreshed).hasStatusOk();
    assertThat(refreshed).bodyJson().extractingPath("$.accessToken").isNotNull();
    Cookie second = refreshCookie(refreshed);
    assertThat(second.getValue()).isNotEqualTo(first.getValue());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE agent_id = ? AND revoked_at IS NOT NULL"
                    + " AND replaced_by_id IS NOT NULL",
                Long.class,
                agent.getId()))
        .as("old token marked as rotated")
        .isEqualTo(1);
  }

  @Test
  void reusingARotatedRefreshTokenRevokesTheWholeSession() {
    Agent agent = createAgent(Role.AGENT);
    Cookie stolen = refreshCookie(login(agent.getEmail(), PASSWORD));
    Cookie legitimate = refreshCookie(refresh(stolen));

    MvcTestResult reuse = refresh(stolen);
    assertThat(reuse).hasStatus(401);
    assertThat(reuse).bodyJson().extractingPath("$.code").isEqualTo("AUTH_REFRESH_REUSED");

    MvcTestResult afterRevocation = refresh(legitimate);
    assertThat(afterRevocation).as("the newer token of the same family is dead too").hasStatus(401);
  }

  @Test
  void refreshWithoutCookieIsRejected() {
    MvcTestResult result = refresh(null);
    assertThat(result).hasStatus(401);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTH_REFRESH_INVALID");
  }

  @Test
  void expiredRefreshTokenIsRejected() {
    Agent agent = createAgent(Role.AGENT);
    Cookie cookie = refreshCookie(login(agent.getEmail(), PASSWORD));
    jdbc.update(
        "UPDATE refresh_tokens SET created_at = now() - interval '8 days',"
            + " expires_at = now() - interval '1 day' WHERE agent_id = ?",
        agent.getId());

    MvcTestResult result = refresh(cookie);

    assertThat(result).hasStatus(401);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTH_REFRESH_INVALID");
  }

  @Test
  void logoutRevokesTheSessionAndClearsTheCookie() {
    Agent agent = createAgent(Role.AGENT);
    Cookie cookie = refreshCookie(login(agent.getEmail(), PASSWORD));

    MvcTestResult logout = mvc.post().uri("/api/v1/auth/logout").cookie(cookie).exchange();

    assertThat(logout).hasStatus(204);
    assertThat(logout.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    assertThat(refresh(cookie)).hasStatus(401);
  }

  // ---- Access token validation --------------------------------------------------------------

  @Test
  void missingTokenIsRejectedWithProblemDetails() {
    MvcTestResult result = mvc.get().uri("/api/v1/auth/me").exchange();

    assertThat(result).hasStatus(401);
    assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTH_UNAUTHENTICATED");
    assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
  }

  @Test
  void expiredAccessTokenIsReportedAsExpired() {
    Agent agent = createAgent(Role.AGENT);
    Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
    String token = sign(jwtEncoder, agent.getId(), past, past.plus(15, ChronoUnit.MINUTES));

    MvcTestResult result = me("Bearer " + token);

    assertThat(result).hasStatus(401);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTH_TOKEN_EXPIRED");
  }

  @Test
  void tamperedAccessTokenIsRejected() {
    String bearer = bearer(Role.AGENT);
    String tampered = bearer.substring(0, bearer.length() - 4) + "AAAA";

    assertThat(me(tampered)).hasStatus(401);
  }

  @Test
  void tokenSignedWithAnotherKeyIsRejected() {
    Agent agent = createAgent(Role.ADMIN);
    JwtEncoder attackerEncoder =
        NimbusJwtEncoder.withSecretKey(new SecretKeySpec(new byte[32], "HmacSHA256"))
            .algorithm(MacAlgorithm.HS256)
            .build();
    Instant now = Instant.now();

    MvcTestResult result =
        me("Bearer " + sign(attackerEncoder, agent.getId(), now, now.plus(5, ChronoUnit.MINUTES)));

    assertThat(result).hasStatus(401);
  }

  @Test
  void deactivatedAgentIsRejectedEvenWithAStillValidToken() {
    Agent agent = createAgent(Role.AGENT);
    String agentBearer = bearer(agent);
    Cookie agentSession = refreshCookie(login(agent.getEmail(), PASSWORD));
    assertThat(me(agentBearer)).hasStatusOk();

    MvcTestResult deactivate =
        mvc.patch()
            .uri("/api/v1/agents/{id}/active", agent.getId())
            .header(HttpHeaders.AUTHORIZATION, bearer(Role.ADMIN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"active\":false}")
            .exchange();
    assertThat(deactivate).hasStatusOk();

    MvcTestResult afterwards = me(agentBearer);
    assertThat(afterwards).hasStatus(401);
    assertThat(afterwards).bodyJson().extractingPath("$.code").isEqualTo("AUTH_ACCOUNT_DISABLED");
    assertThat(refresh(agentSession)).as("sessions were revoked too").hasStatus(401);
  }

  @Test
  void roleChangesApplyWithoutReissuingTheToken() {
    Agent lead = createAgent(Role.LEAD);
    String leadBearer = bearer(lead);
    assertThat(mvc.get().uri("/api/v1/admin/agents").header(HttpHeaders.AUTHORIZATION, leadBearer))
        .hasStatus(403);

    assertThat(
            mvc.patch()
                .uri("/api/v1/agents/{id}", lead.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
        .hasStatusOk();

    assertThat(mvc.get().uri("/api/v1/admin/agents").header(HttpHeaders.AUTHORIZATION, leadBearer))
        .as("same token, new role")
        .hasStatusOk();
  }

  private static String sign(
      JwtEncoder encoder, UUID subject, Instant issuedAt, Instant expiresAt) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("untangle")
            .subject(subject.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("role", "ADMIN")
            .build();
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
        .getTokenValue();
  }
}
