package dev.untangle.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.agent.infrastructure.AgentRepository;
import dev.untangle.ai.domain.InsightKind;
import dev.untangle.ai.domain.TicketAiInsight;
import dev.untangle.ai.infrastructure.TicketAiInsightRepository;
import dev.untangle.auth.domain.RefreshToken;
import dev.untangle.auth.infrastructure.RefreshTokenRepository;
import dev.untangle.comment.domain.CommentType;
import dev.untangle.comment.domain.TicketComment;
import dev.untangle.comment.infrastructure.TicketCommentRepository;
import dev.untangle.notification.domain.Notification;
import dev.untangle.notification.domain.NotificationType;
import dev.untangle.notification.infrastructure.NotificationRepository;
import dev.untangle.support.IntegrationTest;
import dev.untangle.support.TestData;
import dev.untangle.ticket.domain.Ticket;
import dev.untangle.ticket.domain.TicketSource;
import dev.untangle.ticket.domain.TicketStatus;
import dev.untangle.ticket.infrastructure.TicketRepository;
import dev.untangle.webhook.domain.DeliveryOutcome;
import dev.untangle.webhook.domain.Partner;
import dev.untangle.webhook.domain.WebhookDelivery;
import dev.untangle.webhook.infrastructure.PartnerRepository;
import dev.untangle.webhook.infrastructure.WebhookDeliveryRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Round-trips every entity through Hibernate and PostgreSQL. {@code ddl-auto=validate} already
 * checks that columns exist; these tests check that values survive the trip (enums as strings,
 * JSONB maps, generated ticket numbers, UUIDv7 ids) and that the repository queries work.
 *
 * <p>{@code em.flush(); em.clear()} forces a real INSERT and then a real SELECT, instead of
 * Hibernate handing back the same object from its first-level cache.
 */
@Transactional
class RepositoryMappingIntegrationTest extends IntegrationTest {

  @Autowired EntityManager em;
  @Autowired AgentRepository agents;
  @Autowired PartnerRepository partners;
  @Autowired TicketRepository tickets;
  @Autowired TicketCommentRepository comments;
  @Autowired WebhookDeliveryRepository deliveries;
  @Autowired RefreshTokenRepository refreshTokens;
  @Autowired NotificationRepository notifications;
  @Autowired TicketAiInsightRepository insights;

  private void flushAndClear() {
    em.flush();
    em.clear();
  }

  @Test
  void savesAgentWithVersionedAuditFieldsAndTimeOrderedId() {
    Agent saved = agents.saveAndFlush(TestData.agent(Role.LEAD));

    assertThat(saved.getId().version()).as("UUID version").isEqualTo(7);
    assertThat(saved.getVersion()).isZero();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
    assertThat(saved.isActive()).isTrue();
  }

  @Test
  void findsAgentByEmailIgnoringCase() {
    Agent saved = agents.save(TestData.agent(Role.AGENT));
    flushAndClear();

    assertThat(agents.findByEmailIgnoreCase(saved.getEmail().toUpperCase()))
        .get()
        .extracting(Agent::getId)
        .isEqualTo(saved.getId());
  }

  @Test
  void countsActiveAdminsForTheLastAdminRule() {
    agents.save(TestData.agent(Role.ADMIN));
    Agent inactive = TestData.agent(Role.ADMIN);
    inactive.deactivate();
    agents.save(inactive);
    flushAndClear();

    assertThat(agents.countByRoleAndActiveTrue(Role.ADMIN)).isEqualTo(1);
  }

  @Test
  void databaseAssignsSequentialHumanReadableTicketNumbers() {
    Agent creator = agents.save(TestData.agent(Role.AGENT));
    Ticket first = tickets.saveAndFlush(TestData.manualTicket(creator));
    Ticket second = tickets.saveAndFlush(TestData.manualTicket(creator));

    assertThat(first.getTicketNumber()).matches("UNT-\\d{6,}");
    long firstNumber = Long.parseLong(first.getTicketNumber().substring(4));
    long secondNumber = Long.parseLong(second.getTicketNumber().substring(4));
    assertThat(secondNumber).isEqualTo(firstNumber + 1);
  }

  @Test
  void roundTripsManualTicket() {
    Agent creator = agents.save(TestData.agent(Role.AGENT));
    Ticket saved = tickets.save(TestData.manualTicket(creator));
    flushAndClear();

    Ticket loaded = tickets.findByTicketNumber(saved.getTicketNumber()).orElseThrow();
    assertThat(loaded.getStatus()).isEqualTo(TicketStatus.OPEN);
    assertThat(loaded.getSource()).isEqualTo(TicketSource.MANUAL);
    assertThat(loaded.getCreatedBy().getId()).isEqualTo(creator.getId());
    assertThat(loaded.getPartner()).isNull();
    assertThat(loaded.getEscalationLevel()).isZero();
    assertThat(loaded.getVersion()).isZero();
  }

  @Test
  void findsWebhookTicketByPartnerAndEventId() {
    Partner partner = partners.save(TestData.partner());
    Ticket saved = tickets.save(TestData.webhookTicket(partner, "evt_42"));
    flushAndClear();

    assertThat(tickets.findByPartnerIdAndExternalEventId(partner.getId(), "evt_42"))
        .get()
        .extracting(Ticket::getId)
        .isEqualTo(saved.getId());
    assertThat(tickets.findByPartnerIdAndExternalEventId(partner.getId(), "evt_43")).isEmpty();
  }

