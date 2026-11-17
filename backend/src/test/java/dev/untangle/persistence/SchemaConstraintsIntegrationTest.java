package dev.untangle.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.untangle.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database is the last line of defence: even if a bug in Java let bad data through, these
 * constraints would reject it. Each test writes raw SQL to bypass the application on purpose.
 *
 * <p>{@code @Transactional}: every test rolls back, so nothing leaks between tests.
 */
@Transactional
class SchemaConstraintsIntegrationTest extends IntegrationTest {

  private UUID agentId;
  private UUID partnerId;

  @BeforeEach
  void createAgentAndPartner() {
    agentId =
        jdbc.queryForObject(
            "INSERT INTO agents (full_name, email, password_hash, role)"
                + " VALUES ('Asha Rao', 'asha@untangle.test', 'h', 'AGENT') RETURNING id",
            UUID.class);
    partnerId =
        jdbc.queryForObject(
            "INSERT INTO partners (code, name, secret_ciphertext)"
                + " VALUES ('acme-bank', 'Acme', 'c') RETURNING id",
            UUID.class);
  }

  /** Inserts a manual ticket with the given status/priority and optional extra columns. */
  private void insertManual(String priority, String status, String extraCols, String extraVals) {
    jdbc.update(
        "INSERT INTO tickets (subject, description, customer_name, category, priority, status,"
            + " source, created_by_agent_id, sla_due_at"
            + extraCols
            + ") VALUES ('Card blocked', 'Card blocked while travelling', 'Rahul', 'ACCOUNT', ?, ?,"
            + " 'MANUAL', ?, now()"
            + extraVals
            + ")",
        priority,
        status,
        agentId);
  }

  private void insertWebhook(UUID partner, String eventId) {
    jdbc.update(
        "INSERT INTO tickets (subject, description, customer_name, category, priority, source,"
            + " partner_id, external_event_id, sla_due_at) VALUES ('Refund', 'Refund not received"
            + " yet', 'Priya', 'REFUND', 'HIGH', 'WEBHOOK', ?, ?, now())",
        partner,
        eventId);
  }

  // ---- Requirement 8: every ticket must have a valid priority -------------------------------

  @ParameterizedTest(name = "priority \"{0}\" is rejected")
  @ValueSource(strings = {"high", "URGENT", "", "Critical", "P1"})
  void rejectsInvalidPriority(String priority) {
    assertThatThrownBy(() -> insertManual(priority, "OPEN", "", ""))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_tickets_priority");
  }

  @Test
  void rejectsNullPriority() {
    assertThatThrownBy(() -> insertManual(null, "OPEN", "", ""))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("priority");
  }

