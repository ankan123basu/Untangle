package dev.untangle.webhook.application;

import dev.untangle.common.api.PageResponse;
import dev.untangle.common.error.ApiException;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.security.crypto.SecretCipher;
import dev.untangle.webhook.domain.DeliveryOutcome;
import dev.untangle.webhook.domain.Partner;
import dev.untangle.webhook.domain.WebhookDelivery;
import dev.untangle.webhook.infrastructure.PartnerRepository;
import dev.untangle.webhook.infrastructure.WebhookDeliveryRepository;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partner integrations for admins (who: admins; why: onboard partners, rotate secrets, debug
 * deliveries). Secrets are generated here (32 random bytes, base64url), returned exactly once, and
 * stored only encrypted.
 */
@Service
public class PartnerAdmin {

  private static final Logger log = LoggerFactory.getLogger(PartnerAdmin.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  // ---- request / response shapes ----

  public record CreatePartner(
      @NotBlank
          @Pattern(
              regexp = "[a-z0-9][a-z0-9-]{1,62}[a-z0-9]",
              message = "lowercase letters, digits and dashes, 3-64 characters")
          String code,
      @NotBlank @Size(min = 2, max = 120) String name,
      @Min(1) @Max(10000) Integer rateLimitPerMinute) {}

  public record UpdatePartner(
      @Size(min = 2, max = 120) String name, @Min(1) @Max(10000) Integer rateLimitPerMinute) {}

  public record SetActive(@NotNull Boolean active) {}

  public record PartnerView(
      UUID id,
      String code,
      String name,
      boolean active,
      int rateLimitPerMinute,
      @Nullable Instant previousSecretValidUntil,
      Instant createdAt,
      Instant updatedAt) {
    static PartnerView of(Partner p, Instant now) {
      return new PartnerView(
          p.getId(),
          p.getCode(),
          p.getName(),
          p.isActive(),
          p.getRateLimitPerMinute(),
          p.hasPreviousSecretValidAt(now) ? p.getPreviousSecretExpiresAt() : null,
          p.getCreatedAt(),
          p.getUpdatedAt());
    }
  }

  /** Returned only by create/rotate: the plaintext secret, shown once. */
  public record IssuedSecret(PartnerView partner, String secret, String notice) {
    @Override
    public String toString() {
      return "IssuedSecret[partner=" + partner.code() + ", secret=<redacted>]";
    }
  }

  public record DeliveryView(
      UUID id,
      Instant receivedAt,
      @Nullable String partnerCode,
      @Nullable String eventId,
      DeliveryOutcome outcome,
      @Nullable String reason,
      @Nullable UUID ticketId,
      @Nullable String ticketNumber,
      @Nullable String sourceIp,
      @Nullable String payloadSha256) {
    static DeliveryView of(WebhookDelivery d) {
      return new DeliveryView(
          d.getId(),
          d.getReceivedAt(),
          d.getPartner() != null ? d.getPartner().getCode() : d.getPartnerCodeRaw(),
          d.getEventId(),
          d.getOutcome(),
          d.getReason(),
          d.getTicket() == null ? null : d.getTicket().getId(),
          d.getTicket() == null ? null : d.getTicket().getTicketNumber(),
          d.getSourceIp(),
          d.getPayloadSha256());
    }
  }

  private final PartnerRepository partners;
  private final WebhookDeliveryRepository deliveries;
  private final SecretCipher cipher;
  private final WebhookProperties properties;
  private final Clock clock;

  public PartnerAdmin(
      PartnerRepository partners,
      WebhookDeliveryRepository deliveries,
      SecretCipher cipher,
      WebhookProperties properties,
      Clock clock) {
    this.partners = partners;
    this.deliveries = deliveries;
    this.cipher = cipher;
    this.properties = properties;
    this.clock = clock;
  }

  static String newSecret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  @Transactional(readOnly = true)
  public List<PartnerView> list() {
    Instant now = clock.instant();
    return partners.findAll(Sort.by("code")).stream().map(p -> PartnerView.of(p, now)).toList();
  }

  @Transactional
  public IssuedSecret create(CreatePartner command) {
    if (partners.findByCode(command.code()).isPresent()) {
      throw new ApiException(ErrorCode.PARTNER_CODE_TAKEN);
    }
    String secret = newSecret();
    Partner partner =
        new Partner(
            command.code(),
            command.name().strip(),
            cipher.encrypt(secret, command.code()),
            command.rateLimitPerMinute() == null ? 60 : command.rateLimitPerMinute());
    try {
      partners.saveAndFlush(partner);
    } catch (DataIntegrityViolationException e) {
      throw new ApiException(ErrorCode.PARTNER_CODE_TAKEN);
    }
    log.info("Partner created code={}", partner.getCode());
    return new IssuedSecret(
        PartnerView.of(partner, clock.instant()),
        secret,
        "Store this secret now. It cannot be shown again.");
  }

  @Transactional
  public IssuedSecret rotateSecret(UUID id) {
    Partner partner = load(id);
    String secret = newSecret();
    Instant graceEnds = clock.instant().plus(properties.rotationGrace());
    partner.rotateSecret(cipher.encrypt(secret, partner.getCode()), graceEnds);
    partners.flush();
    log.info("Partner secret rotated code={} previousValidUntil={}", partner.getCode(), graceEnds);
    return new IssuedSecret(
        PartnerView.of(partner, clock.instant()),
        secret,
        "Store this secret now. The previous secret keeps working until " + graceEnds + ".");
  }

  @Transactional
  public PartnerView update(UUID id, UpdatePartner command) {
    Partner partner = load(id);
    if (command.name() != null) partner.rename(command.name());
    if (command.rateLimitPerMinute() != null) partner.changeRateLimit(command.rateLimitPerMinute());
    partners.flush();
    return PartnerView.of(partner, clock.instant());
  }

  @Transactional
  public PartnerView setActive(UUID id, boolean active) {
    Partner partner = load(id);
    partner.setActive(active);
    partners.flush();
    log.info("Partner {} code={}", active ? "activated" : "deactivated", partner.getCode());
    return PartnerView.of(partner, clock.instant());
  }

  @Transactional(readOnly = true)
  public PageResponse<DeliveryView> deliveries(
      UUID partnerId, DeliveryOutcome outcome, Instant from, Instant to, int page, int size) {
    List<Specification<WebhookDelivery>> specs = new ArrayList<>();
    if (partnerId != null) specs.add((r, q, cb) -> cb.equal(r.get("partner").get("id"), partnerId));
    if (outcome != null) specs.add((r, q, cb) -> cb.equal(r.get("outcome"), outcome));
    if (from != null) specs.add((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("receivedAt"), from));
    if (to != null) specs.add((r, q, cb) -> cb.lessThan(r.get("receivedAt"), to));
    Specification<WebhookDelivery> spec =
        specs.isEmpty() ? Specification.unrestricted() : Specification.allOf(specs);
    PageRequest request =
        PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.DESC, "receivedAt").and(Sort.by(Sort.Direction.DESC, "id")));
    return PageResponse.of(deliveries.findAll(spec, request), DeliveryView::of);
  }

  private Partner load(UUID id) {
    return partners.findById(id).orElseThrow(() -> new ApiException(ErrorCode.PARTNER_NOT_FOUND));
  }
}
