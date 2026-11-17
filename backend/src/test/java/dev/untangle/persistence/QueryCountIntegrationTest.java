package dev.untangle.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * N+1 guard (§15): list endpoints must run a constant number of SQL statements however many rows
 * (and distinct assignees/authors) they return. Counted with Hibernate statistics.
 */
class QueryCountIntegrationTest extends IntegrationTest {

  private static final int ROWS = 12;

  @Autowired EntityManagerFactory entityManagerFactory;

  private Statistics stats;
  private String lead;
  private String firstTicketId;

  @BeforeEach
  void seed() {
    stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    lead = bearer(Role.LEAD);
    List<Agent> agents = new ArrayList<>();
    for (int i = 0; i < ROWS; i++) {
      Agent agent = createAgent(Role.AGENT);
      agents.add(agent);
      String body = VALID_TICKET.replace("}", ",\"assigneeId\":\"" + agent.getId() + "\"}");
      MvcTestResult created = post("/api/v1/tickets", lead, body);
      assertThat(created).hasStatus(201);
      if (i == 0) firstTicketId = id(created);
    }
    // Comments on one ticket from many different authors.
    for (Agent author : agents.subList(0, 6)) {
      assertThat(
              post(
                  "/api/v1/tickets/" + firstTicketId + "/comments",
                  bearer(author),
                  "{\"body\":\"Update from " + author.getFullName() + "\"}"))
          .hasStatus(201);
    }
    get("/api/v1/tickets", lead); // warm the principal cache so it isn't counted below
  }

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  private long statementsFor(String uri) {
    stats.clear();
    assertThat(get(uri, lead)).hasStatusOk();
    return stats.getPrepareStatementCount();
  }

  @Test
  void ticketListUsesAConstantNumberOfQueries() {
    long statements = statementsFor("/api/v1/tickets?size=50");
    // page query (assignee fetched in the same SELECT) + count query
    assertThat(statements).isBetween(1L, 3L);
  }

  @Test
  void timelineLoadsAuthorsWithTheComments() {
    long statements = statementsFor("/api/v1/tickets/" + firstTicketId + "/comments");
    // ticket existence check + comments joined with their authors (6 authors, still one query)
    assertThat(statements).isBetween(1L, 3L);
  }

  @Test
  void dashboardWorkloadIsOneAggregateQuery() {
    long statements = statementsFor("/api/v1/dashboard/agents-workload");
    assertThat(statements).isBetween(1L, 2L);
  }
}
