package dev.untangle.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Filters, search, sorting and pagination of {@code GET /tickets} (§9, §11 "Validation"). One fixed
 * data set is built once for the class; every test only reads.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TicketQueryIntegrationTest extends IntegrationTest {

  private Agent riya;
  private String riyaBearer;
  private String leadBearer;

  /**
   * Fixture (6 tickets):
   *
   * <pre>
   *  #  priority  category  status       assignee  extras
   *  A  CRITICAL  REFUND    OPEN         -         breached, escalated, customer "Zed 100% Corp"
   *  B  HIGH      BILLING   IN_PROGRESS  riya      breached
   *  C  MEDIUM    BILLING   OPEN         riya
   *  D  LOW       ACCOUNT   RESOLVED     riya
   *  E  LOW       TECHNICAL CLOSED       -
   *  F  HIGH      GENERAL   OPEN         -
   * </pre>
   */
  @BeforeAll
  void fixture() {
    riya = createAgent(Role.AGENT);
    riyaBearer = bearer(riya);
    leadBearer = bearer(Role.LEAD);
    UUID a = make("CRITICAL", "REFUND", "Zed 100% Corp");
    UUID b = make("HIGH", "BILLING", "Bilal");
    UUID c = make("MEDIUM", "BILLING", "Chitra");
    UUID d = make("LOW", "ACCOUNT", "Dev");
    UUID e = make("LOW", "TECHNICAL", "Esha");
    make("HIGH", "GENERAL", "Farah");
    jdbc.update(
        "UPDATE tickets SET sla_due_at = now() - interval '1 hour', escalation_level = 1 WHERE id = ?",
        a);
    jdbc.update(
        "UPDATE tickets SET assigned_agent_id = ?, status = 'IN_PROGRESS',"
            + " sla_due_at = now() - interval '5 minutes' WHERE id = ?",
        riya.getId(),
        b);
    jdbc.update("UPDATE tickets SET assigned_agent_id = ? WHERE id = ?", riya.getId(), c);
    jdbc.update(
        "UPDATE tickets SET assigned_agent_id = ?, status = 'RESOLVED', resolved_at = now()"
            + " WHERE id = ?",
        riya.getId(),
        d);
    jdbc.update("UPDATE tickets SET status = 'CLOSED', closed_at = now() WHERE id = ?", e);
  }

  @AfterAll
  void cleanUp() {
    truncateAllTables();
  }

  private UUID make(String priority, String category, String customer) {
    String body =
        VALID_TICKET
            .replace("\"HIGH\"", "\"" + priority + "\"")
            .replace("\"ACCOUNT\"", "\"" + category + "\"")
            .replace("Rahul Sharma", customer);
    return UUID.fromString(id(post("/api/v1/tickets", leadBearer, body)));
  }

  private MvcTestResult list(String query) {
    return get("/api/v1/tickets?" + query, riyaBearer);
  }

  private List<String> customers(String query) {
    MvcTestResult result = list(query);
    assertThat(result).hasStatusOk();
    return json(result, "$.content[*].customerName");
  }

  @ParameterizedTest(name = "?{0} → {1}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          status=OPEN                                  | Farah,Chitra,Zed 100% Corp
          status=OPEN&status=IN_PROGRESS               | Farah,Chitra,Bilal,Zed 100% Corp
          priority=LOW                                 | Esha,Dev
          priority=CRITICAL&priority=HIGH              | Farah,Bilal,Zed 100% Corp
          category=BILLING                             | Chitra,Bilal
          unassigned=true&status=OPEN                  | Farah,Zed 100% Corp
          mine=true                                    | Dev,Chitra,Bilal
          escalated=true                               | Zed 100% Corp
          slaBreached=true                             | Bilal,Zed 100% Corp
          q=chit                                       | Chitra
          q=100%                                       | Zed 100% Corp
          q=_                                          | ''
          status=CLOSED&priority=CRITICAL              | ''
          """)
  void filtersNewestFirstByDefault(String query, String expected) {
    List<String> want = expected.isEmpty() ? List.of() : List.of(expected.split(","));
    assertThat(customers(query)).containsExactlyElementsOf(want);
  }

  @Test
  void filtersByAssigneeId() {
    assertThat(customers("assigneeId=" + riya.getId())).containsExactly("Dev", "Chitra", "Bilal");
  }

  @Test
  void sortsByRealSeverityNotAlphabetically() {
    assertThat(customers("sort=priority,desc&status=OPEN&status=IN_PROGRESS"))
        .containsExactly("Zed 100% Corp", "Farah", "Bilal", "Chitra");
  }

  @Test
  void paginatesWithStableMetadata() {
    MvcTestResult page = list("size=4&page=1");
    assertThat(page).bodyJson().extractingPath("$.page").isEqualTo(1);
    assertThat(page).bodyJson().extractingPath("$.size").isEqualTo(4);
    assertThat(page).bodyJson().extractingPath("$.totalElements").isEqualTo(6);
    assertThat(page).bodyJson().extractingPath("$.totalPages").isEqualTo(2);
    assertThat((List<?>) json(page, "$.content")).hasSize(2);
  }

  @Test
  void listRowsCarryWhatTheTableNeeds() {
    MvcTestResult result = list("q=Bilal");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.content[0].assignee.fullName")
        .isEqualTo(riya.getFullName());
    assertThat(result).bodyJson().extractingPath("$.content[0].slaBreached").isEqualTo(true);
    assertThat(result).bodyJson().extractingPath("$.content[0].ticketNumber").isNotNull();
  }

  @ParameterizedTest(name = "?{0} → 400 on {1}")
  @CsvSource({
    "size=101, size",
    "size=0, size",
    "page=-1, page",
    "status=PENDING, status",
    "priority=high, priority",
    "sort=customerName, sort",
    "sort=createdAt;drop table, sort",
    "createdFrom=yesterday, createdFrom",
  })
  void rejectsInvalidParameters(String query, String field) {
    MvcTestResult result = list(query);
    assertThat(result).hasStatus(400);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    assertThat((List<String>) json(result, "$.errors[*].field")).contains(field);
  }

  @Test
  void createdRangeFilters() {
    assertThat(customers("createdFrom=2000-01-01T00:00:00Z")).hasSize(6);
    assertThat(customers("createdTo=2000-01-01T00:00:00Z")).isEmpty();
  }
}
