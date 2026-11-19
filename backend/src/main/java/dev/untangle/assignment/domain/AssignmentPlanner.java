package dev.untangle.assignment.domain;

import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Decides who gets each waiting ticket (ADR-029). Pure logic: no Spring, no database, so every rule
 * is unit-tested directly.
 *
 * <p><b>Rule: weighted least-loaded.</b> An agent's load is the sum of their unresolved tickets'
 * weights (CRITICAL 4, HIGH 3, MEDIUM 2, LOW 1). Each ticket goes to the eligible agent with the
 * lowest load; ties go to fewer open tickets, then to whoever has waited longest since their last
 * automatic ticket (round-robin), then to the lower id (deterministic).
 *
 * <p><b>Eligible</b> means: handles the ticket's category (no skills = every category), is under
 * the per-agent cap of open tickets, did not just give this ticket back, and, for the AI teammate,
 * the ticket is LOW or MEDIUM.
 *
 * <p><b>Data structure.</b> One ordered set (a balanced tree, used as a min-heap) per category,
 * holding the agents who handle it. Picking is the first eligible entry; after a pick the agent is
 * removed from each of its sets, its load updated and re-inserted (or dropped at the cap). That is
 * O(C · log A) per ticket for C categories and A agents, so 10,000 tickets × 50 agents is a few
 * hundred thousand steps: milliseconds.
 */
public final class AssignmentPlanner {

  /** An agent who takes automatic work, with their current unresolved tickets. */
  public record Candidate(
      UUID agentId,
      String name,
      boolean ai,
      Set<Category> skills,
      Map<Priority, Integer> openByPriority,
      @Nullable Instant lastAutoAssignedAt) {

    public Candidate {
      Objects.requireNonNull(agentId, "agentId");
      Objects.requireNonNull(name, "name");
      skills = skills == null || skills.isEmpty() ? Set.of() : Set.copyOf(skills);
      openByPriority = openByPriority == null ? Map.of() : Map.copyOf(openByPriority);
    }
  }

  /** A waiting ticket. {@code skipAgentId}: who last gave it back (never handed straight back). */
  public record Job(
      UUID ticketId, Category category, Priority priority, @Nullable UUID skipAgentId) {}

  /** The chosen agent, their load before this ticket, and a human-readable reason. */
  public record Pick(UUID agentId, String agentName, int loadBefore, String reason) {}

  /** How much an unresolved ticket of this priority weighs in an agent's load. */
  public static int weight(Priority priority) {
    return switch (priority) {
      case CRITICAL -> 4;
      case HIGH -> 3;
      case MEDIUM -> 2;
      case LOW -> 1;
    };
  }

  /** The AI teammate only gets routine work; urgent tickets always go to a person (ADR-028). */
  public static boolean aiMayTake(Priority priority) {
    return priority == Priority.LOW || priority == Priority.MEDIUM;
  }

  private static final Comparator<Slot> LEAST_BUSY_FIRST =
      Comparator.comparingInt((Slot s) -> s.load)
          .thenComparingInt(s -> s.open)
          .thenComparingLong(s -> s.lastPick)
          .thenComparing(s -> s.agentId);

  private final int maxOpen;
  private final Map<Category, TreeSet<Slot>> pools = new EnumMap<>(Category.class);
  private long pickCounter;

  public AssignmentPlanner(Collection<Candidate> candidates, int maxOpenPerAgent) {
    if (maxOpenPerAgent < 1) {
      throw new IllegalArgumentException("maxOpenPerAgent must be at least 1");
    }
    this.maxOpen = maxOpenPerAgent;
    for (Category category : Category.values()) {
      pools.put(category, new TreeSet<>(LEAST_BUSY_FIRST));
    }
    long latest = 0;
    for (Candidate c : candidates) {
      Slot slot = new Slot(c);
      latest = Math.max(latest, slot.lastPick);
      if (slot.open < maxOpen) {
        slot.categories.forEach(category -> pools.get(category).add(slot));
      }
    }
    this.pickCounter = latest;
  }

  /**
   * Picks the agent for {@code job} and counts the ticket against them, or empty if nobody fits.
   */
  public Optional<Pick> assign(Job job) {
    for (Slot slot : pools.get(job.category())) {
      if (slot.agentId.equals(job.skipAgentId()) || (slot.ai && !aiMayTake(job.priority()))) {
        continue;
      }
      int before = slot.load;
      take(slot, job.priority());
      return Optional.of(new Pick(slot.agentId, slot.name, before, reason(job, slot, before)));
    }
    return Optional.empty();
  }

  /** False once every agent is at the cap: the rest of the queue can wait for the next run. */
  public boolean hasCapacity() {
    return pools.values().stream().anyMatch(pool -> !pool.isEmpty());
  }

  private void take(Slot slot, Priority priority) {
    slot.categories.forEach(category -> pools.get(category).remove(slot));
    slot.load += weight(priority);
    slot.open++;
    slot.lastPick = ++pickCounter;
    if (slot.open < maxOpen) {
      slot.categories.forEach(category -> pools.get(category).add(slot));
    }
  }

  private static String reason(Job job, Slot slot, int before) {
    String scope = slot.generalist ? "least busy" : "least busy " + job.category() + " specialist";
    return scope + ", workload " + before;
  }

  /** Mutable per-run state of one agent. Removed from its sets before every change. */
  private static final class Slot {
    final UUID agentId;
    final String name;
    final boolean ai;
    final boolean generalist;
    final Set<Category> categories;
    int load;
    int open;
    long lastPick;

    Slot(Candidate c) {
      this.agentId = c.agentId();
      this.name = c.name();
      this.ai = c.ai();
      this.generalist = c.skills().isEmpty();
      this.categories = generalist ? EnumSet.allOf(Category.class) : EnumSet.copyOf(c.skills());
      c.openByPriority()
          .forEach((priority, count) -> {
            load += weight(priority) * count;
            open += count;
          });
      // Never auto-assigned sorts first; otherwise earlier last-pick first (round-robin).
      this.lastPick =
          c.lastAutoAssignedAt() == null ? Long.MIN_VALUE : c.lastAutoAssignedAt().toEpochMilli();
    }
  }
}