  @ParameterizedTest(name = "priority {0} is accepted")
  @ValueSource(strings = {"LOW", "MEDIUM", "HIGH", "CRITICAL"})
  void acceptsEveryValidPriority(String priority) {
    insertManual(priority, "OPEN", "", "");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Long.class)).isEqualTo(1);
  }

  @Test
  void rejectsUnknownStatus() {
    assertThatThrownBy(() -> insertManual("HIGH", "PENDING", "", ""))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_tickets_status");
  }

  // ---- Requirement 8: duplicate external event ids never create duplicate tickets -----------

  @Test
  void rejectsSameEventIdTwiceFromSamePartner() {
    insertWebhook(partnerId, "evt_001");
    assertThatThrownBy(() -> insertWebhook(partnerId, "evt_001"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ux_tickets_partner_event");
  }

  @Test
  void allowsSameEventIdFromDifferentPartners() {
    UUID otherPartner =
        jdbc.queryForObject(
            "INSERT INTO partners (code, name, secret_ciphertext)"
                + " VALUES ('globex-pay', 'Globex', 'c') RETURNING id",
            UUID.class);
    insertWebhook(partnerId, "evt_001");
    insertWebhook(otherPartner, "evt_001");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Long.class)).isEqualTo(2);
  }

  @Test
  void manualTicketsAreNotAffectedByEventUniqueness() {
    insertManual("LOW", "OPEN", "", "");
    insertManual("LOW", "OPEN", "", "");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Long.class)).isEqualTo(2);
  }

  // ---- Source consistency ------------------------------------------------------------------

  @Test
  void rejectsWebhookTicketWithoutEventId() {
    assertThatThrownBy(() -> insertWebhook(partnerId, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_tickets_source_consistency");
  }

  @Test
  void rejectsManualTicketCarryingAnEventId() {
    assertThatThrownBy(() ->
            insertManual(
                "LOW", "OPEN", ", partner_id, external_event_id", ", '" + partnerId + "', 'evt_x'"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_tickets_source_consistency");
  }

  @Test
  void rejectsManualTicketWithoutCreator() {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO tickets (subject, description, customer_name, category, priority,"
                    + " source, sla_due_at) VALUES ('s', 'long enough text', 'Rahul',"
                    + " 'OTHER', 'LOW', 'MANUAL', now())"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_tickets_creator");
  }

  // ---- Lifecycle invariants (defence in depth for the state machine) ------------------------

  @Test
  void rejectsClosedTicketWithoutClosedAt() {
    assertThatThrownBy(() -> insertManual("LOW", "CLOSED", "", ""))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_tickets_closed_has_timestamp");
  }

  @Test
  void rejectsResolvedTicketWithoutResolvedAt() {
    assertThatThrownBy(
            () -> insertManual("LOW", "RESOLVED", ", assigned_agent_id", ", '" + agentId + "'"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_tickets_resolved_has_timestamp");
  }

  @Test
  void rejectsInProgressTicketWithoutAssignee() {
    assertThatThrownBy(() -> insertManual("LOW", "IN_PROGRESS", "", ""))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_tickets_in_progress_assigned");
  }

  @Test
  void rejectsTooShortDescription() {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO tickets (subject, description, customer_name, category, priority,"
                    + " source, created_by_agent_id, sla_due_at) VALUES ('s', 'short', 'Rahul',"
                    + " 'OTHER', 'LOW', 'MANUAL', ?, now())",
                agentId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_tickets_description");
  }

  // ---- Ticket numbers ----------------------------------------------------------------------

  @Test
  void ticketNumbersDoNotTruncatePastSixDigits() {
    jdbc.execute("SELECT setval('ticket_number_seq', 999999)");
    insertManual("LOW", "OPEN", "", "");
    assertThat(jdbc.queryForObject("SELECT ticket_number FROM tickets", String.class))
        .isEqualTo("UNT-1000000");
  }

  // ---- Agents ------------------------------------------------------------------------------

  @Test
  void agentEmailsAreUniqueIgnoringCase() {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO agents (full_name, email, password_hash, role)"
                    + " VALUES ('Asha Again', 'ASHA@Untangle.Test', 'h', 'AGENT')"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ux_agents_email_lower");
  }

  @Test
  void rejectsUnknownRole() {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO agents (full_name, email, password_hash, role)"
                    + " VALUES ('Root', 'root@untangle.test', 'h', 'SUPERUSER')"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_agents_role");
  }

  // ---- Partners ----------------------------------------------------------------------------

  @ParameterizedTest(name = "partner code \"{0}\" is rejected")
  @ValueSource(strings = {"Acme Bank", "ACME", "-acme", "a", "acme_bank"})
  void rejectsMalformedPartnerCodes(String code) {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO partners (code, name, secret_ciphertext) VALUES (?, 'n', 'c')", code))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_partners_code_format");
  }

  @Test
  void previousSecretRequiresAnExpiry() {
    assertThatThrownBy(() ->
            jdbc.update(
                "UPDATE partners SET previous_secret_ciphertext = 'old' WHERE id = ?", partnerId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_partners_previous_secret");
  }

  // ---- Comments are an append-only audit trail ----------------------------------------------

  @Test
  void commentsCannotBeEdited() {
    insertManual("LOW", "OPEN", "", "");
    jdbc.update(
        "INSERT INTO ticket_comments (ticket_id, author_agent_id, type, body)"
            + " SELECT id, ?, 'COMMENT', 'original' FROM tickets",
        agentId);

    assertThatThrownBy(() -> jdbc.update("UPDATE ticket_comments SET body = 'rewritten history'"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void humanCommentsRequireAnAuthor() {
    insertManual("LOW", "OPEN", "", "");
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO ticket_comments (ticket_id, type, body)"
                    + " SELECT id, 'COMMENT', 'anonymous' FROM tickets"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_comments_author");
  }

  // ---- Webhook delivery log ----------------------------------------------------------------

  @Test
  void createdDeliveryMustLinkItsTicket() {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO webhook_deliveries (partner_id, outcome) VALUES (?, 'CREATED')",
                partnerId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_deliveries_ticket");
  }
}
