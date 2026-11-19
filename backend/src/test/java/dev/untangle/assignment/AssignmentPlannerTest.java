package dev.untangle.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.untangle.assignment.domain.AssignmentPlanner;
import dev.untangle.assignment.domain.AssignmentPlanner.Candidate;
import dev.untangle.assignment.domain.AssignmentPlanner.Job;
import dev.untangle.assignment.domain.AssignmentPlanner.Pick;
import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The auto-assignment rule (ADR-029), without Spring or a database. */
class AssignmentPlannerTest {

  private static final UUID A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
  private static final UUID B = UUID.fromString("00000000-0000-7000-8000-00000000000b");
  private static final UUID C = UUID.fromString("00000000-0000-7000-8000-00000000000c");

  private static Candidate person(UUID id, Map<Priority, Integer> open, Category... skills) {
    return new Candidate(
        id, "Agent " + id.toString().substring(35), false, Set.of(skills), open, null);
  }

  private static Job job(Category category, Priority priority) {
    return new Job(UUID.randomUUID(), category, priority, null);
  }

  private static UUID pick(AssignmentPlanner planner, Job job) {
    return planner.assign(job).map(Pick::agentId).orElse(null);
  }

  @Test
  void weighsWorkByPriorityNotJustCount() {
    // A has one CRITICAL (load 4); B has two LOW (load 2): B is less busy despite more tickets.
    var planner =
        new AssignmentPlanner(
            List.of(person(A, Map.of(Priority.CRITICAL, 1)), person(B, Map.of(Priority.LOW, 2))),
            10);
    assertThat(pick(planner, job(Category.BILLING, Priority.MEDIUM))).isEqualTo(B);
  }

  @Test
  void spreadsAnEvenQueueRoundRobin() {
    var planner =
        new AssignmentPlanner(
            List.of(person(A, Map.of()), person(B, Map.of()), person(C, Map.of())), 10);
    Map<UUID, Integer> got = new HashMap<>();
    for (int i = 0; i < 6; i++) {
      got.merge(pick(planner, job(Category.GENERAL, Priority.MEDIUM)), 1, Integer::sum);
    }
    assertThat(got).containsOnly(Map.entry(A, 2), Map.entry(B, 2), Map.entry(C, 2));
  }

  @Test
  void routesByCategoryAndNoSkillsMeansEveryCategory() {
    var planner =
        new AssignmentPlanner(
            List.of(
                person(A, Map.of(), Category.BILLING),
                person(B, Map.of(Priority.HIGH, 2))), // generalist, busier
            10);
    assertThat(pick(planner, job(Category.BILLING, Priority.LOW))).isEqualTo(A);
    assertThat(pick(planner, job(Category.TECHNICAL, Priority.LOW)))
        .as("a billing specialist never gets technical work")
        .isEqualTo(B);
  }

  @Test
  void nobodyEligibleLeavesTheTicketWaiting() {
    var planner = new AssignmentPlanner(List.of(person(A, Map.of(), Category.BILLING)), 10);
    assertThat(planner.assign(job(Category.REFUND, Priority.HIGH))).isEmpty();
    assertThat(new AssignmentPlanner(List.of(), 10).assign(job(Category.REFUND, Priority.LOW)))
        .isEmpty();
  }

  @Test
  void theCapStopsAssignmentAndCountsExistingTickets() {
    var planner =
        new AssignmentPlanner(
            List.of(person(A, Map.of(Priority.LOW, 1)), person(B, Map.of(Priority.LOW, 2))), 2);
    assertThat(pick(planner, job(Category.GENERAL, Priority.LOW))).isEqualTo(A); // A: 2 open, full
    assertThat(planner.hasCapacity()).as("B already had 2 = the cap").isFalse();
    assertThat(planner.assign(job(Category.GENERAL, Priority.LOW))).isEmpty();
  }

  @Test
  void neverHandsATicketBackToWhoeverGaveItBack() {
    var planner =
        new AssignmentPlanner(
            List.of(person(A, Map.of()), person(B, Map.of(Priority.HIGH, 3))), 10);
    UUID ticket = UUID.randomUUID();
    assertThat(pick(planner, new Job(ticket, Category.GENERAL, Priority.LOW, A))).isEqualTo(B);
  }

  @Test
  void theAiOnlyGetsLowAndMediumWork() {
    var ai = new Candidate(C, "Unty", true, Set.of(), Map.of(), null);
    var planner = new AssignmentPlanner(List.of(ai, person(A, Map.of(Priority.HIGH, 3))), 10);
    assertThat(pick(planner, job(Category.GENERAL, Priority.CRITICAL))).isEqualTo(A);
    assertThat(pick(planner, job(Category.GENERAL, Priority.HIGH))).isEqualTo(A);
    assertThat(pick(planner, job(Category.GENERAL, Priority.MEDIUM))).isEqualTo(C);
  }

  @Test
  void onEqualLoadWhoeverWaitedLongestGoesFirst() {
    Instant now = Instant.parse("2026-11-19T10:00:00Z");
    var recent = new Candidate(A, "A", false, Set.of(), Map.of(), now);
    var earlier = new Candidate(B, "B", false, Set.of(), Map.of(), now.minusSeconds(600));
    var never = new Candidate(C, "C", false, Set.of(), Map.of(), null);
    var planner = new AssignmentPlanner(List.of(recent, earlier, never), 10);
    assertThat(pick(planner, job(Category.GENERAL, Priority.LOW))).isEqualTo(C);
    assertThat(pick(planner, job(Category.GENERAL, Priority.LOW))).isEqualTo(B);
    assertThat(pick(planner, job(Category.GENERAL, Priority.LOW))).isEqualTo(A);
  }

  @Test
  void explainsEachPick() {
    var planner =
        new AssignmentPlanner(List.of(person(A, Map.of(Priority.MEDIUM, 1), Category.REFUND)), 10);
    Pick p = planner.assign(job(Category.REFUND, Priority.LOW)).orElseThrow();
    assertThat(p.loadBefore()).isEqualTo(2);
    assertThat(p.reason()).isEqualTo("least busy REFUND specialist, workload 2");
  }

  @Test
  void tenThousandTicketsAcrossFiftyAgentsIsFastAndFair() {
    List<Candidate> agents = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      agents.add(person(UUID.randomUUID(), Map.of()));
    }
    Priority[] priorities = Priority.values();
    Category[] categories = Category.values();
    Map<UUID, Integer> load = new HashMap<>();
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          var planner = new AssignmentPlanner(agents, 1_000);
          for (int i = 0; i < 10_000; i++) {
            Priority p = priorities[i % priorities.length];
            Pick pick = planner.assign(job(categories[i % categories.length], p)).orElseThrow();
            load.merge(pick.agentId(), AssignmentPlanner.weight(p), Integer::sum);
          }
        });
    int max = load.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
    int min = load.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
    assertThat(max - min).as("loads differ by at most one CRITICAL ticket").isLessThanOrEqualTo(4);
  }
}
