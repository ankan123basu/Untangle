package dev.untangle.notification.application;

import dev.untangle.agent.application.AgentDirectory;
import dev.untangle.agent.domain.Agent;
import dev.untangle.ai.application.AiTeammate.AiProposalReady;
import dev.untangle.common.api.PageResponse;
import dev.untangle.common.error.ApiException;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.notification.domain.Notification;
import dev.untangle.notification.domain.NotificationType;
import dev.untangle.notification.infrastructure.NotificationRepository;
import dev.untangle.ticket.application.TicketEvents.TicketAssigned;
import dev.untangle.ticket.application.TicketEvents.TicketEscalated;
import dev.untangle.ticket.application.TicketEvents.TicketHandedBack;
import dev.untangle.ticket.application.TicketEvents.TicketReopened;
import dev.untangle.ticket.domain.Ticket;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * In-app notifications (who: agents and leads; why: nothing urgent is missed).
 *
 * <p>Created from ticket events <b>after</b> the ticket change commits, in their own transaction. A
 * rolled-back change notifies nobody, and a notification failure can't undo a ticket change. People
 * are never notified about their own actions.
 *
 * <table>
 *   <tr><th>Event</th><th>Who</th><th>Type</th></tr>
 *   <tr><td>assigned</td><td>new assignee</td><td>ASSIGNED</td></tr>
 *   <tr><td>manual escalation</td><td>active leads + assignee</td><td>ESCALATED</td></tr>
 *   <tr><td>SLA breach (scheduler)</td><td>active leads + assignee</td><td>SLA_BREACH</td></tr>
 *   <tr><td>reopened</td><td>assignee</td><td>REOPENED</td></tr>
 * </table>
 *
 * Delivery to the browser is by polling the unread count (the SPA polls every 15 s and pops up new
 * ones; ADR-021).
 */
@Service
public class NotificationService {

  public record NotificationView(
      UUID id,
      NotificationType type,
      UUID ticketId,
      String ticketNumber,
      String message,
      boolean read,
      Instant createdAt) {
    static NotificationView of(Notification n) {
      return new NotificationView(
          n.getId(),
          n.getType(),
          n.getTicket().getId(),
          n.getTicket().getTicketNumber(),
          n.getMessage(),
          n.getReadAt() != null,
          n.getCreatedAt());
    }
  }

  public record UnreadCount(long count) {}

  private final NotificationRepository notifications;
  private final AgentDirectory agentDirectory;
  private final EntityManager entityManager;
  private final Clock clock;

  public NotificationService(
      NotificationRepository notifications,
      AgentDirectory agentDirectory,
      EntityManager entityManager,
      Clock clock) {
    this.notifications = notifications;
    this.agentDirectory = agentDirectory;
    this.entityManager = entityManager;
    this.clock = clock;
  }

  // ---- event listeners ------------------------------------------------------------------------

  /** The AI teammate proposed a resolution: tell whoever assigned it (or every lead). */
  @TransactionalEventListener(fallbackExecution = true)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onAiProposal(AiProposalReady event) {
    Set<UUID> to =
        event.notifyAgentId() != null
            ? Set.of(event.notifyAgentId())
            : new LinkedHashSet<>(agentDirectory.activeLeadIds());
    notify(
        to,
        null,
        NotificationType.AI_PROPOSAL,
        event.ticketId(),
        event.aiName() + " proposed a resolution for " + event.ticketNumber() + ". Review it.");
  }

  /** The AI teammate handed a ticket back: tell the person it went to (or every lead). */
  @TransactionalEventListener
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onHandedBack(TicketHandedBack event) {
    Set<UUID> to =
        event.toAgentId() != null
            ? Set.of(event.toAgentId())
            : new LinkedHashSet<>(agentDirectory.activeLeadIds());
    notify(
        to,
        null,
        NotificationType.AI_HANDBACK,
        event.ticketId(),
        agentDirectory.findFresh(event.fromAgentId()).map(a -> a.fullName()).orElse("The AI")
            + " handed "
            + event.ticketNumber()
            + " back: "
            + event.reason());
  }

  @TransactionalEventListener
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onAssigned(TicketAssigned event) {
    if (agentDirectory.findFresh(event.assigneeId()).map(a -> a.ai()).orElse(false)) {
      return; // the AI teammate gets to work instead of a notification (ADR-028)
    }
    notify(
        Set.of(event.assigneeId()),
        event.actorId(),
        NotificationType.ASSIGNED,
        event.ticketId(),
        event.ticketNumber() + " was assigned to you");
  }

  @TransactionalEventListener
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onEscalated(TicketEscalated event) {
    Set<UUID> recipients = new LinkedHashSet<>(agentDirectory.activeLeadIds());
    if (event.assigneeId() != null) {
      recipients.add(event.assigneeId());
    }
    if (event.automatic()) {
      notify(
          recipients,
          null,
          NotificationType.SLA_BREACH,
          event.ticketId(),
          event.ticketNumber() + " breached its SLA (escalation level " + event.level() + ")");
    } else {
      notify(
          recipients,
          event.actorId(),
          NotificationType.ESCALATED,
          event.ticketId(),
          event.ticketNumber()
              + " was escalated to level "
              + event.level()
              + ": "
              + event.reason());
    }
  }

  @TransactionalEventListener
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onReopened(TicketReopened event) {
    if (event.assigneeId() != null) {
      notify(
          Set.of(event.assigneeId()),
          event.actorId(),
          NotificationType.REOPENED,
          event.ticketId(),
          event.ticketNumber() + " was reopened");
    }
  }

  private void notify(
      Set<UUID> recipients, UUID actorId, NotificationType type, UUID ticketId, String message) {
    Ticket ticket = entityManager.getReference(Ticket.class, ticketId);
    String text = message.length() > 500 ? message.substring(0, 497) + "..." : message;
    recipients.stream()
        .filter(Objects::nonNull)
        .filter(id -> !id.equals(actorId))
        .forEach(id ->
            notifications.save(
                new Notification(entityManager.getReference(Agent.class, id), type, ticket, text)));
  }

  // ---- queries & commands for the current user ----------------------------------------------

  @Transactional(readOnly = true)
  public PageResponse<NotificationView> list(UUID me, boolean unreadOnly, int page, int size) {
    PageRequest request = PageRequest.of(page, size);
    Page<Notification> result =
        unreadOnly
            ? notifications.findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(me, request)
            : notifications.findByRecipientIdOrderByCreatedAtDesc(me, request);
    return PageResponse.of(result, NotificationView::of);
  }

  @Transactional(readOnly = true)
  public UnreadCount unreadCount(UUID me) {
    return new UnreadCount(notifications.countByRecipientIdAndReadAtIsNull(me));
  }

  /**
   * Someone else's notification is reported as "not found", not "forbidden" (no existence leak).
   */
  @Transactional
  public NotificationView markRead(UUID me, UUID notificationId) {
    Notification n =
        notifications
            .findByIdAndRecipientId(notificationId, me)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Notification not found"));
    n.markRead(clock.instant());
    return NotificationView.of(n);
  }

  @Transactional
  public UnreadCount markAllRead(UUID me) {
    notifications.markAllRead(me, clock.instant());
    return new UnreadCount(0);
  }
}
