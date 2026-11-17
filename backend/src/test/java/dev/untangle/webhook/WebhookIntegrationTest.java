package dev.untangle.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import dev.untangle.webhook.hmac.HmacSignatures;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The signed partner webhook end to end (§7, §11 "Webhook / HMAC"). Each test acts like a partner:
 * it signs the raw bytes with its own copy of the secret.
 */
class WebhookIntegrationTest extends IntegrationTest {

  static final String URI = "/api/v1/webhooks/partners/tickets";
  static final String BODY =
      """
      {"customerName":"Rahul Sharma","customerEmail":"rahul@example.com",
       "subject":"Refund not received",
       "description":"I cancelled my order 7 days ago and the refund has not arrived.",
       "category":"REFUND","priority":"HIGH"}\
      """;

  private String adminBearer;
  private Partner acme;

  record Partner(String id, String code, String secret) {}

  @BeforeEach
  void setUp() {
    adminBearer = bearer(Role.ADMIN);
    acme = createPartner("acme-" + suffix(), 1000);
  }

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  // ---- partner-side helpers -----------------------------------------------------------------

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 6);
  }

  private Partner createPartner(String code, int ratePerMinute) {
    MvcTestResult created =
        post(
            "/api/v1/admin/partners",
            adminBearer,
            "{\"code\":\"%s\",\"name\":\"%s Ltd\",\"rateLimitPerMinute\":%d}"
                .formatted(code, code, ratePerMinute));
    assertThat(created).hasStatus(201);
    return new Partner(json(created, "$.partner.id"), code, json(created, "$.secret"));
  }

  private static String now() {
    return Long.toString(Instant.now().getEpochSecond());
  }

  /** Sends exactly the given headers (null = header omitted). */
  private MvcTestResult send(Map<String, String> headers, byte[] body) {
    var request = mvc.post().uri(URI).contentType(MediaType.APPLICATION_JSON).content(body);
    for (var h : headers.entrySet()) {
      if (h.getValue() != null) request = request.header(h.getKey(), h.getValue());
    }
    return request.exchange();
  }

  private Map<String, String> signedHeaders(
      Partner p, String eventId, String ts, byte[] body, String secret) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("X-Partner-Id", p.code());
    h.put("X-Event-Id", eventId);
    h.put("X-Timestamp", ts);
    h.put("X-Signature", HmacSignatures.sign(secret, ts, body));
    return h;
  }

  private MvcTestResult sendSigned(Partner p, String eventId, String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    return send(signedHeaders(p, eventId, now(), bytes, p.secret()), bytes);
  }

  private void assertGeneric401(MvcTestResult result) {
    assertThat(result).hasStatus(401);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("WEBHOOK_REJECTED");
    assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("invalid webhook request");
    assertThat(body(result)).doesNotContainIgnoringCase("signature").doesNotContain("timestamp");
  }

  private List<Map<String, Object>> deliveries(String query) {
    return json(get("/api/v1/admin/webhook-deliveries?" + query, adminBearer), "$.content");
  }

  // ---- happy path & idempotency -------------------------------------------------------------

  @Test
  void validSignedEventCreatesAWebhookTicket() {
    MvcTestResult result = sendSigned(acme, "evt_001", BODY);

    assertThat(result).hasStatus(201);
    assertThat(result).bodyJson().extractingPath("$.duplicate").isEqualTo(false);
    assertThat((String) json(result, "$.ticketNumber")).matches("UNT-\\d{6,}");

    MvcTestResult ticket = get("/api/v1/tickets/" + json(result, "$.ticketId"), adminBearer);
    assertThat(ticket).bodyJson().extractingPath("$.source").isEqualTo("WEBHOOK");
    assertThat(ticket).bodyJson().extractingPath("$.partnerCode").isEqualTo(acme.code());
    assertThat(ticket).bodyJson().extractingPath("$.externalEventId").isEqualTo("evt_001");
    assertThat(ticket).bodyJson().extractingPath("$.createdBy").isNull();
    assertThat(deliveries("outcome=CREATED")).hasSize(1);
  }

  @Test
  void duplicateEventReturns200WithTheSameTicket() {
    MvcTestResult first = sendSigned(acme, "evt_dup", BODY);
    MvcTestResult retry = sendSigned(acme, "evt_dup", BODY);

    assertThat(first).hasStatus(201);
    assertThat(retry).hasStatus(200);
    assertThat(retry).bodyJson().extractingPath("$.duplicate").isEqualTo(true);
    assertThat((String) json(retry, "$.ticketId")).isEqualTo(json(first, "$.ticketId"));
    assertThat(deliveries("outcome=DUPLICATE").get(0).get("reason"))
        .isEqualTo("event already processed");
  }

  @Test
  void duplicateWithADifferentPayloadIsFlaggedForAdmins() {
    sendSigned(acme, "evt_changed", BODY);
    MvcTestResult retry = sendSigned(acme, "evt_changed", BODY.replace("HIGH", "CRITICAL"));

    assertThat(retry).hasStatus(200);
    assertThat((String) deliveries("outcome=DUPLICATE").get(0).get("reason"))
        .contains("payload differs");
  }

  @Test
  void twentyConcurrentRetriesCreateExactlyOneTicket() throws Exception {
    byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
    Map<String, String> headers = signedHeaders(acme, "evt_storm", now(), bytes, acme.secret());
    CountDownLatch go = new CountDownLatch(1);
    List<Future<MvcTestResult>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
      for (int i = 0; i < 20; i++) {
        futures.add(
            pool.submit(() -> {
              go.await();
              return send(headers, bytes);
            }));
      }
      go.countDown();
    }
    List<Integer> statuses = new ArrayList<>();
    List<String> ticketIds = new ArrayList<>();
    for (Future<MvcTestResult> f : futures) {
      statuses.add(f.get().getResponse().getStatus());
      ticketIds.add(json(f.get(), "$.ticketId"));
    }

    assertThat(statuses).containsOnly(200, 201).filteredOn(s -> s == 201).hasSize(1);
    assertThat(ticketIds).containsOnly(ticketIds.get(0));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM tickets WHERE external_event_id = 'evt_storm'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void sameEventIdFromTwoPartnersCreatesTwoTickets() {
    Partner globex = createPartner("globex-" + suffix(), 1000);
    assertThat(sendSigned(acme, "evt_shared", BODY)).hasStatus(201);
    assertThat(sendSigned(globex, "evt_shared", BODY)).hasStatus(201);
  }

  @Test
  void missingSubjectIsDerivedFromTheDescription() {
    MvcTestResult result =
        sendSigned(acme, "evt_nosubject", BODY.replace("\"subject\":\"Refund not received\",", ""));
    assertThat(result).hasStatus(201);
    MvcTestResult ticket = get("/api/v1/tickets/" + json(result, "$.ticketId"), adminBearer);
    assertThat((String) json(ticket, "$.subject")).startsWith("I cancelled my order 7 days ago");
  }

  // ---- authentication failures: all generic 401, precise reason logged ------------------------

  @Test
  void rejectsMissingHeaders() {
    byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
    for (String missing : List.of("X-Partner-Id", "X-Event-Id", "X-Timestamp", "X-Signature")) {
      Map<String, String> headers = signedHeaders(acme, "evt_h", now(), bytes, acme.secret());
      headers.put(missing, null);
      assertGeneric401(send(headers, bytes));
    }
  }

  @Test
  void rejectsMalformedSignatureHeader() {
    byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
    Map<String, String> headers = signedHeaders(acme, "evt_m", now(), bytes, acme.secret());
    headers.put("X-Signature", headers.get("X-Signature").replace("sha256=", "md5="));
    assertGeneric401(send(headers, bytes));
  }

  @Test
  void rejectsWrongSecret() {
    byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
    assertGeneric401(send(signedHeaders(acme, "evt_w", now(), bytes, "not-the-secret"), bytes));
    assertThat(deliveries("outcome=REJECTED_SIGNATURE").get(0).get("reason"))
        .isEqualTo("signature mismatch");
  }

  @Test
  void rejectsATamperedBody() {
    byte[] signed = BODY.getBytes(StandardCharsets.UTF_8);
    byte[] tampered = BODY.replace("HIGH", "LOW").getBytes(StandardCharsets.UTF_8);
    assertGeneric401(send(signedHeaders(acme, "evt_t", now(), signed, acme.secret()), tampered));
  }

  @Test
  void rejectsAWhitespaceOnlyChange() {
    byte[] signed = BODY.getBytes(StandardCharsets.UTF_8);
    byte[] reformatted = BODY.replace("\":\"", "\": \"").getBytes(StandardCharsets.UTF_8);
    assertGeneric401(
        send(signedHeaders(acme, "evt_ws", now(), signed, acme.secret()), reformatted));
  }

  @Test
  void rejectsExpiredFutureAndNonNumericTimestamps() {
    byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
    long nowSec = Instant.now().getEpochSecond();
    assertGeneric401(
        send(
            signedHeaders(acme, "evt_old", Long.toString(nowSec - 600), bytes, acme.secret()),
            bytes));
    assertGeneric401(
        send(
            signedHeaders(acme, "evt_new", Long.toString(nowSec + 600), bytes, acme.secret()),
            bytes));
    assertGeneric401(
        send(signedHeaders(acme, "evt_nan", "yesterday", bytes, acme.secret()), bytes));

    List<Map<String, Object>> rejected = deliveries("outcome=REJECTED_TIMESTAMP");
    assertThat(rejected)
        .extracting(d -> (String) d.get("reason"))
        .anySatisfy(r -> assertThat(r).contains("past"))
        .anySatisfy(r -> assertThat(r).contains("future"))
        .anySatisfy(r -> assertThat(r).contains("non-numeric"));
  }

  @Test
  void rejectsUnknownAndInactivePartners() {
    Partner ghost = new Partner(null, "ghost-partner", "whatever");
    assertGeneric401(sendSigned(ghost, "evt_g", BODY));

    call(
        org.springframework.http.HttpMethod.PATCH,
        "/api/v1/admin/partners/" + acme.id() + "/active",
        adminBearer,
        "{\"active\":false}");
    assertGeneric401(sendSigned(acme, "evt_i", BODY));
    assertThat(deliveries("outcome=REJECTED_UNKNOWN_PARTNER"))
        .extracting(d -> d.get("reason"))
        .contains("unknown partner", "partner inactive");
  }

  // ---- payload problems (signature valid) -----------------------------------------------------

  @Test
  void validSignatureButInvalidPriorityIs400() {
    MvcTestResult result = sendSigned(acme, "evt_p", BODY.replace("\"HIGH\"", "\"high\""));
    assertThat(result).hasStatus(400);
    assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("priority");
    assertThat(deliveries("outcome=REJECTED_VALIDATION")).hasSize(1);
  }

  @Test
  void validSignatureButMissingFieldsIs400() {
    MvcTestResult result =
        sendSigned(
            acme,
            "evt_f",
            "{\"customerName\":\"R\",\"description\":\"short\",\"category\":\"REFUND\"}");
    assertThat(result).hasStatus(400);
    assertThat((List<String>) json(result, "$.errors[*].field"))
        .contains("customerName", "description", "priority");
  }

  @Test
  void malformedJsonIs400() {
    MvcTestResult result = sendSigned(acme, "evt_j", "{\"customerName\": ");
    assertThat(result).hasStatus(400);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Malformed JSON request body");
  }

  @Test
  void oversizedPayloadIs413BeforeAnythingElse() {
    String huge = BODY.replace("I cancelled", "x".repeat(70_000) + " I cancelled");
    MvcTestResult result = sendSigned(acme, "evt_big", huge);

    assertThat(result).hasStatus(413);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
    assertThat(deliveries("outcome=REJECTED_TOO_LARGE")).hasSize(1);
  }

  @Test
  void perPartnerRateLimitReturns429WithRetryAfter() {
    Partner slow = createPartner("slow-" + suffix(), 2);
    assertThat(sendSigned(slow, "evt_r1", BODY)).hasStatus(201);
    assertThat(sendSigned(slow, "evt_r2", BODY)).hasStatus(201);

    MvcTestResult limited = sendSigned(slow, "evt_r3", BODY);

    assertThat(limited).hasStatus(429);
    assertThat(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
    assertThat(deliveries("outcome=RATE_LIMITED")).hasSize(1);
  }

  // ---- secret rotation ----------------------------------------------------------------------

  @Test
  void oldSecretWorksInsideTheGraceWindowOnly() {
    MvcTestResult rotated =
        post("/api/v1/admin/partners/" + acme.id() + "/rotate-secret", adminBearer, null);
    assertThat(rotated).hasStatusOk();
    Partner current = new Partner(acme.id(), acme.code(), json(rotated, "$.secret"));
    assertThat(current.secret()).isNotEqualTo(acme.secret());

    assertThat(sendSigned(acme, "evt_old_secret_in_grace", BODY)).hasStatus(201);
    assertThat(sendSigned(current, "evt_new_secret", BODY)).hasStatus(201);

    jdbc.update(
        "UPDATE partners SET previous_secret_expires_at = now() - interval '1 second' WHERE code = ?",
        acme.code());
    assertGeneric401(sendSigned(acme, "evt_old_secret_after_grace", BODY));
    assertThat(sendSigned(current, "evt_new_secret_2", BODY)).hasStatus(201);
  }
}
