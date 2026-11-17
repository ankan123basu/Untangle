package dev.untangle.ticket.application;

import static dev.untangle.ticket.infrastructure.TicketSpecifications.*;

import dev.untangle.common.api.PageResponse;
import dev.untangle.common.error.ApiException;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.common.error.GlobalExceptionHandler.FieldViolation;
import dev.untangle.ticket.application.TicketViews.TicketSummary;
import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.Ticket;
import dev.untangle.ticket.domain.TicketStatus;
import dev.untangle.ticket.infrastructure.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code GET /tickets}: filtering, sorting and pagination (§9). Read-only. */
@Service
public class TicketQueryService {

  /** Every supported filter; {@code null} or empty = not filtered. */
  public record TicketFilter(
      Set<TicketStatus> status,
      Set<Priority> priority,
      Set<Category> category,
      UUID assigneeId,
      Boolean unassigned,
      Boolean mine,
      Boolean escalated,
      Boolean slaBreached,
      String q,
      Instant createdFrom,
      Instant createdTo) {}

  public static final int MAX_PAGE_SIZE = 100;

  /** Public sort keys → entity attributes. Unknown keys are a 400, never passed through. */
  private static final Map<String, String> SORTABLE =
      Map.of(
          "createdAt", "createdAt",
          "updatedAt", "updatedAt",
          "priority", "priorityRank",
          "status", "statusRank",
          "slaDueAt", "slaDueAt",
          "ticketNumber", "ticketNumber");

  private final TicketRepository tickets;
  private final TicketMapper mapper;
  private final Clock clock;

  public TicketQueryService(TicketRepository tickets, TicketMapper mapper, Clock clock) {
    this.tickets = tickets;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<TicketSummary> search(
      TicketFilter filter, int page, int size, String sort, UUID currentAgentId) {
    Instant now = clock.instant();
    PageRequest pageRequest = PageRequest.of(page, size, parseSort(sort));
    return PageResponse.of(
        tickets.findAll(toSpecification(filter, currentAgentId, now), pageRequest),
        t -> mapper.toSummary(t, now));
  }

  /** Builds the WHERE clause. Package-visible so dashboard counts reuse exactly the same rules. */
  static Specification<Ticket> toSpecification(TicketFilter f, UUID currentAgentId, Instant now) {
    List<Specification<Ticket>> specs = new ArrayList<>();
    if (f.status() != null && !f.status().isEmpty()) specs.add(statusIn(f.status()));
    if (f.priority() != null && !f.priority().isEmpty()) specs.add(priorityIn(f.priority()));
    if (f.category() != null && !f.category().isEmpty()) specs.add(categoryIn(f.category()));
    if (Boolean.TRUE.equals(f.unassigned())) {
      specs.add(unassigned());
    } else if (f.assigneeId() != null) {
      specs.add(assignedTo(f.assigneeId()));
    }
    if (Boolean.TRUE.equals(f.mine())) specs.add(assignedTo(currentAgentId));
    if (Boolean.TRUE.equals(f.escalated())) specs.add(escalated());
    if (Boolean.TRUE.equals(f.slaBreached())) specs.add(slaBreached(now));
    if (f.q() != null && !f.q().isBlank()) specs.add(matches(f.q()));
    if (f.createdFrom() != null) specs.add(createdFrom(f.createdFrom()));
    if (f.createdTo() != null) specs.add(createdBefore(f.createdTo()));
    return specs.isEmpty() ? Specification.unrestricted() : Specification.allOf(specs);
  }

  /**
   * Parses {@code field[,asc|desc]}. Adds {@code id} as a tie-breaker so pages never repeat or skip
   * rows when many tickets share a sort value.
   */
  static Sort parseSort(String sort) {
    String[] parts = (sort == null || sort.isBlank() ? "createdAt,desc" : sort).split(",");
    String attribute = SORTABLE.get(parts[0].strip());
    Sort.Direction direction = Sort.Direction.DESC;
    if (parts.length > 1) {
      try {
        direction = Sort.Direction.fromString(parts[1].strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        attribute = null;
      }
    }
    if (attribute == null || parts.length > 2) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported sort")
          .with(
              "errors",
              List.of(
                  new FieldViolation(
                      "sort",
                      "must be one of " + SORTABLE.keySet() + " with optional ,asc|,desc")));
    }
    return Sort.by(direction, attribute).and(Sort.by(direction, "id"));
  }
}
