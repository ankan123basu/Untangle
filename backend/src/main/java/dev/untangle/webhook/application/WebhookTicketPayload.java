package dev.untangle.webhook.application;

import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The partner's JSON body (§7.1). Same rules as a manual ticket (§5.3), except that {@code subject}
 * is optional: if missing, it's derived from the first 80 characters of the description. Unknown
 * extra fields are ignored, so partners can add fields without breaking.
 */
public record WebhookTicketPayload(
    @NotBlank @Size(min = 2, max = 120) String customerName,
    @Email @Size(max = 254) String customerEmail,
    @Size(max = 150) String subject,
    @NotBlank @Size(min = 10, max = 5000) String description,
    @NotNull Category category,
    @NotNull Priority priority) {

  public WebhookTicketPayload {
    customerName = customerName == null ? null : customerName.strip();
    customerEmail = customerEmail == null || customerEmail.isBlank() ? null : customerEmail.strip();
    subject = subject == null || subject.isBlank() ? null : subject.strip();
    description = description == null ? null : description.strip();
  }

  /** Subject to store: the given one, or the start of the description (§5.3). */
  public String effectiveSubject() {
    if (subject != null) {
      return subject;
    }
    String flat = description.replaceAll("\\s+", " ");
    return flat.length() <= 80 ? flat : flat.substring(0, 77).strip() + "...";
  }
}
