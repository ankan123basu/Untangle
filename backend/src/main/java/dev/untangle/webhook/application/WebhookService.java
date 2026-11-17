package dev.untangle.webhook.application;

import dev.untangle.common.error.ApiException;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.common.error.GlobalExceptionHandler.FieldViolation;
import dev.untangle.common.error.RateLimitExceededException;
import dev.untangle.common.ratelimit.RateLimitPolicy;
import dev.untangle.common.ratelimit.RateLimiter;
import dev.untangle.security.crypto.SecretCipher;
import dev.untangle.ticket.application.TicketService;
import dev.untangle.ticket.application.TicketService.WebhookTicketResult;
import dev.untangle.webhook.domain.DeliveryOutcome;
import dev.untangle.webhook.domain.Partner;
import dev.untangle.webhook.hmac.HmacSignatures;
import dev.untangle.webhook.infrastructure.PartnerRepository;
import dev.untangle.webhook.infrastructure.WebhookDeliveryRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The partner webhook pipeline (§7.2). The order matters: cheap checks first, and nothing about the
 * payload is trusted (or even parsed) until the signature is verified.
 *
 * <pre>
 *  0  per-IP rate limit (R2)                 → 429 RATE_LIMITED
 *  1  size ≤ 64 KB (WebhookBodyLimitFilter)  → 413
 *  2  required headers present & well formed → 401 (generic)
 *  3  partner exists and is active           → 401 (generic)
 *  4  |now − timestamp| ≤ 300 s              → 401 (generic)
 *  5  HMAC over timestamp + "." + raw body,
 *     current secret or previous in grace    → 401 (generic)
 *  6  per-partner rate limit                 → 429 + Retry-After
 *  7  parse + validate JSON                  → 400 with field errors
 *  8  idempotent create (DB unique index)    → 201 new / 200 duplicate
 *  9  every attempt → webhook_deliveries (precise reason, for admins)
 * </pre>
 *
 * All 401s carry the same generic body, so an attacker can't tell which check failed (ADR-018).
 */
@Service
public class WebhookService {

  public record Headers(String partnerId, String eventId, String timestamp, String signature) {}

  /** What the partner gets back for an accepted request. */
  public record WebhookResponse(java.util.UUID ticketId, String ticketNumber, boolean duplicate) {}

  private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern PARTNER_CODE = Pattern.compile("[a-z0-9-]{3,64}");
  private static final Pattern EPOCH_SECONDS = Pattern.compile("\\d{1,12}");
  static final String GENERIC_401 = "invalid webhook request";

  private final PartnerRepository partners;
  private final WebhookDeliveryRepository deliveries;
  private final SecretCipher cipher;
  private final TicketService ticketService;
  private final DeliveryRecorder recorder;
  private final RateLimiter rateLimiter;
  private final WebhookProperties properties;
  private final JsonMapper jsonMapper;
  private final Validator validator;
  private final Clock clock;

  public WebhookService(
      PartnerRepository partners,
      WebhookDeliveryRepository deliveries,
      SecretCipher cipher,
      TicketService ticketService,
      DeliveryRecorder recorder,
      RateLimiter rateLimiter,
      WebhookProperties properties,
      JsonMapper jsonMapper,
      Validator validator,
      Clock clock) {
    this.partners = partners;
    this.deliveries = deliveries;
    this.cipher = cipher;
    this.ticketService = ticketService;
    this.recorder = recorder;
    this.rateLimiter = rateLimiter;
    this.properties = properties;
    this.jsonMapper = jsonMapper;
    this.validator = validator;
    this.clock = clock;
  }

