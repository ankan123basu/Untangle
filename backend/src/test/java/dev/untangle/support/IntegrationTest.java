package dev.untangle.support;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for tests that need the real database.
 *
 * <p>Every subclass shares one Spring context and therefore one PostgreSQL container. Tests that
 * commit data (concurrency, optimistic locking) must call {@link #truncateAllTables()} afterwards
 * so they don't leak rows into other tests. Plain repository tests use {@code @Transactional},
 * which rolls back automatically.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

  private static final List<String> APP_TABLES =
      List.of(
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

  protected void truncateAllTables() {
    jdbc.execute("TRUNCATE " + String.join(", ", APP_TABLES) + " CASCADE");
  }
}
