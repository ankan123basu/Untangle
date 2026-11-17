package dev.untangle.ticket.api;

import dev.untangle.comment.application.Timeline;
import dev.untangle.common.api.PageResponse;
import dev.untangle.security.AuthenticatedAgent;
import dev.untangle.ticket.application.TicketCommands.AddComment;
import dev.untangle.ticket.application.TicketCommands.AssignTicket;
import dev.untangle.ticket.application.TicketCommands.BulkAssign;
import dev.untangle.ticket.application.TicketCommands.CreateTicket;
import dev.untangle.ticket.application.TicketCommands.EditTicket;
import dev.untangle.ticket.application.TicketCommands.EscalateTicket;
import dev.untangle.ticket.application.TicketCommands.ReopenTicket;
import dev.untangle.ticket.application.TicketCommands.TransitionTicket;
import dev.untangle.ticket.application.TicketQueryService;
import dev.untangle.ticket.application.TicketQueryService.TicketFilter;
import dev.untangle.ticket.application.TicketService;
import dev.untangle.ticket.application.TicketViews.BulkAssignResult;
import dev.untangle.ticket.application.TicketViews.TicketDetail;
import dev.untangle.ticket.application.TicketViews.TicketSummary;
import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.TicketStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tickets API (§9). Controllers are thin: validate input, pass the caller, return the view. Coarse
 * permission checks live here; per-ticket rules (ownership, state, closed) live in {@code
 * TicketAccessPolicy}, applied by {@link TicketService}.
 */
@Tag(name = "Tickets")
@Validated
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

  private final TicketService tickets;
  private final TicketQueryService queries;

  public TicketController(TicketService tickets, TicketQueryService queries) {
    this.tickets = tickets;
    this.queries = queries;
  }

  @Operation(summary = "Search and filter tickets (paginated)")
  @GetMapping
  @PreAuthorize("hasAuthority('TICKET_VIEW')")
  public PageResponse<TicketSummary> list(
      @RequestParam(required = false) Set<TicketStatus> status,
      @RequestParam(required = false) Set<Priority> priority,
      @RequestParam(required = false) Set<Category> category,
      @RequestParam(required = false) UUID assigneeId,
      @RequestParam(required = false) Boolean unassigned,
      @RequestParam(required = false) Boolean mine,
      @RequestParam(required = false) Boolean escalated,
      @RequestParam(required = false) Boolean slaBreached,
      @RequestParam(required = false) @Size(max = 200) String q,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant createdFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant createdTo,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(TicketQueryService.MAX_PAGE_SIZE) int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort,
      @AuthenticationPrincipal AuthenticatedAgent me) {
    TicketFilter filter =
        new TicketFilter(
            status,
            priority,
            category,
            assigneeId,
            unassigned,
            mine,
            escalated,
            slaBreached,
            q,
            createdFrom,
            createdTo);
    return queries.search(filter, page, size, sort, me.id());
  }

  @Operation(summary = "Create a ticket (manual intake)")
  @PostMapping
  @PreAuthorize("hasAuthority('TICKET_CREATE')")
  public ResponseEntity<TicketDetail> create(
      @Valid @RequestBody CreateTicket request, @AuthenticationPrincipal AuthenticatedAgent me) {
    TicketDetail created = tickets.create(request, me);
    return ResponseEntity.created(URI.create("/api/v1/tickets/" + created.id())).body(created);
  }

  @Operation(summary = "Ticket detail, including allowedActions for the caller")
  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('TICKET_VIEW')")
  public TicketDetail get(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedAgent me) {
    return tickets.get(id, me);
  }

  @Operation(summary = "Look up a ticket by its number, e.g. UNT-000042 (command palette)")
  @GetMapping("/by-number/{ticketNumber}")
  @PreAuthorize("hasAuthority('TICKET_VIEW')")
  public TicketDetail getByNumber(
      @PathVariable String ticketNumber, @AuthenticationPrincipal AuthenticatedAgent me) {
    return tickets.getByNumber(ticketNumber, me);
  }

  @Operation(summary = "Edit subject, description, category or priority")
  @PatchMapping("/{id}")
  public TicketDetail edit(
      @PathVariable UUID id,
      @Valid @RequestBody EditTicket request,
      @AuthenticationPrincipal AuthenticatedAgent me) {
    return tickets.edit(id, request, me);
  }

  @Operation(summary = "Assign, reassign or unassign (agentId null)")
  @PutMapping("/{id}/assignee")
  public TicketDetail assign(
      @PathVariable UUID id,
      @Valid @RequestBody AssignTicket request,
      @AuthenticationPrincipal AuthenticatedAgent me) {
    return tickets.assign(id, request, me);
  }

  @Operation(summary = "Reassign many tickets at once (leads/admins)")
  @PostMapping("/bulk/assign")
  @PreAuthorize("hasAuthority('TICKET_ASSIGN_ANY')")
  public BulkAssignResult bulkAssign(
      @Valid @RequestBody BulkAssign request, @AuthenticationPrincipal AuthenticatedAgent me) {
    return tickets.bulkAssign(request, me);
  }

  @Operation(summary = "Change status (see docs/state-machine.md)")
  @PostMapping("/{id}/transitions")
  public TicketDetail transition(
      @PathVariable UUID id,
      @Valid @RequestBody TransitionTicket request,
      @AuthenticationPrincipal AuthenticatedAgent me) {
    return tickets.transition(id, request, me);
  }

  @Operation(summary = "Reopen a CLOSED ticket (leads/admins, reason required)")
  @PostMapping("/{id}/reopen")
  public TicketDetail reopen(
      @PathVariable UUID id,
      @Valid @RequestBody ReopenTicket request,
      @AuthenticationPrincipal AuthenticatedAgent me) {
    return tickets.reopen(id, request, me);
  }

  @Operation(summary = "Escalate manually (reason required)")
  @PostMapping("/{id}/escalate")
  public TicketDetail escalate(
      @PathVariable UUID id,
      @Valid @RequestBody EscalateTicket request,
      @AuthenticationPrincipal AuthenticatedAgent me) {
    return tickets.escalate(id, request, me);
  }

  @Operation(summary = "Timeline: comments, resolution notes and system events (oldest first)")
  @GetMapping("/{id}/comments")
  @PreAuthorize("hasAuthority('TICKET_VIEW')")
  public List<Timeline.Entry> comments(@PathVariable UUID id) {
    return tickets.timeline(id);
  }

  @Operation(summary = "Add a comment or resolution note")
  @PostMapping("/{id}/comments")
  @ResponseStatus(HttpStatus.CREATED)
  public Timeline.Entry addComment(
      @PathVariable UUID id,
      @Valid @RequestBody AddComment request,
      @AuthenticationPrincipal AuthenticatedAgent me) {
    return tickets.addComment(id, request, me);
  }
}
