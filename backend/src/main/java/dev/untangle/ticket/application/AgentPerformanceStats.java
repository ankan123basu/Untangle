package dev.untangle.ticket.application;

import dev.untangle.agent.domain.Role;
import dev.untangle.ticket.infrastructure.TicketResolutionRepository;
import dev.untangle.ticket.infrastructure.TicketResolutionRepository.PerformanceRow;
import dev.untangle.ticket.infrastructure.TicketResolutionRepository.TeamRow;
import jakarta.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How fast and how well each agent resolves tickets (ADR-030), for leads and admins.
 *
 * <p>Every number comes from {@code ticket_resolutions}: one row per resolve, credited to the
 * assignee at that moment. Durations are whole seconds; a metric is null when the agent resolved
 * nothing in the period (no data is not the same as zero).
 */
@Service
public class AgentPerformanceStats {

  /** Below this many resolutions the numbers are shown but not used to name top performers. */
  public static final int MIN_RESOLVED_TO_RANK = 3;

  /** The numbers for one agent, or for the whole team (agent fields absent). */
  public record Metrics(
      long resolved,
      @Nullable Long avgHandleSeconds,
      @Nullable Long medianHandleSeconds,
      @Nullable Long medianResolutionSeconds,
      @Nullable Integer slaMetPercent,
      @Nullable Integer reopenPercent) {

    static Metrics of(
        long resolved,
        @Nullable Double avgHandle,
        @Nullable Double medianHandle,
        @Nullable Double medianResolution,
        long slaMet,
        long reopened) {
      return new Metrics(
          resolved,
          seconds(avgHandle),
          seconds(medianHandle),
          seconds(medianResolution),
          percent(slaMet, resolved),
          percent(reopened, resolved));
    }
  }

  public record AgentPerformance(
      UUID agentId,
      String fullName,
      Role role,
      boolean ai,
      boolean active,
      long openNow,
      boolean ranked,
      Metrics metrics) {}

  public record PerformanceReport(
      int days,
      Instant from,
      Instant to,
      int minResolvedToRank,
      Metrics team,
      List<AgentPerformance> agents) {}

  private final TicketResolutionRepository resolutions;
  private final Clock clock;

  public AgentPerformanceStats(TicketResolutionRepository resolutions, Clock clock) {
    this.resolutions = resolutions;
    this.clock = clock;
  }

  /** Resolutions in the last {@code days} days (a rolling window ending now). */
  @Transactional(readOnly = true)
  public PerformanceReport report(int days) {
    Instant to = clock.instant();
    Instant from = to.minus(Duration.ofDays(days));
    TeamRow t = resolutions.team(from, to);
    Metrics team =
        Metrics.of(
            t.getResolved(),
            t.getAvgHandleSeconds(),
            t.getMedianHandleSeconds(),
            t.getMedianResolutionSeconds(),
            t.getSlaMet(),
            t.getReopened());
    List<AgentPerformance> agents =
        resolutions.perAgent(from, to).stream().map(AgentPerformanceStats::toView).toList();
    return new PerformanceReport(days, from, to, MIN_RESOLVED_TO_RANK, team, agents);
  }

  private static AgentPerformance toView(PerformanceRow r) {
    return new AgentPerformance(
        r.getAgentId(),
        r.getFullName(),
        Role.valueOf(r.getRole()),
        "AI".equals(r.getKind()),
        r.getActive(),
        r.getOpenNow(),
        r.getResolved() >= MIN_RESOLVED_TO_RANK,
        Metrics.of(
            r.getResolved(),
            r.getAvgHandleSeconds(),
            r.getMedianHandleSeconds(),
            r.getMedianResolutionSeconds(),
            r.getSlaMet(),
            r.getReopened()));
  }

  private static @Nullable Long seconds(@Nullable Double value) {
    return value == null ? null : Math.round(value);
  }

  private static @Nullable Integer percent(long part, long whole) {
    return whole == 0 ? null : (int) Math.round(100.0 * part / whole);
  }
}
