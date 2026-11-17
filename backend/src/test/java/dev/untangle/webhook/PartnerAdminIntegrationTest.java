package dev.untangle.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.agent.domain.Role;
import dev.untangle.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Partner administration and secret storage (§7.3). */
class PartnerAdminIntegrationTest extends IntegrationTest {

  private String adminBearer;

  @BeforeEach
  void signIn() {
    adminBearer = bearer(Role.ADMIN);
  }

  @AfterEach
  void cleanUp() {
    truncateAllTables();
  }

  private MvcTestResult create(String code) {
    return post(
        "/api/v1/admin/partners",
        adminBearer,
        "{\"code\":\"" + code + "\",\"name\":\"Acme Bank\"}");
  }

  @Test
  void secretIsShownOnceAndStoredOnlyEncrypted() {
    MvcTestResult created = create("acme-bank");

    assertThat(created).hasStatus(201);
    String secret = json(created, "$.secret");
    assertThat(secret).hasSizeGreaterThanOrEqualTo(43); // 32 bytes base64url
    assertThat(created).bodyJson().extractingPath("$.partner.rateLimitPerMinute").isEqualTo(60);

    assertThat(body(get("/api/v1/admin/partners", adminBearer)))
        .doesNotContain(secret)
        .doesNotContain("secret\"");
    String stored =
        jdbc.queryForObject(
            "SELECT secret_ciphertext FROM partners WHERE code = 'acme-bank'", String.class);
    assertThat(stored).startsWith("v1:").doesNotContain(secret);
  }

  @Test
  void rotationIssuesANewSecretAndKeepsTheOldOneForTheGraceWindow() {
    String id = json(create("acme-bank"), "$.partner.id");

    MvcTestResult rotated =
        post("/api/v1/admin/partners/" + id + "/rotate-secret", adminBearer, null);

    assertThat(rotated).hasStatusOk();
    assertThat(rotated).bodyJson().extractingPath("$.partner.previousSecretValidUntil").isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT previous_secret_ciphertext IS NOT NULL FROM partners WHERE id = ?::uuid",
                Boolean.class,
                id))
        .isTrue();
  }

  @Test
  void rejectsDuplicateAndMalformedCodes() {
    create("acme-bank");
    assertThat(create("acme-bank"))
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("PARTNER_CODE_TAKEN");
    assertThat(create("Acme Bank")).hasStatus(400);
  }

  @Test
  void onlyAdminsManagePartnersAndSeeDeliveryLogs() {
    String lead = bearer(Role.LEAD);
    assertThat(get("/api/v1/admin/partners", lead)).hasStatus(403);
    assertThat(post("/api/v1/admin/partners", lead, "{\"code\":\"x-y-z\",\"name\":\"X\"}"))
        .hasStatus(403);
    assertThat(get("/api/v1/admin/webhook-deliveries", lead)).hasStatus(403);
  }

  @Test
  void updatesNameRateLimitAndActiveFlag() {
    String id = json(create("acme-bank"), "$.partner.id");
    MvcTestResult updated =
        call(
            HttpMethod.PATCH,
            "/api/v1/admin/partners/" + id,
            adminBearer,
            "{\"name\":\"Acme Bank PLC\",\"rateLimitPerMinute\":5}");
    assertThat(updated).bodyJson().extractingPath("$.rateLimitPerMinute").isEqualTo(5);
    MvcTestResult off =
        call(
            HttpMethod.PATCH,
            "/api/v1/admin/partners/" + id + "/active",
            adminBearer,
            "{\"active\":false}");
    assertThat(off).bodyJson().extractingPath("$.active").isEqualTo(false);
  }
}