  public WebhookResponse receive(Headers h, byte[] body, String sourceIp) {
    String bodyHash = HmacSignatures.sha256Hex(body);
    Attempt attempt = new Attempt(h, sourceIp, bodyHash);

    // 0. Per-IP flood protection, before any crypto work.
    try {
      rateLimiter.consume("webhook-ip", properties.perIpLimit(), sourceIp);
    } catch (RateLimitExceededException e) {
      attempt.record(null, DeliveryOutcome.RATE_LIMITED, null, "per-IP limit exceeded");
      throw e;
    }

    // 2. Headers.
    if (h.partnerId() == null || !PARTNER_CODE.matcher(h.partnerId()).matches()) {
      throw attempt.reject(
          null, DeliveryOutcome.REJECTED_UNKNOWN_PARTNER, "missing or malformed X-Partner-Id");
    }
    if (h.eventId() == null || !EVENT_ID.matcher(h.eventId()).matches()) {
      throw attempt.reject(
          null, DeliveryOutcome.REJECTED_VALIDATION, "missing or malformed X-Event-Id");
    }
    if (h.timestamp() == null || !EPOCH_SECONDS.matcher(h.timestamp()).matches()) {
      throw attempt.reject(
          null, DeliveryOutcome.REJECTED_TIMESTAMP, "missing or non-numeric X-Timestamp");
    }
    if (!HmacSignatures.isWellFormed(h.signature())) {
      throw attempt.reject(
          null, DeliveryOutcome.REJECTED_SIGNATURE, "missing or malformed X-Signature");
    }

    // 3. Partner.
    Optional<Partner> found = partners.findByCode(h.partnerId());
    if (found.isEmpty()) {
      throw attempt.reject(null, DeliveryOutcome.REJECTED_UNKNOWN_PARTNER, "unknown partner");
    }
    Partner partner = found.get();
    if (!partner.isActive()) {
      throw attempt.reject(partner, DeliveryOutcome.REJECTED_UNKNOWN_PARTNER, "partner inactive");
    }

    // 4. Freshness (replay protection), both directions.
    Instant now = clock.instant();
    Instant sentAt = Instant.ofEpochSecond(Long.parseLong(h.timestamp()));
    Duration skew = Duration.between(sentAt, now).abs();
    if (skew.compareTo(properties.timestampTolerance()) > 0) {
      throw attempt.reject(
          partner,
          DeliveryOutcome.REJECTED_TIMESTAMP,
          "timestamp outside ±"
              + properties.timestampTolerance().toSeconds()
              + "s (skew "
              + skew.toSeconds()
              + "s, "
              + (sentAt.isAfter(now) ? "future" : "past")
              + ")");
    }

    // 5. Signature over the exact bytes received.
    if (!signatureMatches(partner, h, body, now)) {
      throw attempt.reject(partner, DeliveryOutcome.REJECTED_SIGNATURE, "signature mismatch");
    }

    // 6. Per-partner rate limit (authenticated, so it's safe to key on the partner).
    try {
      rateLimiter.consume(
          "webhook-partner",
          new RateLimitPolicy(partner.getRateLimitPerMinute(), Duration.ofMinutes(1)),
          partner.getCode() + ":" + partner.getRateLimitPerMinute());
    } catch (RateLimitExceededException e) {
      attempt.record(
          partner,
          DeliveryOutcome.RATE_LIMITED,
          null,
          "partner limit " + partner.getRateLimitPerMinute() + "/min exceeded");
      throw e;
    }

    // 7. A valid signature proves who sent it, not that the data is valid.
    WebhookTicketPayload payload = parseAndValidate(body, attempt, partner);

    // 8. Idempotent create, backed by the unique index.
    WebhookTicketResult result =
        ticketService.createFromWebhook(
            partner,
            h.eventId(),
            payload.effectiveSubject(),
            payload.description(),
            payload.customerName(),
            payload.customerEmail(),
            payload.category(),
            payload.priority());
    if (result.duplicate()) {
      // R3: same event id, different body. Still answer with the original ticket (A8), but flag
      // it for admins, since it usually means a bug on the partner's side.
      boolean samePayload =
          deliveries
              .findFirstByTicketIdAndOutcome(result.ticket().getId(), DeliveryOutcome.CREATED)
              .map(original -> bodyHash.equals(original.getPayloadSha256()))
              .orElse(true);
      attempt.record(
          partner,
          DeliveryOutcome.DUPLICATE,
          result.ticket(),
          samePayload
              ? "event already processed"
              : "event already processed; payload differs from the original");
    } else {
      attempt.record(partner, DeliveryOutcome.CREATED, result.ticket(), null);
    }
    return new WebhookResponse(
        result.ticket().getId(), result.ticket().getTicketNumber(), result.duplicate());
  }