  @Test
  void countsUnresolvedTickets() {
    Agent creator = agents.save(TestData.agent(Role.AGENT));
    tickets.save(TestData.manualTicket(creator));
    tickets.save(TestData.manualTicket(creator));
    flushAndClear();

    assertThat(tickets.countByStatusIn(TicketStatus.UNRESOLVED)).isEqualTo(2);
    assertThat(tickets.countByStatusIn(EnumSet.of(TicketStatus.CLOSED))).isZero();
  }

  @Test
  void returnsTimelineOldestFirstWithJsonMetadata() {
    Agent agent = agents.save(TestData.agent(Role.AGENT));
    Ticket ticket = tickets.save(TestData.manualTicket(agent));
    comments.save(TicketComment.byAgent(ticket, agent, CommentType.COMMENT, "Looking into it"));
    flushAndClear();
    comments.save(
        TicketComment.system(
            em.getReference(Ticket.class, ticket.getId()),
            null,
            "SLA breached",
            Map.of("event", "SLA_BREACH", "level", 1)));
    flushAndClear();

    var timeline = comments.findByTicketIdOrderByCreatedAtAsc(ticket.getId());

    assertThat(timeline)
        .extracting(TicketComment::getType)
        .containsExactly(CommentType.COMMENT, CommentType.SYSTEM);
    assertThat(timeline.get(0).getAuthor().getFullName()).isEqualTo(agent.getFullName());
    assertThat(timeline.get(1).getAuthor()).isNull();
    assertThat(timeline.get(1).getMetadata())
        .containsEntry("event", "SLA_BREACH")
        .containsEntry("level", 1);
  }

  @Test
  void roundTripsWebhookDelivery() {
    Partner partner = partners.save(TestData.partner());
    WebhookDelivery saved =
        deliveries.save(
            WebhookDelivery.builder()
                .partner(partner)
                .partnerCodeRaw(partner.getCode())
                .eventId("evt_1")
                .outcome(DeliveryOutcome.REJECTED_SIGNATURE)
                .reason("signature mismatch")
                .sourceIp("203.0.113.7")
                .payloadSha256("a".repeat(64))
                .build());
    flushAndClear();

    WebhookDelivery loaded = deliveries.findById(saved.getId()).orElseThrow();
    assertThat(loaded.getOutcome()).isEqualTo(DeliveryOutcome.REJECTED_SIGNATURE);
    assertThat(loaded.getReceivedAt()).isNotNull();
  }

  @Test
  void deletesDeliveriesOlderThanRetentionCutoff() {
    deliveries.save(
        WebhookDelivery.builder()
            .partnerCodeRaw("ghost")
            .outcome(DeliveryOutcome.REJECTED_UNKNOWN_PARTNER)
            .build());
    flushAndClear();

    assertThat(deliveries.deleteReceivedBefore(Instant.now().minus(30, ChronoUnit.DAYS))).isZero();
    assertThat(deliveries.deleteReceivedBefore(Instant.now().plusSeconds(60))).isEqualTo(1);
  }

  @Test
  void revokesAWholeRefreshTokenFamily() {
    Agent agent = agents.save(TestData.agent(Role.AGENT));
    UUID family = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      refreshTokens.save(
          RefreshToken.builder()
              .agent(agent)
              .tokenHash(String.format("%064x", i + 1))
              .familyId(family)
              .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
              .build());
    }
    flushAndClear();

    assertThat(refreshTokens.revokeFamily(family, Instant.now())).isEqualTo(3);
    assertThat(refreshTokens.revokeFamily(family, Instant.now()))
        .as("already revoked tokens are not touched again")
        .isZero();
    assertThat(refreshTokens.findByTokenHash(String.format("%064x", 1)))
        .get()
        .extracting(RefreshToken::getRevokedAt)
        .isNotNull();
  }

  @Test
  void countsUnreadNotifications() {
    Agent lead = agents.save(TestData.agent(Role.LEAD));
    Ticket ticket = tickets.save(TestData.manualTicket(lead));
    Notification read =
        notifications.save(new Notification(lead, NotificationType.SLA_BREACH, ticket, "breached"));
    notifications.save(new Notification(lead, NotificationType.ASSIGNED, ticket, "assigned"));
    read.markRead(Instant.now());
    flushAndClear();

    assertThat(notifications.countByRecipientIdAndReadAtIsNull(lead.getId())).isEqualTo(1);
    assertThat(
            notifications.findByRecipientIdOrderByCreatedAtDesc(
                lead.getId(), PageRequest.of(0, 10)))
        .hasSize(2);
  }

  @Test
  void roundTripsAiInsightWithJsonContent() {
    Agent agent = agents.save(TestData.agent(Role.AGENT));
    Ticket ticket = tickets.save(TestData.manualTicket(agent));
    insights.save(
        TicketAiInsight.builder()
            .ticket(ticket)
            .kind(InsightKind.SUMMARY)
            .content(Map.of("summary", "Customer waiting for refund", "sentiment", "NEGATIVE"))
            .model("test-model")
            .promptVersion("summary-v1")
            .createdBy(agent)
            .build());
    flushAndClear();

    TicketAiInsight loaded =
        insights
            .findFirstByTicketIdAndKindOrderByCreatedAtDesc(ticket.getId(), InsightKind.SUMMARY)
            .orElseThrow();
    assertThat(loaded.getContent()).containsEntry("sentiment", "NEGATIVE");
    assertThat(loaded.getAccepted()).isNull();
  }
}
