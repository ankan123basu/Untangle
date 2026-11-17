package dev.untangle.ticket.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolution targets per priority (§5.4, configurable: {@code app.tickets.sla.*}). Defaults:
 * CRITICAL 1h, HIGH 4h, MEDIUM 24h, LOW 72h.
 *
 * <p>The deadline is always computed from the ticket's SLA clock start ({@code sla_started_at}):
 * creation, or the latest reopen. A priority change keeps the start and recomputes the deadline.
 */
public final class SlaPolicy {

  private final Map<Priority, Duration> targets;

  public SlaPolicy(Map<Priority, Duration> targets) {
    EnumMap<Priority, Duration> copy = new EnumMap<>(Priority.class);
    for (Priority priority : Priority.values()) {
      Duration target =
          Objects.requireNonNull(targets.get(priority), "Missing SLA for " + priority);
      if (target.isNegative() || target.isZero()) {
        throw new IllegalArgumentException("SLA for " + priority + " must be positive");
      }
      copy.put(priority, target);
    }
    this.targets = copy;
  }

  public static SlaPolicy defaults() {
    return new SlaPolicy(
        Map.of(
            Priority.CRITICAL, Duration.ofHours(1),
            Priority.HIGH, Duration.ofHours(4),
            Priority.MEDIUM, Duration.ofHours(24),
            Priority.LOW, Duration.ofHours(72)));
  }

  public Duration targetFor(Priority priority) {
    return targets.get(priority);
  }

  public Instant dueAt(Instant clockStart, Priority priority) {
    return clockStart.plus(targetFor(priority));
  }
}
