package dev.untangle.escalation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import dev.untangle.ticket.application.SlaBreachProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** SLA escalation (§5.4, R4; §11 "scheduler escalates once per level, idempotent"). */
class SlaEscalationIntegrationTest extends IntegrationTest {

  @Autowired SlaBreachProcessor processor;
  @Autowired LockProvider lockProvider;

  private Agent lead;
  private Agent agent;
  private String leadBearer;

  @BeforeEach
  void setUp() {
    lead = createAgent(Role.LEAD);
    leadBearer = bearer(lead);
    agent = createAgent(Role.AGENT);
  }

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  /** A HIGH (4 h target) ticket whose SLA clock started {@code hoursAgo} hours ago. */
  private UUID highTicketStarted(double hoursAgo, String status) {
    UUID id = UUID.fromString(id(createTicket(leadBearer)));
    long minutes = Math.round(hoursAgo * 60);
    jdbc.update(
        "UPDATE tickets SET sla_started_at = now() - make_interval(mins => ?),"
            + " sla_due_at = now() - make_interval(mins => ?) + interval '4 hours',"
            + " assigned_agent_id = ?, status = ?,"
            + " resolved_at = CASE WHEN ? = 'RESOLVED' THEN now() END WHERE id = ?",
        (int) minutes,
        (int) minutes,
        agent.getId(),
        status,
        status,
        id);
    return id;
  }

  private int level(UUID id) {
    return jdbc.queryForObject(
        "SELECT escalation_level FROM tickets WHERE id = ?", Integer.class, id);
  }

  private long breachEntries(UUID id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ticket_comments WHERE ticket_id = ? AND metadata->>'event' = 'SLA_BREACH'",
        Long.class,
        id);
  }

  @Test
  void levelsFollowMultiplesOfTheTargetAndAreCapped() {
    UUID onTime = highTicketStarted(3, "OPEN"); // 0.75 × target
    UUID level1 = highTicketStarted(5, "IN_PROGRESS"); // 1.25 ×
    UUID level2 = highTicketStarted(9, "OPEN"); // 2.25 ×
    UUID capped = highTicketStarted(100, "OPEN"); // 25 × → max 3
    UUID resolved = highTicketStarted(100, "RESOLVED");

    int escalated = processor.escalateBreaches(Instant.now(), 3);

    assertThat(escalated).isEqualTo(3);
    assertThat(level(onTime)).isZero();
    assertThat(level(level1)).isEqualTo(1);
    assertThat(level(level2)).isEqualTo(2);
    assertThat(level(capped)).isEqualTo(3);
    assertThat(level(resolved)).as("resolved tickets can't breach").isZero();
    assertThat(breachEntries(level2)).isEqualTo(1);
  }

  @Test
  void rerunningIsIdempotentAndLaterLevelsFollow() {
    UUID id = highTicketStarted(5, "OPEN");
    Instant now = Instant.now();

    processor.escalateBreaches(now, 3);
    assertThat(processor.escalateBreaches(now, 3)).as("second run, same time").isZero();
    assertThat(level(id)).isEqualTo(1);
    assertThat(breachEntries(id)).isEqualTo(1);

    processor.escalateBreaches(now.plus(Duration.ofHours(4)), 3); // now 2.25 × target
    assertThat(level(id)).isEqualTo(2);
    assertThat(breachEntries(id)).isEqualTo(2);
  }

  @Test
  void twoConcurrentRunsEscalateEachTicketOnce() throws Exception {
    List<UUID> ids =
        List.of(
            highTicketStarted(5, "OPEN"),
            highTicketStarted(6, "OPEN"),
            highTicketStarted(7, "OPEN"));
    Instant now = Instant.now();
    CountDownLatch go = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<Integer> a =
          pool.submit(() -> {
            go.await();
            return processor.escalateBreaches(now, 3);
          });
      Future<Integer> b =
          pool.submit(() -> {
            go.await();
            return processor.escalateBreaches(now, 3);
          });
      go.countDown();
      assertThat(a.get() + b.get()).as("total escalations across both runs").isEqualTo(3);
    }
    for (UUID id : ids) {
      assertThat(level(id)).isEqualTo(1);
      assertThat(breachEntries(id)).isEqualTo(1);
    }
  }

  @Test
  void manualEscalationAboveTheDueLevelIsNotDowngradedOrRepeated() {
    UUID id = highTicketStarted(5, "OPEN");
    jdbc.update("UPDATE tickets SET escalation_level = 2 WHERE id = ?", id);

    assertThat(processor.escalateBreaches(Instant.now(), 3)).isZero();
    assertThat(level(id)).isEqualTo(2);
  }

  @Test
  void breachNotifiesLeadsAndTheAssignee() {
    UUID id = highTicketStarted(5, "OPEN");

    processor.escalateBreaches(Instant.now(), 3);

    List<String> recipients =
        jdbc.queryForList(
            "SELECT recipient_agent_id::text FROM notifications WHERE ticket_id = ? AND type = 'SLA_BREACH'",
            String.class,
            id);
    assertThat(recipients)
        .containsExactlyInAnyOrder(lead.getId().toString(), agent.getId().toString());
  }

  @Test
  void shedLockLetsOnlyOneInstanceHoldTheJobLock() {
    LockConfiguration config =
        new LockConfiguration(
            Instant.now(), "test-job-" + UUID.randomUUID(), Duration.ofMinutes(1), Duration.ZERO);

    SimpleLock first = lockProvider.lock(config).orElseThrow();
    assertThat(lockProvider.lock(config)).as("second instance while held").isEmpty();
    first.unlock();
    assertThat(lockProvider.lock(config)).as("after release").isPresent();
  }
}
