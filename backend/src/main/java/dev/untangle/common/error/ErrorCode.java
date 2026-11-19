package dev.untangle.common.error;

import org.springframework.http.HttpStatus;

/**
 * Every machine-readable error the API can return, with its HTTP status.
 *
 * <p>Clients branch on {@code code}, never on the human-readable text, so wording can change
 * freely. The frontend maps each code to a friendly message in one place (Section 12.6). Adding a
 * code is fine; renaming or removing one is a breaking API change.
 */
public enum ErrorCode {
  // ---- Request shape ----
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
  NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
  PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Payload too large"),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),

  // ---- Authentication (401 / 423) ----
  AUTH_UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
  AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
  AUTH_ACCOUNT_LOCKED(HttpStatus.LOCKED, "Account temporarily locked"),
  AUTH_ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "Account is deactivated"),
  AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Access token expired"),
  AUTH_REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "Session expired, please sign in again"),
  AUTH_REFRESH_REUSED(HttpStatus.UNAUTHORIZED, "Session revoked for security reasons"),

  // ---- Authorisation (403) ----
  ACCESS_DENIED(HttpStatus.FORBIDDEN, "You are not allowed to do this"),

  // ---- Agents ----
  AGENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Agent not found"),
  AGENT_INACTIVE(HttpStatus.CONFLICT, "Agent is deactivated"),
  EMAIL_TAKEN(HttpStatus.CONFLICT, "Email already in use"),
  LAST_ADMIN(HttpStatus.CONFLICT, "Cannot remove the last active admin"),
  CANNOT_DEACTIVATE_SELF(HttpStatus.CONFLICT, "You cannot deactivate your own account"),
  AGENT_HAS_OPEN_TICKETS(HttpStatus.CONFLICT, "Agent still has unresolved tickets"),

  // ---- Tickets (Phase 3) ----
  TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, "Ticket not found"),
  TICKET_CLOSED(HttpStatus.CONFLICT, "Ticket is closed"),
  INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "Status transition not allowed"),
  RESOLUTION_NOTE_REQUIRED(HttpStatus.BAD_REQUEST, "A resolution note is required"),
  ASSIGNEE_REQUIRED(HttpStatus.CONFLICT, "Ticket must be assigned first"),
  VERSION_CONFLICT(HttpStatus.CONFLICT, "This record was changed by someone else"),

  // ---- Integrations ----
  WEBHOOK_REJECTED(HttpStatus.UNAUTHORIZED, "Invalid webhook request"),
  PARTNER_NOT_FOUND(HttpStatus.NOT_FOUND, "Partner not found"),
  PARTNER_CODE_TAKEN(HttpStatus.CONFLICT, "Partner code already in use"),
  AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI assistance is unavailable"),
  AI_PROPOSAL_NOT_FOUND(HttpStatus.NOT_FOUND, "No pending AI proposal for this ticket"),
  AI_PROPOSAL_STALE(HttpStatus.CONFLICT, "This AI proposal is no longer current"),
  AI_TEAMMATE_FIXED(HttpStatus.CONFLICT, "The AI teammate's role and sign-in cannot be changed"),

  // ---- Capacity ----
  SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Service is busy"),

  // ---- Fallback ----
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");

  private final HttpStatus status;
  private final String title;

  ErrorCode(HttpStatus status, String title) {
    this.status = status;
    this.title = title;
  }

  public HttpStatus status() {
    return status;
  }

  public String title() {
    return title;
  }
}
