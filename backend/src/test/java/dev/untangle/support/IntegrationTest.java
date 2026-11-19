package dev.untangle.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.agent.infrastructure.AgentRepository;
import dev.untangle.auth.api.AuthController;
import jakarta.servlet.http.Cookie;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Base class for tests that need the real database and/or the HTTP API.
 *
 * <p>Every subclass shares one Spring context and therefore one PostgreSQL container. Tests that
 * commit data (all HTTP tests, concurrency tests) must call {@link #truncateAllTables()}
 * afterwards. Plain repository tests can use {@code @Transactional}, which rolls back
 * automatically.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

  public static final String PASSWORD = "Correct-Horse-42";

  private static final List<String> APP_TABLES =
      List.of(
          "ai_proposals",
          "agent_routing_skills",
          "agent_routing",
          "auto_assign_settings",
          "ticket_ai_insights",
          "notifications",
          "refresh_tokens",
          "webhook_deliveries",
          "ticket_comments",
          "tickets",
          "partners",
          "agents",
          "shedlock");

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected MockMvcTester mvc;
  @Autowired protected AgentRepository agentRepository;
  @Autowired protected PasswordEncoder passwordEncoder;
  @Autowired private List<CacheManager> cacheManagers;

  /** Empties the database and every cache (e.g. the 30 s dashboard cache) between tests. */
  protected void truncateAllTables() {
    jdbc.execute("TRUNCATE " + String.join(", ", APP_TABLES) + " CASCADE");
    clearCaches();
  }

  protected void clearCaches() {
    for (CacheManager manager : cacheManagers) {
      for (String name : manager.getCacheNames()) {
        Cache cache = manager.getCache(name);
        if (cache != null) cache.clear();
      }
    }
  }

  // ---- Agents & sessions --------------------------------------------------------------------

  /** Saves an agent whose password is {@link #PASSWORD}. */
  protected Agent createAgent(Role role) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return agentRepository.save(
        new Agent(
            role.name().charAt(0) + role.name().substring(1).toLowerCase() + " " + unique,
            role.name().toLowerCase() + "." + unique + "@untangle.test",
            passwordEncoder.encode(PASSWORD),
            role));
  }

  protected MvcTestResult login(String email, String password) {
    return mvc.post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))
        .exchange();
  }

  /** Logs in and returns {@code "Bearer <jwt>"}. */
  protected String bearer(Agent agent) {
    MvcTestResult result = login(agent.getEmail(), PASSWORD);
    assertThat(result).hasStatusOk();
    return "Bearer " + json(result, "$.accessToken");
  }

  /** Creates an agent with the role and returns a bearer header for them. */
  protected String bearer(Role role) {
    return bearer(createAgent(role));
  }

  protected static Cookie refreshCookie(MvcTestResult result) {
    Cookie cookie = result.getResponse().getCookie(AuthController.REFRESH_COOKIE);
    assertThat(cookie).as("refresh cookie").isNotNull();
    return cookie;
  }

  protected MvcTestResult refresh(Cookie cookie) {
    var request = mvc.post().uri("/api/v1/auth/refresh");
    return cookie == null ? request.exchange() : request.cookie(cookie).exchange();
  }

  // ---- Generic HTTP helpers -----------------------------------------------------------------

  protected MvcTestResult call(
      org.springframework.http.HttpMethod method, String uri, String bearer, String jsonBody) {
    var request = mvc.method(method).uri(uri);
    if (bearer != null) {
      request = request.header(org.springframework.http.HttpHeaders.AUTHORIZATION, bearer);
    }
    if (jsonBody != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(jsonBody);
    }
    return request.exchange();
  }

  protected MvcTestResult get(String uri, String bearer) {
    return call(org.springframework.http.HttpMethod.GET, uri, bearer, null);
  }

  protected MvcTestResult post(String uri, String bearer, String json) {
    return call(org.springframework.http.HttpMethod.POST, uri, bearer, json);
  }

  // ---- Tickets ------------------------------------------------------------------------------

  public static final String VALID_TICKET =
      """
      {"subject":"Card blocked abroad","description":"My card was blocked while travelling.",
       "customerName":"Rahul Sharma","customerEmail":"rahul@example.com",
       "category":"ACCOUNT","priority":"HIGH"}\
      """;

  /** Creates a ticket through the API and returns its detail response. */
  protected MvcTestResult createTicket(String bearer) {
    MvcTestResult result = post("/api/v1/tickets", bearer, VALID_TICKET);
    assertThat(result).hasStatus(201);
    return result;
  }

  protected static String id(MvcTestResult ticket) {
    return json(ticket, "$.id");
  }

  protected static long version(MvcTestResult ticket) {
    return ((Number) json(ticket, "$.version")).longValue();
  }

  // ---- JSON ---------------------------------------------------------------------------------

  protected static <T> T json(MvcTestResult result, String path) {
    return JsonPath.read(body(result), path);
  }

  /** The raw response body, for "must not contain" checks. */
  protected static String body(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