  private boolean signatureMatches(Partner partner, Headers h, byte[] body, Instant now) {
    String current = cipher.decrypt(partner.getSecretCiphertext(), partner.getCode());
    if (HmacSignatures.verify(current, h.timestamp(), body, h.signature())) {
      return true;
    }
    if (partner.hasPreviousSecretValidAt(now)) {
      String previous = cipher.decrypt(partner.getPreviousSecretCiphertext(), partner.getCode());
      return HmacSignatures.verify(previous, h.timestamp(), body, h.signature());
    }
    return false;
  }

  private WebhookTicketPayload parseAndValidate(byte[] body, Attempt attempt, Partner partner) {
    WebhookTicketPayload payload;
    try {
      payload = jsonMapper.readValue(body, WebhookTicketPayload.class);
    } catch (DatabindException e) {
      String field = e.getPath().isEmpty() ? null : e.getPath().getLast().getPropertyName();
      attempt.record(
          partner,
          DeliveryOutcome.REJECTED_VALIDATION,
          null,
          field == null ? "malformed JSON" : "invalid value for " + field);
      throw validation(
          field == null ? List.of() : List.of(new FieldViolation(field, "has an invalid value")),
          field == null);
    } catch (JacksonException e) {
      attempt.record(partner, DeliveryOutcome.REJECTED_VALIDATION, null, "malformed JSON");
      throw validation(List.of(), true);
    }
    if (payload == null) {
      attempt.record(partner, DeliveryOutcome.REJECTED_VALIDATION, null, "empty body");
      throw validation(List.of(), true);
    }
    Set<ConstraintViolation<WebhookTicketPayload>> violations = validator.validate(payload);
    if (!violations.isEmpty()) {
      List<FieldViolation> errors =
          violations.stream()
              .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
              .sorted(java.util.Comparator.comparing(FieldViolation::field))
              .toList();
      attempt.record(
          partner,
          DeliveryOutcome.REJECTED_VALIDATION,
          null,
          "invalid fields: " + errors.stream().map(FieldViolation::field).toList());
      throw validation(errors, false);
    }
    return payload;
  }

  private static ApiException validation(List<FieldViolation> errors, boolean malformed) {
    return new ApiException(
            ErrorCode.VALIDATION_FAILED,
            malformed ? "Malformed JSON request body" : "Payload has invalid fields")
        .with("errors", errors);
  }

  /** Remembers request facts so every exit path records one delivery row. */
  private final class Attempt {
    private final Headers headers;
    private final String sourceIp;
    private final String bodyHash;

    Attempt(Headers headers, String sourceIp, String bodyHash) {
      this.headers = headers;
      this.sourceIp = sourceIp;
      this.bodyHash = bodyHash;
    }

    void record(
        Partner partner,
        DeliveryOutcome outcome,
        dev.untangle.ticket.domain.Ticket ticket,
        String reason) {
      recorder.record(
          headers.partnerId(),
          partner,
          headers.eventId(),
          outcome,
          ticket,
          reason,
          sourceIp,
          bodyHash);
    }

    ApiException reject(Partner partner, DeliveryOutcome outcome, String reason) {
      record(partner, outcome, null, reason);
      return new ApiException(ErrorCode.WEBHOOK_REJECTED, GENERIC_401);
    }
  }
}
