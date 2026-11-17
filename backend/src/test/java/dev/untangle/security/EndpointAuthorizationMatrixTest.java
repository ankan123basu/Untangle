package dev.untangle.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Every role × every protected endpoint, over real HTTP (MASTER_PROMPT §11: "test the full
 * permission matrix with a parameterised test").
 *
 * <p>"allowed" means the request got past authorisation, i.e. any status except 401/403. The bodies
 * are deliberately minimal, so an allowed call may still end in 400/404/409; that's fine, because
 * this test is only about the authorisation decision. Ticket endpoints join the matrix in Phase 3.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointAuthorizationMatrixTest extends IntegrationTest {

  private final Map<Role, String> bearers = new EnumMap<>(Role.class);

  @BeforeAll
  void signInOncePerRole() {
    for (Role role : Role.values()) {
      bearers.put(role, bearer(role));
    }
  }

  @AfterAll
  void cleanUp() {
    truncateAllTables();
  }

  @ParameterizedTest(name = "{0} {1} {2} → allowed={3}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          # role  | method | path                          | allowed | body
          AGENT   | GET    | /api/v1/auth/me               | true    |
          LEAD    | GET    | /api/v1/auth/me               | true    |
          ADMIN   | GET    | /api/v1/auth/me               | true    |
          AGENT   | GET    | /api/v1/agents                | true    |
          LEAD    | GET    | /api/v1/agents                | true    |
          ADMIN   | GET    | /api/v1/agents                | true    |
          AGENT   | GET    | /api/v1/admin/agents          | false   |
          LEAD    | GET    | /api/v1/admin/agents          | false   |
          ADMIN   | GET    | /api/v1/admin/agents          | true    |
          AGENT   | POST   | /api/v1/agents                | false   | {}
          LEAD    | POST   | /api/v1/agents                | false   | {}
          ADMIN   | POST   | /api/v1/agents                | true    | {}
          AGENT   | PATCH  | /api/v1/agents/{id}           | false   | {"role":"ADMIN"}
          LEAD    | PATCH  | /api/v1/agents/{id}           | false   | {"role":"ADMIN"}
          ADMIN   | PATCH  | /api/v1/agents/{id}           | true    | {"role":"ADMIN"}
          AGENT   | PATCH  | /api/v1/agents/{id}/active    | false   | {"active":false}
          LEAD    | PATCH  | /api/v1/agents/{id}/active    | false   | {"active":false}
          ADMIN   | PATCH  | /api/v1/agents/{id}/active    | true    | {"active":false}
          """)
  void enforcesThePermissionMatrix(
      Role role, HttpMethod method, String path, boolean allowed, String body) {
    var request =
        mvc.method(method)
            .uri(path.replace("{id}", UUID.randomUUID().toString()))
            .header(HttpHeaders.AUTHORIZATION, bearers.get(role));
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    MvcTestResult result = request.exchange();

    int status = result.getResponse().getStatus();
    if (allowed) {
      assertThat(status).as("%s should be allowed", role).isNotIn(401, 403);
    } else {
      assertThat(status).as("%s should be forbidden", role).isEqualTo(403);
      assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_DENIED");
    }
  }

  @ParameterizedTest(name = "anonymous {0} {1} → 401")
  @CsvSource({
    "GET, /api/v1/auth/me",
    "GET, /api/v1/agents",
    "GET, /api/v1/admin/agents",
    "POST, /api/v1/agents",
  })
  void anonymousCallersAreRejected(HttpMethod method, String path) {
    assertThat(mvc.method(method).uri(path)).hasStatus(401);
  }
}
