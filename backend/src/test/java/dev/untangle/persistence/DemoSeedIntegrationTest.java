package dev.untangle.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.support.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Starts the app with the {@code demo} profile on a fresh database and checks that the demo data is
 * loaded and covers what a demo needs. Because the seed goes through the real V1 constraints, this
 * also proves the seed itself is valid.
 */
@SpringBootTest
@ActiveProfiles({"test", "demo"})
@Import(TestcontainersConfiguration.class)
class DemoSeedIntegrationTest {

  @Autowired JdbcTemplate jdbc;

  private long count(String sql) {
    Long value = jdbc.queryForObject(sql, Long.class);
    return value == null ? 0 : value;
  }

  private List<String> distinct(String column) {
    return jdbc.queryForList(
        "SELECT DISTINCT " + column + " FROM tickets ORDER BY 1", String.class);
  }

  @Test
  void seedsTheTeam() {
    assertThat(count("SELECT count(*) FROM agents WHERE role = 'ADMIN'")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM agents WHERE role = 'LEAD'")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM agents WHERE role = 'AGENT'")).isEqualTo(6);
    assertThat(count("SELECT count(*) FROM partners")).isEqualTo(1);
  }

  @Test
  void seedsSixtyTicketsCoveringEveryStatusPriorityCategoryAndSource() {
    assertThat(count("SELECT count(*) FROM tickets")).isEqualTo(60);
    assertThat(distinct("status"))
        .containsExactlyInAnyOrder("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
    assertThat(distinct("priority")).containsExactlyInAnyOrder("LOW", "MEDIUM", "HIGH", "CRITICAL");
    assertThat(distinct("category"))
        .containsExactlyInAnyOrder("BILLING", "TECHNICAL", "ACCOUNT", "REFUND", "GENERAL", "OTHER");
    assertThat(distinct("source")).containsExactlyInAnyOrder("MANUAL", "WEBHOOK");
  }

  @Test
  void seedsDashboardWorthyStates() {
    assertThat(count("SELECT count(*) FROM tickets WHERE status IN ('OPEN','IN_PROGRESS')"))
        .as("unresolved")
        .isPositive();
    assertThat(
            count(
                "SELECT count(*) FROM tickets WHERE status = 'OPEN' AND assigned_agent_id IS NULL"))
        .as("unassigned queue")
        .isPositive();
    assertThat(count("SELECT count(*) FROM tickets WHERE escalation_level > 0"))
        .as("escalated")
        .isPositive();
    assertThat(count("SELECT count(*) FROM notifications WHERE read_at IS NULL"))
        .as("unread")
        .isPositive();
  }

  @Test
  void everyResolvedOrClosedTicketHasAResolutionNote() {
    assertThat(
            count(
                "SELECT count(*) FROM tickets t WHERE t.status IN ('RESOLVED','CLOSED') AND NOT"
                    + " EXISTS (SELECT 1 FROM ticket_comments c WHERE c.ticket_id = t.id AND"
                    + " c.type = 'RESOLUTION_NOTE')"))
        .isZero();
  }

  @Test
  void newTicketsContinueAfterTheSeededNumbers() {
    assertThat(count("SELECT last_value FROM ticket_number_seq")).isGreaterThanOrEqualTo(60);
  }
}
