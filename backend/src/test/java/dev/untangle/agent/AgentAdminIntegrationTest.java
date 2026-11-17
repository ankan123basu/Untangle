package dev.untangle.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import dev.untangle.support.TestData;
import dev.untangle.ticket.domain.Ticket;
import dev.untangle.ticket.infrastructure.TicketRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Team management rules (MASTER_PROMPT §6.2 "Admin safety rules"). */
class AgentAdminIntegrationTest extends IntegrationTest {

  @Autowired TicketRepository tickets;

  private Agent admin;
  private String adminBearer;

  @BeforeEach
  void signInAsAdmin() {
    admin = createAgent(Role.ADMIN);
    adminBearer = bearer(admin);
  }

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  private MvcTestResult post(String json) {
    return mvc.post()
        .uri("/api/v1/agents")
        .header(HttpHeaders.AUTHORIZATION, adminBearer)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json)
        .exchange();
  }

  private MvcTestResult patch(String uri, UUID id, String json) {
    return mvc.patch()
        .uri(uri, id)
        .header(HttpHeaders.AUTHORIZATION, adminBearer)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json)
        .exchange();
  }

  private MvcTestResult setActive(UUID id, boolean active) {
    return patch("/api/v1/agents/{id}/active", id, "{\"active\":" + active + "}");
  }

  // ---- Create -------------------------------------------------------------------------------

  @Test
  void adminCreatesAnAgentWhoCanThenSignIn() {
    MvcTestResult created =
        post(
            """
            {"fullName":"Riya Sen","email":"riya.new@untangle.test",
             "password":"A-Strong-Passw0rd","role":"AGENT"}\
            """);

    assertThat(created).hasStatus(201);
    assertThat(created).bodyJson().extractingPath("$.role").isEqualTo("AGENT");
    assertThat(created).bodyJson().extractingPath("$.active").isEqualTo(true);
    assertThat(body(created)).doesNotContain("password");
    assertThat(login("riya.new@untangle.test", "A-Strong-Passw0rd")).hasStatusOk();
  }

  @Test
  void rejectsPasswordsShorterThanTenCharacters() {
    MvcTestResult result =
        post(
            """
            {"fullName":"Riya Sen","email":"riya.short@untangle.test",
             "password":"short","role":"AGENT"}\
            """);

    assertThat(result).hasStatus(400);
    assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("password");
    assertThat(body(result)).as("rejected values are never echoed").doesNotContain("\"short\"");
  }

  @Test
  void rejectsUnknownRole() {
    MvcTestResult result =
        post(
            """
            {"fullName":"Riya Sen","email":"riya.role@untangle.test",
             "password":"A-Strong-Passw0rd","role":"SUPERUSER"}\
            """);

    assertThat(result).hasStatus(400);
    assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("role");
  }

  @Test
  void rejectsDuplicateEmailIgnoringCase() {
    MvcTestResult result =
        post(
            """
            {"fullName":"Copy Cat","email":"%s",
             "password":"A-Strong-Passw0rd","role":"AGENT"}\
            """
                .formatted(admin.getEmail().toUpperCase()));

    assertThat(result).hasStatus(409);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("EMAIL_TAKEN");
  }

  // ---- Last admin & self --------------------------------------------------------------------

  @Test
  void lastActiveAdminCannotBeDemoted() {
    MvcTestResult result = patch("/api/v1/agents/{id}", admin.getId(), "{\"role\":\"LEAD\"}");

    assertThat(result).hasStatus(409);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("LAST_ADMIN");
  }

  @Test
  void adminCanBeDemotedWhileAnotherActiveAdminRemains() {
    Agent second = createAgent(Role.ADMIN);

    assertThat(patch("/api/v1/agents/{id}", second.getId(), "{\"role\":\"LEAD\"}")).hasStatusOk();
  }

  @Test
  void lastAdminRuleIgnoresDeactivatedAdmins() {
    Agent second = createAgent(Role.ADMIN);
    assertThat(setActive(second.getId(), false)).hasStatusOk();

    MvcTestResult result = patch("/api/v1/agents/{id}", admin.getId(), "{\"role\":\"AGENT\"}");

    assertThat(result).hasStatus(409);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("LAST_ADMIN");
  }

  @Test
  void adminCannotDeactivateThemselves() {
    MvcTestResult result = setActive(admin.getId(), false);

    assertThat(result).hasStatus(409);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CANNOT_DEACTIVATE_SELF");
  }

  // ---- Deactivation -------------------------------------------------------------------------

  @Test
  void agentWithUnresolvedTicketsCannotBeDeactivated() {
    Agent agent = createAgent(Role.AGENT);
    jdbc.update(
        "UPDATE tickets SET assigned_agent_id = ? WHERE id = ?",
        agent.getId(),
        tickets.save(TestData.manualTicket(agent)).getId());

    MvcTestResult result = setActive(agent.getId(), false);

    assertThat(result).hasStatus(409);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AGENT_HAS_OPEN_TICKETS");
  }

  @Test
  void agentWithoutOpenTicketsIsDeactivatedAndCanBeReactivated() {
    Agent agent = createAgent(Role.AGENT);
    Ticket closedOne = tickets.save(TestData.manualTicket(agent));
    jdbc.update(
        "UPDATE tickets SET assigned_agent_id = ?, status = 'CLOSED', resolved_at = now(),"
            + " closed_at = now() WHERE id = ?",
        agent.getId(),
        closedOne.getId());

    MvcTestResult deactivated = setActive(agent.getId(), false);
    assertThat(deactivated).hasStatusOk();
    assertThat(deactivated).bodyJson().extractingPath("$.active").isEqualTo(false);
    assertThat(login(agent.getEmail(), PASSWORD)).hasStatus(401);

    assertThat(setActive(agent.getId(), true)).hasStatusOk();
    assertThat(login(agent.getEmail(), PASSWORD)).hasStatusOk();
  }

  @Test
  void deactivationIsIdempotent() {
    Agent agent = createAgent(Role.AGENT);
    assertThat(setActive(agent.getId(), false)).hasStatusOk();
    assertThat(setActive(agent.getId(), false)).hasStatusOk();
  }

  @Test
  void unknownAgentIs404() {
    MvcTestResult result = setActive(UUID.randomUUID(), false);

    assertThat(result).hasStatus(404);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AGENT_NOT_FOUND");
  }

  // ---- Listing ------------------------------------------------------------------------------

  @Test
  void everyoneSeesActiveAgentsWithoutEmails() {
    Agent inactive = createAgent(Role.AGENT);
    jdbc.update("UPDATE agents SET active = false WHERE id = ?", inactive.getId());

    MvcTestResult result =
        mvc.get()
            .uri("/api/v1/agents")
            .header(HttpHeaders.AUTHORIZATION, bearer(Role.AGENT))
            .exchange();

    assertThat(result).hasStatusOk();
    assertThat(result)
        .bodyJson()
        .extractingPath("$[*].id")
        .asArray()
        .contains(admin.getId().toString())
        .doesNotContain(inactive.getId().toString());
    assertThat(body(result)).doesNotContain("@untangle.test");
  }

  @Test
  void adminListIncludesInactiveAgentsAndEmails() {
    Agent inactive = createAgent(Role.AGENT);
    jdbc.update("UPDATE agents SET active = false WHERE id = ?", inactive.getId());

    MvcTestResult result =
        mvc.get()
            .uri("/api/v1/admin/agents")
            .header(HttpHeaders.AUTHORIZATION, adminBearer)
            .exchange();

    assertThat(result).hasStatusOk();
    assertThat(result)
        .bodyJson()
        .extractingPath("$[*].email")
        .asArray()
        .contains(inactive.getEmail(), admin.getEmail());
  }
}
