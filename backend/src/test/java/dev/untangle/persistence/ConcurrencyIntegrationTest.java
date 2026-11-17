package dev.untangle.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.agent.infrastructure.AgentRepository;
import dev.untangle.support.IntegrationTest;
import dev.untangle.support.TestData;
import dev.untangle.ticket.infrastructure.TicketRepository;
import dev.untangle.webhook.domain.Partner;
import dev.untangle.webhook.infrastructure.PartnerRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Tests that only make sense with real, committed, concurrent transactions. No
 * {@code @Transactional} here, so the tables are truncated after each test.
 */
class ConcurrencyIntegrationTest extends IntegrationTest {

  @Autowired AgentRepository agents;
  @Autowired PartnerRepository partners;
  @Autowired TicketRepository tickets;

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  /**
   * Section 7.2 step 8 / edge case "duplicate event id, 20 concurrent requests → exactly 1 row".
   *
   * <p>A "check if it exists, then insert" approach fails here: all 20 threads check at the same
   * moment, see nothing and all insert. Only the unique index makes this safe. This test proves the
   * index holds under a real race. Phase 4 builds the 200-with-existing-ticket response on top.
   */
  @Test
  void twentyConcurrentInsertsOfOneEventCreateExactlyOneTicket() throws Exception {
    Partner partner = partners.save(TestData.partner());
    int threads = 20;
    CountDownLatch startGun = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(() -> {
              startGun.await();
              try {
                tickets.saveAndFlush(TestData.webhookTicket(partner, "evt_race"));
                return true;
              } catch (DataIntegrityViolationException duplicate) {
                return false;
              }
            }));
      }
      startGun.countDown(); // release all threads at once
    }

    long created = 0;
    for (Future<Boolean> result : results) {
      if (result.get(30, TimeUnit.SECONDS)) {
        created++;
      }
    }
    assertThat(created).as("inserts that succeeded").isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM tickets WHERE external_event_id = 'evt_race'", Long.class))
        .isEqualTo(1);
  }

  /**
   * Edge case "two updates with the same version → one succeeds, one gets 409". Both copies are
   * loaded at version 0. The first save bumps the row to version 1; the second still claims version
   * 0, so its {@code UPDATE ... WHERE version = 0} matches nothing and is rejected instead of
   * silently overwriting the first change. (Phase 3 maps this exception to 409 VERSION_CONFLICT.)
   */
  @Test
  void staleUpdateIsRejectedByOptimisticLocking() {
    Agent original = agents.save(TestData.agent(Role.AGENT));
    Agent firstCopy = agents.findById(original.getId()).orElseThrow();
    Agent secondCopy = agents.findById(original.getId()).orElseThrow();

    firstCopy.deactivate();
    Agent afterFirst = agents.save(firstCopy);
    assertThat(afterFirst.getVersion()).isEqualTo(1);

    secondCopy.deactivate();
    assertThatThrownBy(() -> agents.save(secondCopy))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }
}
