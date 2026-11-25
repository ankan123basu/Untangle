package dev.untangle.ticket.infrastructure;

import dev.untangle.ticket.domain.TicketResolution;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Resolution facts and the performance aggregates over them (ADR-030). */
public interface TicketResolutionRepository extends JpaRepository<TicketResolution, UUID> {

  /** The standing resolution of a ticket didn't stick (reopened or sent back to work). */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          "update ticket_resolutions set reopened_at = :now"
              + " where ticket_id = :ticketId and reopened_at is null",
      nativeQuery = true)
  int markReopened(UUID ticketId, Instant now);

  /** One row of performance numbers. Durations are in seconds; null when nothing was resolved. */
  interface PerformanceRow {
    UUID getAgentId();

    String getFullName();

    String getRole();

    String getKind();

    boolean getActive();

    long getResolved();

    Double getAvgHandleSeconds();

    Double getMedianHandleSeconds();

    Double getMedianResolutionSeconds();

    long getSlaMet();

    long getReopened();

    long getOpenNow();
  }

  /** The aggregate columns, in snake_case; callers alias them for the projection. */
  String STATS =
      """
       count(r.id) AS resolved,
       avg(extract(epoch FROM r.resolved_at - r.assigned_at)) AS avg_handle,
       percentile_cont(0.5) WITHIN GROUP
           (ORDER BY extract(epoch FROM r.resolved_at - r.assigned_at)) AS median_handle,
       percentile_cont(0.5) WITHIN GROUP
           (ORDER BY extract(epoch FROM r.resolved_at - r.opened_at)) AS median_resolution,
       count(r.id) FILTER (WHERE r.resolved_at <= r.sla_due_at) AS sla_met,
       count(r.id) FILTER (WHERE r.reopened_at IS NOT NULL) AS reopened
      """;

  /**
   * Per agent, for resolutions in {@code [since, until)}. Lists every active AGENT (people and
   * Unty), even with nothing resolved, plus anyone else (a lead, a deactivated agent) who resolved
   * something in the period.
   */
  @Query(
      nativeQuery = true,
      value =
          "WITH stats AS (SELECT r.agent_id,"
              + STATS
              + """
                FROM ticket_resolutions r
                WHERE r.resolved_at >= :since AND r.resolved_at < :until
                GROUP BY r.agent_id),
              open_now AS (
                SELECT t.assigned_agent_id AS agent_id, count(*) AS open_now
                FROM tickets t
                WHERE t.status IN ('OPEN', 'IN_PROGRESS') AND t.assigned_agent_id IS NOT NULL
                GROUP BY t.assigned_agent_id)
              SELECT a.id AS "agentId", a.full_name AS "fullName", a.role AS "role",
                     a.kind AS "kind", a.active AS "active",
                     coalesce(s.resolved, 0) AS "resolved",
                     s.avg_handle AS "avgHandleSeconds",
                     s.median_handle AS "medianHandleSeconds",
                     s.median_resolution AS "medianResolutionSeconds",
                     coalesce(s.sla_met, 0) AS "slaMet",
                     coalesce(s.reopened, 0) AS "reopened",
                     coalesce(o.open_now, 0) AS "openNow"
              FROM agents a
              LEFT JOIN stats s ON s.agent_id = a.id
              LEFT JOIN open_now o ON o.agent_id = a.id
              WHERE (a.active AND a.role = 'AGENT') OR s.agent_id IS NOT NULL
              ORDER BY coalesce(s.resolved, 0) DESC, a.full_name, a.id
              """)
  List<PerformanceRow> perAgent(Instant since, Instant until);

  /** Team totals in one row (medians cannot be combined from per-agent medians). */
  interface TeamRow {
    long getResolved();

    Double getAvgHandleSeconds();

    Double getMedianHandleSeconds();

    Double getMedianResolutionSeconds();

    long getSlaMet();

    long getReopened();
  }

  @Query(
      nativeQuery = true,
      value =
          "SELECT s.resolved AS \"resolved\", s.avg_handle AS \"avgHandleSeconds\","
              + " s.median_handle AS \"medianHandleSeconds\","
              + " s.median_resolution AS \"medianResolutionSeconds\","
              + " s.sla_met AS \"slaMet\", s.reopened AS \"reopened\""
              + " FROM (SELECT"
              + STATS
              + " FROM ticket_resolutions r"
              + " WHERE r.resolved_at >= :since AND r.resolved_at < :until) s")
  TeamRow team(Instant since, Instant until);
}
