package dev.untangle.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.untangle.ticket.application.TicketEvents.TicketChanged;
import dev.untangle.ticket.infrastructure.TicketRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * Stress-test finding (docs/scalability.md): after every eviction, all concurrent dashboard
 * requests recomputed the same counts at once. Only one may compute now, and it must work on
 * virtual threads (Caffeine's sync mode pinned them and stalled the server).
 */
class DashboardCountsStampedeTest {

  private TicketRepository tickets;
  private DashboardCounts counts;

  @BeforeEach
  void setUp() {
    tickets = mock(TicketRepository.class);
    when(tickets.countByStatus())
        .thenAnswer(invocation -> {
          Thread.sleep(150); // a slow query, so concurrent callers really overlap
          return List.of();
        });
    when(tickets.countByPriorityForStatuses(anyCollection())).thenReturn(List.of());
    when(tickets.count(any(Specification.class))).thenReturn(0L);

    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    counts =
        new DashboardCounts(
            tickets,
            Clock.systemUTC(),
            new ConcurrentMapCacheManager(DashboardCounts.TEAM, DashboardCounts.MINE),
            transactions);
  }

  @Test
  void twentyConcurrentRequestsOnVirtualThreadsComputeTheCountsOnce() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<DashboardCounts.TeamCounts>> results = new ArrayList<>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 20; i++) {
        results.add(
            executor.submit(() -> {
              start.await();
              return counts.team();
            }));
      }
      start.countDown();
      for (Future<DashboardCounts.TeamCounts> result : results) {
        assertThat(result.get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS))
            .isNotNull();
      }
    }

    verify(tickets, times(1)).countByStatus();
  }

  @Test
  void aChangeDuringTheComputationIsNotHiddenByStaleNumbers() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<DashboardCounts.TeamCounts> slow = executor.submit(counts::team);
      Thread.sleep(50); // the computation is running
      counts.onTicketChanged(new TicketChanged(UUID.randomUUID()));
      slow.get(5, TimeUnit.SECONDS);
    }

    counts.team(); // must compute again: the earlier result was not cached

    verify(tickets, times(2)).countByStatus();
  }
}
