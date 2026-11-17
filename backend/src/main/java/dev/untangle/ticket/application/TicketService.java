package dev.untangle.ticket.application;

import dev.untangle.agent.domain.Agent;
import dev.untangle.comment.application.Timeline;
import dev.untangle.comment.domain.CommentType;
import dev.untangle.common.error.ApiException;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.common.error.GlobalExceptionHandler.FieldViolation;
import dev.untangle.security.AuthenticatedAgent;
import dev.untangle.security.rbac.Permission;
import dev.untangle.security.rbac.TicketAccessPolicy;
import dev.untangle.security.rbac.TicketAccessPolicy.Evaluation;
import dev.untangle.ticket.application.TicketCommands.AddComment;
import dev.untangle.ticket.application.TicketCommands.AssignTicket;
import dev.untangle.ticket.application.TicketCommands.BulkAssign;
import dev.untangle.ticket.application.TicketCommands.CreateTicket;
import dev.untangle.ticket.application.TicketCommands.EditTicket;
import dev.untangle.ticket.application.TicketCommands.EscalateTicket;
import dev.untangle.ticket.application.TicketCommands.ReopenTicket;
import dev.untangle.ticket.application.TicketCommands.TransitionTicket;
import dev.untangle.ticket.application.TicketEvents.TicketAssigned;
import dev.untangle.ticket.application.TicketEvents.TicketChanged;
import dev.untangle.ticket.application.TicketEvents.TicketCreated;
import dev.untangle.ticket.application.TicketEvents.TicketEscalated;
import dev.untangle.ticket.application.TicketEvents.TicketReopened;
import dev.untangle.ticket.application.TicketEvents.TicketTransitioned;
import dev.untangle.ticket.application.TicketViews.BulkAssignResult;
import dev.untangle.ticket.application.TicketViews.DeniedAction;
import dev.untangle.ticket.application.TicketViews.TicketDetail;
import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.SlaPolicy;
import dev.untangle.ticket.domain.Ticket;
import dev.untangle.ticket.domain.TicketAction;
import dev.untangle.ticket.domain.TicketStateMachine;
import dev.untangle.ticket.domain.TicketStatus;
import dev.untangle.ticket.infrastructure.AssigneeRepository;
import dev.untangle.ticket.infrastructure.TicketRepository;
import dev.untangle.webhook.domain.Partner;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every change to a ticket goes through here, whether it comes from an agent (REST) or a partner
 * (webhook, Phase 4). That's "two entry doors, one brain" (§4.1).
 *
 * <p>Each mutation follows the same recipe:
 *
 * <ol>
 *   <li>load the ticket (404 {@code TICKET_NOT_FOUND}) and apply the closed rule (409 {@code
 *       TICKET_CLOSED});
 *   <li>optimistic-lock check: the client's {@code version} must match (409 {@code
 *       VERSION_CONFLICT}). This comes before the permission check, so a client acting on stale
 *       data learns "someone changed this, refresh" rather than a confusing 403;
 *   <li>{@link TicketAccessPolicy#require}: permission, ownership, state machine;
 *   <li>validate action-specific input (reason, resolution note);
 *   <li>call the entity's behaviour method (it guards invariants again);
 *   <li>append a SYSTEM timeline entry (the audit trail);
 *   <li>publish an event; notifications and caches react after commit.
 * </ol>
 */
@Service
public class TicketService {

  private static final Logger log = LoggerFactory.getLogger(TicketService.class);

  private final TicketRepository tickets;
  private final AssigneeRepository agents;
  private final TicketAccessPolicy policy;
  private final Timeline timeline;
  private final TicketMapper mapper;
  private final SlaPolicy slaPolicy;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final TransactionTemplate readOnlyTransactions;

  public TicketService(
      TicketRepository tickets,
      AssigneeRepository agents,
      TicketAccessPolicy policy,
      Timeline timeline,
      TicketMapper mapper,
      SlaPolicy slaPolicy,
      ApplicationEventPublisher events,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.tickets = tickets;
    this.agents = agents;
    this.policy = policy;
    this.timeline = timeline;
    this.mapper = mapper;
    this.slaPolicy = slaPolicy;
    this.events = events;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
    this.readOnlyTransactions = new TransactionTemplate(transactionManager);
    this.readOnlyTransactions.setReadOnly(true);
  }

  // ---- Queries --------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public TicketDetail get(UUID id, AuthenticatedAgent me) {
    return detail(load(id), me);
  }

  @Transactional(readOnly = true)
  public TicketDetail getByNumber(String ticketNumber, AuthenticatedAgent me) {
    Ticket ticket =
        tickets
            .findByTicketNumber(ticketNumber.strip().toUpperCase())
            .orElseThrow(() -> new ApiException(ErrorCode.TICKET_NOT_FOUND));
    return detail(ticket, me);
  }

  @Transactional(readOnly = true)
  public List<Timeline.Entry> timeline(UUID id) {
    load(id);
    return timeline.list(id);
  }

  // ---- Create ---------------------------------------------------------------------------------

  @Transactional
  public TicketDetail create(CreateTicket command, AuthenticatedAgent me) {
    Agent assignee = null;
    if (command.assigneeId() != null) {
      boolean self = command.assigneeId().equals(me.id());
      boolean allowed =
          me.can(Permission.TICKET_ASSIGN_ANY) || (self && me.can(Permission.TICKET_SELF_ASSIGN));
      if (!allowed) {
        throw new ApiException(ErrorCode.ACCESS_DENIED, "You may only assign tickets to yourself");
      }
      assignee = activeAgent(command.assigneeId());
    }

    Instant now = clock.instant();
    Ticket ticket =
        Ticket.manual(
            command.subject(),
            command.description(),
            command.customerName(),
            command.customerEmail(),
            command.category(),
            command.priority(),
            agents.getReferenceById(me.id()),
            now,
            slaPolicy.dueAt(now, command.priority()));
    if (assignee != null) {
      ticket.assignTo(assignee);
    }
    tickets.saveAndFlush(ticket); // flush: the database assigns ticket_number

    Agent actor = agents.getReferenceById(me.id());
    timeline.system(
        ticket, actor, "Ticket created", Map.of("event", "CREATED", "source", "MANUAL"));
    if (assignee != null) {
      recordAssignment(ticket, null, assignee, actor);
    }
    events.publishEvent(
        new TicketCreated(ticket.getId(), ticket.getTicketNumber(), ticket.getSource()));
    events.publishEvent(new TicketChanged(ticket.getId()));
    log.info(
        "Ticket created number={} priority={} source=MANUAL",
        ticket.getTicketNumber(),
        ticket.getPriority());
    return detail(ticket, me);
  }

  // ---- Create from a partner webhook (Phase 4) -------------------------------------------------

  /** A webhook ticket and whether it already existed for this (partner, event id). */
  public record WebhookTicketResult(Ticket ticket, boolean duplicate) {}

  /**
   * Idempotent create for partner webhooks (§7.2 step 8, ADR-012).
   *
   * <ol>
   *   <li>Fast path: if the event was already processed, return the existing ticket.
   *   <li>Otherwise insert in its own transaction. If a concurrent retry inserted first, the unique
   *       index {@code ux_tickets_partner_event} rejects us; we load and return the winner's
   *       ticket.
   * </ol>
   *
   * Step 1 is only an optimisation (fewer failed inserts); correctness comes from step 2's
   * constraint, so 20 simultaneous retries still produce exactly one ticket.
   *
   * <p>Not {@code @Transactional} itself: the insert must be able to fail and roll back on its own,
   * and the follow-up lookup needs a fresh transaction.
   */
  public WebhookTicketResult createFromWebhook(
      Partner partner,
      String eventId,
      String subject,
      String description,
      String customerName,
      String customerEmail,
      Category category,
      Priority priority) {
    Optional<Ticket> existing = findWebhookTicket(partner, eventId);
    if (existing.isPresent()) {
      return new WebhookTicketResult(existing.get(), true);
    }
    try {
      Ticket created =
          transactions.execute(status -> {
            Instant now = clock.instant();
            Ticket ticket =
                Ticket.fromWebhook(
                    subject,
                    description,
                    customerName,
                    customerEmail,
                    category,
                    priority,
                    partner,
                    eventId,
                    now,
                    slaPolicy.dueAt(now, priority));
            tickets.saveAndFlush(ticket);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("event", "CREATED");
            metadata.put("source", "WEBHOOK");
            metadata.put("partner", partner.getCode());
            metadata.put("externalEventId", eventId);
            timeline.system(
                ticket, null, "Ticket received from partner " + partner.getName(), metadata);
            events.publishEvent(
                new TicketCreated(ticket.getId(), ticket.getTicketNumber(), ticket.getSource()));
            events.publishEvent(new TicketChanged(ticket.getId()));
            return ticket;
          });
      log.info(
          "Ticket created number={} priority={} source=WEBHOOK partner={}",
          created.getTicketNumber(),
          priority,
          partner.getCode());
      return new WebhookTicketResult(created, false);
    } catch (DataIntegrityViolationException e) {
      if (!String.valueOf(e.getMostSpecificCause().getMessage())
          .contains("ux_tickets_partner_event")) {
        throw e;
      }
      Ticket winner =
          findWebhookTicket(partner, eventId)
              .orElseThrow(() -> new IllegalStateException("Duplicate reported but not found", e));
      return new WebhookTicketResult(winner, true);
    }
  }

  private Optional<Ticket> findWebhookTicket(Partner partner, String eventId) {
    return Optional.ofNullable(
        readOnlyTransactions.execute(status ->
            tickets.findByPartnerIdAndExternalEventId(partner.getId(), eventId).orElse(null)));
  }

  // ---- Edit -----------------------------------------------------------------------------------

  @Transactional
  public TicketDetail edit(UUID id, EditTicket command, AuthenticatedAgent me) {
    Ticket ticket = load(id);
    ticket.ensureNotClosed();
    checkVersion(ticket, command.version());
    policy.require(TicketAction.EDIT, ticket, me);

    Map<String, Object> changes = new LinkedHashMap<>();
    if (command.priority() != null && command.priority() != ticket.getPriority()) {
      changes.put(
          "priority", Map.of("from", ticket.getPriority().name(), "to", command.priority().name()));
    }
    if (command.category() != null && command.category() != ticket.getCategory()) {
      changes.put(
          "category", Map.of("from", ticket.getCategory().name(), "to", command.category().name()));
    }
    if (command.subject() != null && !command.subject().equals(ticket.getSubject())) {
      changes.put("subject", true);
    }
    if (command.description() != null && !command.description().equals(ticket.getDescription())) {
      changes.put("description", true);
    }

    boolean changed =
        ticket.edit(
            command.subject(),
            command.description(),
            command.category(),
            command.priority(),
            slaPolicy);
    if (changed) {
      tickets.flush();
      timeline.system(
          ticket,
          agents.getReferenceById(me.id()),
          "Ticket details updated",
          Map.of("event", "UPDATED", "changes", changes));
      events.publishEvent(new TicketChanged(ticket.getId()));
    }
    return detail(ticket, me);
  }

  // ---- Assign ---------------------------------------------------------------------------------

  @Transactional
  public TicketDetail assign(UUID id, AssignTicket command, AuthenticatedAgent me) {
    Ticket ticket = load(id);
    TicketAction action =
        me.can(Permission.TICKET_ASSIGN_ANY) || !me.id().equals(command.agentId())
            ? TicketAction.ASSIGN
            : TicketAction.SELF_ASSIGN;
    ticket.ensureNotClosed();
    checkVersion(ticket, command.version());
    policy.require(action, ticket, me);

    Agent previous = ticket.getAssignedAgent();
    UUID previousId = previous == null ? null : previous.getId();
    if (Objects.equals(previousId, command.agentId())) {
      return detail(ticket, me); // already the case: nothing to do
    }
    Agent next = command.agentId() == null ? null : activeAgent(command.agentId());
    ticket.assignTo(next);
    tickets.flush();
    recordAssignment(ticket, previous, next, agents.getReferenceById(me.id()));
    return detail(ticket, me);
  }

  /**
   * Reassigns many tickets at once (leads; used before deactivating an agent). Closed and unknown
   * tickets are skipped and reported, instead of failing the whole batch.
   */
  @Transactional
  public BulkAssignResult bulkAssign(BulkAssign command, AuthenticatedAgent me) {
    if (!me.can(Permission.TICKET_ASSIGN_ANY)) {
      throw new ApiException(ErrorCode.ACCESS_DENIED);
    }
    Agent target = activeAgent(command.agentId());
    Agent actor = agents.getReferenceById(me.id());
    List<UUID> assigned = new ArrayList<>();
    List<BulkAssignResult.Skipped> skipped = new ArrayList<>();
    for (UUID ticketId : command.ticketIds().stream().distinct().toList()) {
      Ticket ticket = tickets.findById(ticketId).orElse(null);
      if (ticket == null) {
        skipped.add(new BulkAssignResult.Skipped(ticketId, ErrorCode.TICKET_NOT_FOUND));
        continue;
      }
      if (ticket.isClosed()) {
        skipped.add(new BulkAssignResult.Skipped(ticketId, ErrorCode.TICKET_CLOSED));
        continue;
      }
      Agent previous = ticket.getAssignedAgent();
      if (previous != null && previous.getId().equals(target.getId())) {
        assigned.add(ticketId);
        continue;
      }
      ticket.assignTo(target);
      recordAssignment(ticket, previous, target, actor);
      assigned.add(ticketId);
    }
    tickets.flush();
    return new BulkAssignResult(assigned, skipped);
  }

  // ---- Status transitions ---------------------------------------------------------------------

  @Transactional
  public TicketDetail transition(UUID id, TransitionTicket command, AuthenticatedAgent me) {
    Ticket ticket = load(id);
    ticket.ensureNotClosed(); // §5.2: TICKET_CLOSED takes precedence, even for "CLOSED → OPEN"
    TicketStatus from = ticket.getStatus();
    TicketStatus to = command.targetStatus();
    TicketAction action =
        TicketStateMachine.actionFor(from, to)
            .filter(a -> a != TicketAction.REOPEN)
            .orElseThrow(() ->
                new ApiException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot move a ticket from " + from + " to " + to));
    checkVersion(ticket, command.version());
    policy.require(action, ticket, me);

    if (TicketStateMachine.requiresResolutionNote(to) && command.note() == null) {
      throw new ApiException(
          ErrorCode.RESOLUTION_NOTE_REQUIRED, "Describe how the issue was resolved");
    }
    if (TicketStateMachine.requiresReason(from, to) && command.reason() == null) {
      throw reasonRequired("Moving from " + from + " to " + to + " requires a reason");
    }

    Instant now = clock.instant();
    Agent actor = agents.getReferenceById(me.id());
    ticket.transitionTo(to, now);
    tickets.flush();

    if (action == TicketAction.RESOLVE) {
      timeline.byAgent(ticket, actor, CommentType.RESOLUTION_NOTE, command.note());
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("event", "STATUS_CHANGED");
    metadata.put("from", from.name());
    metadata.put("to", to.name());
    if (command.reason() != null) {
      metadata.put("reason", command.reason());
    }
    timeline.system(ticket, actor, "Status changed from " + from + " to " + to, metadata);
    events.publishEvent(
        new TicketTransitioned(
            ticket.getId(), ticket.getTicketNumber(), from.name(), to.name(), me.id()));
    events.publishEvent(new TicketChanged(ticket.getId()));
    log.info("Ticket transitioned number={} {}->{}", ticket.getTicketNumber(), from, to);
    return detail(ticket, me);
  }

  @Transactional
  public TicketDetail reopen(UUID id, ReopenTicket command, AuthenticatedAgent me) {
    Ticket ticket = load(id);
    checkVersion(ticket, command.version());
    policy.require(TicketAction.REOPEN, ticket, me);

    UUID previousAssignee =
        ticket.getAssignedAgent() == null ? null : ticket.getAssignedAgent().getId();
    ticket.reopen(clock.instant(), slaPolicy);
    tickets.flush();
    UUID keptAssignee =
        ticket.getAssignedAgent() == null ? null : ticket.getAssignedAgent().getId();

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("event", "REOPENED");
    metadata.put("from", "CLOSED");
    metadata.put("to", "OPEN");
    metadata.put("reason", command.reason());
    metadata.put("reopenedCount", ticket.getReopenedCount());
    if (previousAssignee != null && keptAssignee == null) {
      metadata.put("assigneeCleared", "previous assignee is deactivated");
    }
    timeline.system(ticket, agents.getReferenceById(me.id()), "Ticket reopened", metadata);
    events.publishEvent(
        new TicketReopened(ticket.getId(), ticket.getTicketNumber(), keptAssignee, me.id()));
    events.publishEvent(new TicketChanged(ticket.getId()));
    log.info(
        "Ticket reopened number={} count={}", ticket.getTicketNumber(), ticket.getReopenedCount());
    return detail(ticket, me);
  }

  // ---- Escalation -----------------------------------------------------------------------------

  @Transactional
  public TicketDetail escalate(UUID id, EscalateTicket command, AuthenticatedAgent me) {
    Ticket ticket = load(id);
    policy.require(TicketAction.ESCALATE, ticket, me);
    int level = ticket.escalate();
    tickets.flush();
    timeline.system(
        ticket,
        agents.getReferenceById(me.id()),
        "Escalated to level " + level,
        Map.of(
            "event", "ESCALATED", "level", level, "reason", command.reason(), "automatic", false));
    UUID assigneeId = ticket.getAssignedAgent() == null ? null : ticket.getAssignedAgent().getId();
    events.publishEvent(
        new TicketEscalated(
            ticket.getId(),
            ticket.getTicketNumber(),
            level,
            assigneeId,
            me.id(),
            false,
            command.reason()));
    events.publishEvent(new TicketChanged(ticket.getId()));
    log.info("Ticket escalated number={} level={} manual", ticket.getTicketNumber(), level);
    return detail(ticket, me);
  }

  // ---- Comments -------------------------------------------------------------------------------

  @Transactional
  public Timeline.Entry addComment(UUID id, AddComment command, AuthenticatedAgent me) {
    Ticket ticket = load(id);
    policy.require(TicketAction.COMMENT, ticket, me);
    CommentType type =
        command.type() == TicketCommands.NewCommentType.RESOLUTION_NOTE
            ? CommentType.RESOLUTION_NOTE
            : CommentType.COMMENT;
    return timeline.byAgent(ticket, agents.getReferenceById(me.id()), type, command.body());
  }

  // ---- Helpers --------------------------------------------------------------------------------

  private Ticket load(UUID id) {
    return tickets
        .findWithDetailsById(id)
        .orElseThrow(() -> new ApiException(ErrorCode.TICKET_NOT_FOUND));
  }

  private Agent activeAgent(UUID agentId) {
    Agent agent =
        agents.findById(agentId).orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND));
    if (!agent.isActive()) {
      throw new ApiException(ErrorCode.AGENT_INACTIVE, "Cannot assign to a deactivated agent");
    }
    return agent;
  }

  /**
   * §5.5: the client must send the version it last saw. A mismatch means someone else changed the
   * ticket in between, so we refuse rather than overwrite their change. Concurrent writers that
   * pass this check together are still caught by {@code @Version} at flush.
   */
  private static void checkVersion(Ticket ticket, Long expected) {
    if (!Objects.equals(ticket.getVersion(), expected)) {
      throw new ApiException(
              ErrorCode.VERSION_CONFLICT,
              "This ticket was updated by someone else. Refresh to see the latest version.")
          .with("currentVersion", ticket.getVersion());
    }
  }

  private void recordAssignment(Ticket ticket, Agent previous, Agent next, Agent actor) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("event", "ASSIGNED");
    metadata.put("from", previous == null ? null : previous.getId().toString());
    metadata.put("to", next == null ? null : next.getId().toString());
    String body = next == null ? "Unassigned" : "Assigned to " + next.getFullName();
    timeline.system(ticket, actor, body, metadata);
    if (next != null) {
      events.publishEvent(
          new TicketAssigned(
              ticket.getId(), ticket.getTicketNumber(), next.getId(), actor.getId()));
    }
    events.publishEvent(new TicketChanged(ticket.getId()));
  }

  private TicketDetail detail(Ticket ticket, AuthenticatedAgent me) {
    Evaluation evaluation = policy.evaluateAll(ticket, me);
    List<DeniedAction> denied =
        evaluation.denied().stream().map(d -> new DeniedAction(d.action(), d.reason())).toList();
    return mapper.toDetail(ticket, evaluation.allowed(), denied, clock.instant());
  }

  private static ApiException reasonRequired(String message) {
    return new ApiException(ErrorCode.VALIDATION_FAILED, message)
        .with(
            "errors", List.of(new FieldViolation("reason", "is required for this status change")));
  }
}
