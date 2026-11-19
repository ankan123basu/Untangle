package dev.untangle.ai.application;

import dev.untangle.agent.application.AgentSnapshot;
import dev.untangle.agent.application.AiAgentAccount;
import dev.untangle.agent.domain.Agent;
import dev.untangle.ai.application.AiAssistant.TeammateAnswer;
import dev.untangle.ai.domain.AiProposal;
import dev.untangle.ai.infrastructure.AiProposalRepository;
import dev.untangle.common.error.ApiException;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.security.AuthenticatedAgent;
import dev.untangle.security.rbac.Permission;
import dev.untangle.security.rbac.RolePermissions;
import dev.untangle.ticket.application.TicketCommands.AddComment;
import dev.untangle.ticket.application.TicketCommands.AssignTicket;
import dev.untangle.ticket.application.TicketCommands.NewCommentType;
import dev.untangle.ticket.application.TicketCommands.TransitionTicket;
import dev.untangle.ticket.application.TicketEvents.TicketAssigned;
import dev.untangle.ticket.application.TicketService;
import dev.untangle.ticket.application.TicketViews.TicketDetail;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.Ticket;
import dev.untangle.ticket.domain.TicketStatus;
import jakarta.annotation.Nullable;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Unty", the AI teammate (ADR-028). <b>It proposes; a human lead decides.</b>
 *
 * <p>When a ticket is assigned to it (after that change commits), it works the ticket in the
 * background:
 *
 * <ol>
 *   <li>still mine and still open? otherwise stop;
 *   <li>CRITICAL → hand back at once (critical tickets always go to a human);
 *   <li>AI switched off → hand back;
 *   <li>ask the model (redact → wrap → call → validate, as every AI call);
 *   <li>the model asks for a human, or confidence &lt; {@value #MIN_CONFIDENCE} → hand back;
 *   <li>otherwise: start progress, post the reply on the timeline as the AI, store a PENDING
 *       proposal and tell the person who assigned it.
 * </ol>
 *
 * Any failure (provider down, invalid answer, version clash) hands the ticket back with the reason.
 * The AI acts only through {@link TicketService} with its own AGENT permissions, so every ticket
 * rule still applies to it. It never resolves, closes, reassigns or reprioritises: resolving
 * happens only when a lead approves ({@link #approve}); a lead can instead take the ticket over
 * ({@link #takeOver}).
 */
@Service
public class AiTeammate {

  private static final Logger log = LoggerFactory.getLogger(AiTeammate.class);
  static final double MIN_CONFIDENCE = 0.6;

  /** The latest proposal for a ticket, plus whether the caller may decide on it. */
  public record ProposalView(
      UUID id,
      String reply,
      String resolutionNote,
      BigDecimal confidence,
      AiProposal.Status status,
      String model,
      String promptVersion,
      Instant createdAt,
      @Nullable String decidedBy,
      @Nullable Instant decidedAt,
      boolean canDecide) {}

  public record TeammateState(UUID agentId, String name, boolean active, boolean enabled) {}

  public record ProposalEnvelope(@Nullable ProposalView proposal) {}

  /** Published after a proposal is stored, for the notification feature. */
  public record AiProposalReady(
      UUID ticketId, String ticketNumber, @Nullable UUID notifyAgentId, String aiName) {}

  private final AiAssistant assistant;
  private final AiProperties properties;
  private final AiAgentAccount account;
  private final TicketService tickets;
  private final AiProposalRepository proposals;
  private final RolePermissions rolePermissions;
  private final EntityManager entityManager;
  private final ApplicationEventPublisher events;
  private final Executor executor;
  private final Clock clock;
  private final TransactionTemplate tx;

  public AiTeammate(
      AiAssistant assistant,
      AiProperties properties,
      AiAgentAccount account,
      TicketService tickets,
      AiProposalRepository proposals,
      RolePermissions rolePermissions,
      EntityManager entityManager,
      ApplicationEventPublisher events,
      @Qualifier("aiTeammateExecutor") Executor executor,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.assistant = assistant;
    this.properties = properties;
    this.account = account;
    this.tickets = tickets;
    this.proposals = proposals;
    this.rolePermissions = rolePermissions;
    this.entityManager = entityManager;
    this.events = events;
    this.executor = executor;
    this.clock = clock;
    // The background worker runs outside any request, so it opens its own short transactions.
    this.tx = new TransactionTemplate(transactionManager);
  }

  public TeammateState state() {
    AgentSnapshot ai = account.snapshot();
    return new TeammateState(ai.id(), ai.fullName(), ai.active(), properties.isActive());
  }

  // ---- Picking up work ------------------------------------------------------------------------

  /** After an assignment commits: if it went to the AI, work the ticket in the background. */
  @TransactionalEventListener
  public void onAssigned(TicketAssigned event) {
    AgentSnapshot ai = account.snapshot();
    if (!ai.id().equals(event.assigneeId())) {
      return;
    }
    executor.execute(() -> work(event.ticketId(), event.actorId()));
  }

  /** The background work on one ticket. Never throws: every failure becomes a hand-back. */
  void work(UUID ticketId, @Nullable UUID assignerId) {
    AgentSnapshot ai = account.snapshot();
    AuthenticatedAgent me = principal(ai);
    try {
      TicketDetail ticket = tickets.get(ticketId, me);
      if (!isMine(ticket, ai) || !isWorkable(ticket)) {
        return;
      }
      if (ticket.priority() == Priority.CRITICAL) {
        handBack(ticketId, ai, assignerId, "critical tickets always go to a human lead");
        return;
      }
      if (!properties.isActive() || !ai.active()) {
        handBack(ticketId, ai, assignerId, "AI assistance is switched off");
        return;
      }
      TeammateAnswer answer = assistant.workTicket(ticketId, me);
      if (answer.needsHuman() || answer.confidence() < MIN_CONFIDENCE) {
        String why = answer.reason() == null ? "not confident enough" : answer.reason();
        handBack(ticketId, ai, assignerId, "needs a human (" + why + ")");
        return;
      }
      propose(ticketId, ai, me, answer, assignerId);
    } catch (ApiException e) {
      log.warn("AI teammate could not work ticket={} code={}", ticketId, e.getCode());
      handBack(ticketId, ai, assignerId, "the AI could not finish this right now");
    } catch (RuntimeException e) {
      log.error("AI teammate failed on ticket={}", ticketId, e);
      handBack(ticketId, ai, assignerId, "the AI ran into an unexpected problem");
    }
  }

  private void propose(
      UUID ticketId,
      AgentSnapshot ai,
      AuthenticatedAgent me,
      TeammateAnswer answer,
      @Nullable UUID assignerId) {
    TicketDetail fresh = tickets.get(ticketId, me);
    if (!isMine(fresh, ai) || !isWorkable(fresh)) {
      return; // someone took it while the model was thinking
    }
    if (fresh.status() == TicketStatus.OPEN) {
      tickets.transition(
          ticketId,
          new TransitionTicket(TicketStatus.IN_PROGRESS, null, null, fresh.version()),
          me);
    }
    tickets.addComment(ticketId, new AddComment(answer.reply(), NewCommentType.COMMENT), me);
    tx.executeWithoutResult(status -> store(ticketId, ai, answer));
    events.publishEvent(
        new AiProposalReady(ticketId, fresh.ticketNumber(), assignerId, ai.fullName()));
    log.info(
        "AI teammate proposed a resolution ticket={} confidence={}",
        fresh.ticketNumber(),
        answer.confidence());
  }

  private void store(UUID ticketId, AgentSnapshot ai, TeammateAnswer answer) {
    proposals
        .findByTicketIdAndStatus(ticketId, AiProposal.Status.PENDING)
        .forEach(AiProposal::supersede);
    proposals.flush();
    Ticket ticket = entityManager.getReference(Ticket.class, ticketId);
    proposals.saveAndFlush(
        new AiProposal(
            ticket,
            answer.reply(),
            answer.resolutionNote(),
            answer.confidence(),
            answer.model(),
            answer.promptVersion()));
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("event", "AI_PROPOSAL");
    metadata.put("confidence", Math.round(answer.confidence() * 100));
    tickets.recordNote(
        ticketId,
        ai.id(),
        "Proposed a resolution ("
            + Math.round(answer.confidence() * 100)
            + "% confident). Waiting for a lead to approve.",
        metadata);
  }

  private void handBack(UUID ticketId, AgentSnapshot ai, @Nullable UUID to, String reason) {
    try {
      tx.executeWithoutResult(status ->
          proposals
              .findByTicketIdAndStatus(ticketId, AiProposal.Status.PENDING)
              .forEach(AiProposal::supersede));
      tickets.handBack(ticketId, ai.id(), to, reason);
    } catch (RuntimeException e) {
      log.error("AI teammate could not hand back ticket={}", ticketId, e);
    }
  }

  // ---- Human decisions ----------------------------------------------------------------------

  @Transactional
  public ProposalEnvelope current(UUID ticketId, AuthenticatedAgent me) {
    TicketDetail ticket = tickets.get(ticketId, me); // 404 / view rules
    AgentSnapshot ai = account.snapshot();
    return proposals
        .findFirstByTicketIdOrderByCreatedAtDesc(ticketId)
        .map(p -> {
          if (p.isPending() && !stillCurrent(ticket, ai)) {
            p.supersede(); // the ticket moved on without a decision
          }
          return new ProposalEnvelope(view(p, me));
        })
        .orElse(new ProposalEnvelope(null));
  }

  /** A lead approves: the ticket is resolved with the proposed note, by the lead. */
  @Transactional
  public ProposalView approve(UUID ticketId, long version, AuthenticatedAgent me) {
    AiProposal proposal = pendingFor(ticketId, me);
    tickets.transition(
        ticketId,
        new TransitionTicket(TicketStatus.RESOLVED, proposal.getResolutionNote(), null, version),
        me);
    proposal.approve(entityManager.getReference(Agent.class, me.id()), clock.instant());
    tickets.recordNote(
        ticketId,
        me.id(),
        "Approved the resolution proposed by " + account.snapshot().fullName(),
        Map.of("event", "AI_PROPOSAL_APPROVED", "proposalId", proposal.getId().toString()));
    return view(proposal, me);
  }

  /**
   * A lead rejects the proposal by taking the ticket over (assigned to them, still in progress).
   */
  @Transactional
  public ProposalView takeOver(UUID ticketId, long version, AuthenticatedAgent me) {
    AiProposal proposal = pendingFor(ticketId, me);
    tickets.assign(ticketId, new AssignTicket(me.id(), version), me);
    proposal.reject(entityManager.getReference(Agent.class, me.id()), clock.instant());
    tickets.recordNote(
        ticketId,
        me.id(),
        "Took over from " + account.snapshot().fullName() + " (proposal not used)",
        Map.of("event", "AI_PROPOSAL_REJECTED", "proposalId", proposal.getId().toString()));
    return view(proposal, me);
  }

  private AiProposal pendingFor(UUID ticketId, AuthenticatedAgent me) {
    if (!canDecide(me)) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, "Only a lead can decide on AI proposals");
    }
    TicketDetail ticket = tickets.get(ticketId, me);
    AiProposal proposal =
        proposals
            .findFirstByTicketIdOrderByCreatedAtDesc(ticketId)
            .filter(AiProposal::isPending)
            .orElseThrow(() -> new ApiException(ErrorCode.AI_PROPOSAL_NOT_FOUND));
    if (!stillCurrent(ticket, account.snapshot())) {
      proposal.supersede();
      throw new ApiException(
          ErrorCode.AI_PROPOSAL_STALE,
          "The ticket changed after the AI proposed this. Refresh to see the latest.");
    }
    return proposal;
  }

  // ---- Helpers ------------------------------------------------------------------------------

  /** Leads and admins decide (the same people who can assign tickets to anyone, incl. the AI). */
  private static boolean canDecide(AuthenticatedAgent me) {
    return me.can(Permission.TICKET_ASSIGN_ANY) && me.can(Permission.TICKET_TRANSITION_ANY);
  }

  private static boolean isMine(TicketDetail ticket, AgentSnapshot ai) {
    return ticket.assignee() != null && ticket.assignee().id().equals(ai.id());
  }

  private static boolean isWorkable(TicketDetail ticket) {
    return ticket.status() == TicketStatus.OPEN || ticket.status() == TicketStatus.IN_PROGRESS;
  }

  private static boolean stillCurrent(TicketDetail ticket, AgentSnapshot ai) {
    return isMine(ticket, ai) && ticket.status() == TicketStatus.IN_PROGRESS;
  }

  private AuthenticatedAgent principal(AgentSnapshot ai) {
    return new AuthenticatedAgent(
        ai.id(), ai.email(), ai.fullName(), ai.role(), rolePermissions.of(ai.role()));
  }

  private ProposalView view(AiProposal p, AuthenticatedAgent me) {
    Agent decider = p.getDecidedBy();
    return new ProposalView(
        p.getId(),
        p.getReply(),
        p.getResolutionNote(),
        p.getConfidence(),
        p.getStatus(),
        p.getModel(),
        p.getPromptVersion(),
        p.getCreatedAt(),
        decider == null ? null : decider.getFullName(),
        p.getDecidedAt(),
        p.isPending() && canDecide(me));
  }
}
