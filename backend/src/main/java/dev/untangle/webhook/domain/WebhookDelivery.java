package dev.untangle.webhook.domain;

import dev.untangle.common.audit.BaseEntity;
import dev.untangle.ticket.domain.Ticket;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

/**
 * Audit record of one webhook request, accepted or rejected (table {@code webhook_deliveries}).
 *
 * <p>We store a SHA-256 fingerprint of the body, never the body itself (it contains customer PII).
 * Rows are written once and never changed ({@code @Immutable}).
 */
@Getter
@Entity
@Immutable
@Table(name = "webhook_deliveries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookDelivery extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "partner_id")
  private Partner partner;

  @Column(name = "partner_code_raw", length = 128)
  private String partnerCodeRaw;

  @Column(name = "event_id", length = 128)
  private String eventId;

  @CreationTimestamp
  @Column(name = "received_at", nullable = false, updatable = false)
  private Instant receivedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, length = 30)
  private DeliveryOutcome outcome;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "ticket_id")
  private Ticket ticket;

  @Column(name = "reason", length = 500)
  private String reason;

  @Column(name = "source_ip", length = 45)
  private String sourceIp;

  @Column(name = "payload_sha256", length = 64)
  private String payloadSha256;

  @Builder
  private WebhookDelivery(
      Partner partner,
      String partnerCodeRaw,
      String eventId,
      DeliveryOutcome outcome,
      Ticket ticket,
      String reason,
      String sourceIp,
      String payloadSha256) {
    this.partner = partner;
    this.partnerCodeRaw = partnerCodeRaw;
    this.eventId = eventId;
    this.outcome = outcome;
    this.ticket = ticket;
    this.reason = reason;
    this.sourceIp = sourceIp;
    this.payloadSha256 = payloadSha256;
  }
}
