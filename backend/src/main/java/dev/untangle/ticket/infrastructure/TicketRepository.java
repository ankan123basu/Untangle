package dev.untangle.ticket.infrastructure;

import dev.untangle.agent.domain.Role;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.Ticket;
import dev.untangle.ticket.domain.TicketStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * Ticket persistence. {@link JpaSpecificationExecutor} powers the dynamic list filters with
 * type-safe, injection-proof criteria queries ({@link TicketSpecifications}).
 */
public interface TicketRepository
    extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

  /**
   * Paged list. The entity graph fetch-joins the assignee into the same SELECT, so a 20-row page is
   * 1 query (+1 count), not 21 (no N+1).
   */
  @Override
  @EntityGraph(attributePaths = "assignedAgent")
  Page<Ticket> findAll(Specification<Ticket> spec, Pageable pageable);

  /** Detail page: everything the view shows, in one query. */
  @EntityGraph(attributePaths = {"assignedAgent", "createdBy", "partner"})
  Optional<Ticket> findWithDetailsById(UUID id);

  @EntityGraph(attributePaths = {"assignedAgent", "createdBy", "partner"})
  Optional<Ticket> findByTicketNumber(String ticketNumber);

  /**
   * Webhook idempotency: after the unique index rejects a duplicate insert, load the original
   * ticket to return it. Uses {@code ux_tickets_partner_event}.
   */
  Optional<Ticket> findByPartnerIdAndExternalEventId(UUID partnerId, String externalEventId);

  long countByStatusIn(Collection<TicketStatus> statuses);

  /** "Deactivate agent with open tickets" rule (409 AGENT_HAS_OPEN_TICKETS). */
  boolean existsByAssignedAgentIdAndStatusIn(UUID agentId, Collection<TicketStatus> statuses);

  /** SLA scheduler (Phase 5): lock candidate rows so concurrent runs can't double-escalate. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select t from Ticket t where t.status in :statuses and t.slaDueAt < :now"
          + " order by t.slaDueAt")
  List<Ticket> lockBreachCandidates(Collection<TicketStatus> statuses, Instant now, Pageable page);

  // ---- Auto-assignment queue (ADR-029) -------------------------------------------------------

  /**
   * The waiting queue, most urgent SLA first: OPEN and unassigned, rows locked {@code FOR UPDATE
   * SKIP LOCKED} so two runs (or a run and a new-ticket trigger) never pick the same ticket. Uses
   * {@code ix_tickets_unassigned_queue}.
   */
  @Query(
      value =
          "select * from tickets where status = 'OPEN' and assigned_agent_id is null"
              + " order by sla_due_at, id limit :limit for update skip locked",
      nativeQuery = true)
  List<Ticket> lockQueueHead(int limit);

  /** The next page of the queue after {@code (afterDue, afterId)} (keyset paging). */
  @Query(
      value =
          "select * from tickets where status = 'OPEN' and assigned_agent_id is null"
              + " and (sla_due_at, id) > (:afterDue, :afterId)"
              + " order by sla_due_at, id limit :limit for update skip locked",
      nativeQuery = true)
  List<Ticket> lockQueueAfter(Instant afterDue, UUID afterId, int limit);

  /** One waiting ticket, if it is still waiting and nobody else holds it. */
  @Query(
      value =
          "select * from tickets where id = :id and status = 'OPEN' and assigned_agent_id is null"
              + " for update skip locked",
      nativeQuery = true)
  Optional<Ticket> lockIfWaiting(UUID id);

  long countByStatusAndAssignedAgentIsNull(TicketStatus status);

  interface AgentPriorityCount {
    UUID getAgentId();

    Priority getPriority();

    long getCount();
  }

  /** Unresolved tickets per agent and priority: the planner's current workload. */
  @Query(
      "select t.assignedAgent.id as agentId, t.priority as priority, count(t) as count"
          + " from Ticket t where t.assignedAgent is not null and t.status in :statuses"
          + " group by t.assignedAgent.id, t.priority")
  List<AgentPriorityCount> countOpenByAgentAndPriority(Collection<TicketStatus> statuses);

  // ---- Dashboard aggregates -----------------------------------------------------------------

  interface StatusCount {
    TicketStatus getStatus();

    long getCount();
  }

  interface PriorityCount {
    Priority getPriority();

    long getCount();
  }

  @Query("select t.status as status, count(t) as count from Ticket t group by t.status")
  List<StatusCount> countByStatus();

  @Query(
      "select t.priority as priority, count(t) as count from Ticket t"
          + " where t.status in :statuses group by t.priority")
  List<PriorityCount> countByPriorityForStatuses(Collection<TicketStatus> statuses);

  interface DailyTrend {
    LocalDate getDay();

    long getCreated();

    long getResolved();
  }

  /**
   * Created vs resolved per UTC day for the last {@code days} days, including days with zero
   * activity (generate_series), oldest first.
   */
  @Query(
      nativeQuery = true,
      value =
          """
          WITH days AS (
              SELECT generate_series(CAST(:today AS date) - (CAST(:days AS int) - 1),
                                     CAST(:today AS date), interval '1 day')::date AS day
          )
          SELECT d.day AS day,
                 (SELECT count(*) FROM tickets t
                   WHERE (t.created_at AT TIME ZONE 'UTC')::date = d.day) AS created,
                 (SELECT count(*) FROM tickets t
                   WHERE (t.resolved_at AT TIME ZONE 'UTC')::date = d.day) AS resolved
          FROM days d
          ORDER BY d.day
          """)
  List<DailyTrend> dailyTrend(LocalDate today, int days);

  interface AgentWorkload {
    UUID getAgentId();

    String getFullName();

    Role getRole();

    long getOpen();

    long getInProgress();

    long getBreached();
  }

  /** Open / in-progress / breached counts per active agent, for leads balancing work. */
  @Query(
      """
      select a.id as agentId, a.fullName as fullName, a.role as role,
             coalesce(sum(case when t.status = dev.untangle.ticket.domain.TicketStatus.OPEN
                               then 1 else 0 end), 0) as open,
             coalesce(sum(case when t.status = dev.untangle.ticket.domain.TicketStatus.IN_PROGRESS
                               then 1 else 0 end), 0) as inProgress,
             coalesce(sum(case when t.slaDueAt < :now then 1 else 0 end), 0) as breached
      from Agent a
      left join Ticket t on t.assignedAgent = a and t.status in :statuses
      where a.active = true
      group by a.id, a.fullName, a.role
      order by a.fullName
      """)
  List<AgentWorkload> workload(Collection<TicketStatus> statuses, Instant now);
}
