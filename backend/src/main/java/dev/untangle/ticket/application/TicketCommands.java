package dev.untangle.ticket.application;

import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.TicketStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Request bodies for tickets, validated with Bean Validation (§5.3). They mirror the frontend Zod
 * schemas.
 *
 * <p>Text fields are trimmed in the compact constructors, <b>before</b> validation runs. So {@code
 * " a "} counts as 1 character and fails the 2-character minimum, instead of passing validation and
 * then hitting a database CHECK.
 */
public final class TicketCommands {

  private TicketCommands() {}

  static String strip(String value) {
    return value == null ? null : value.strip();
  }

  static String blankToNull(String value) {
    String stripped = strip(value);
    return stripped == null || stripped.isEmpty() ? null : stripped;
  }

  public record CreateTicket(
      @NotBlank @Size(min = 5, max = 150) String subject,
      @NotBlank @Size(min = 10, max = 5000) String description,
      @NotBlank @Size(min = 2, max = 120) String customerName,
      @Email @Size(max = 254) String customerEmail,
      @NotNull Category category,
      @NotNull Priority priority,
      UUID assigneeId) {
    public CreateTicket {
      subject = strip(subject);
      description = strip(description);
      customerName = strip(customerName);
      customerEmail = blankToNull(customerEmail);
    }
  }

  /** Partial edit; {@code null} = unchanged. {@code version} is required (optimistic locking). */
  public record EditTicket(
      @Size(min = 5, max = 150) String subject,
      @Size(min = 10, max = 5000) String description,
      Category category,
      Priority priority,
      @NotNull Long version) {
    public EditTicket {
      subject = strip(subject);
      description = strip(description);
    }
  }

  /** {@code agentId = null} unassigns. */
  public record AssignTicket(UUID agentId, @NotNull Long version) {}

  public record TransitionTicket(
      @NotNull TicketStatus targetStatus,
      @Size(max = 5000) String note,
      @Size(max = 500) String reason,
      @NotNull Long version) {
    public TransitionTicket {
      note = blankToNull(note);
      reason = blankToNull(reason);
    }
  }

  public record ReopenTicket(@NotBlank @Size(max = 500) String reason, @NotNull Long version) {
    public ReopenTicket {
      reason = strip(reason);
    }
  }

  public record EscalateTicket(@NotBlank @Size(max = 500) String reason) {
    public EscalateTicket {
      reason = strip(reason);
    }
  }

  /** Only people-written types; SYSTEM entries can't be created through the API. */
  public enum NewCommentType {
    COMMENT,
    RESOLUTION_NOTE
  }

  public record AddComment(@NotBlank @Size(max = 5000) String body, NewCommentType type) {
    public AddComment {
      body = strip(body);
      type = type == null ? NewCommentType.COMMENT : type;
    }
  }

  public record BulkAssign(
      @NotEmpty @Size(max = 200) List<@NotNull UUID> ticketIds, @NotNull UUID agentId) {}
}
