package dev.untangle.ticket.infrastructure;

import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.Ticket;
import dev.untangle.ticket.domain.TicketStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable, type-safe filters for {@code GET /tickets} and the dashboard (JPA Criteria API).
 *
 * <p>Every value becomes a bind parameter, never string concatenation, so SQL injection is
 * impossible by construction (§6.3). The dashboard counts use the <b>same</b> specifications as the
 * list, so "SLA breached: 12" on the dashboard always matches the 12 rows the filter shows (§11).
 */
public final class TicketSpecifications {

  private TicketSpecifications() {}

  public static Specification<Ticket> statusIn(Collection<TicketStatus> statuses) {
    return (root, query, cb) -> root.get("status").in(statuses);
  }

  public static Specification<Ticket> priorityIn(Collection<Priority> priorities) {
    return (root, query, cb) -> root.get("priority").in(priorities);
  }

  public static Specification<Ticket> categoryIn(Collection<Category> categories) {
    return (root, query, cb) -> root.get("category").in(categories);
  }

  public static Specification<Ticket> assignedTo(UUID agentId) {
    return (root, query, cb) -> cb.equal(root.get("assignedAgent").get("id"), agentId);
  }

  public static Specification<Ticket> unassigned() {
    return (root, query, cb) -> cb.isNull(root.get("assignedAgent"));
  }

  public static Specification<Ticket> unresolved() {
    return statusIn(TicketStatus.UNRESOLVED);
  }

  public static Specification<Ticket> escalated() {
    return (root, query, cb) -> cb.greaterThan(root.get("escalationLevel"), (short) 0);
  }

  /** Unresolved and past its deadline. The ONE definition of "SLA breached". */
  public static Specification<Ticket> slaBreached(Instant now) {
    return unresolved().and((root, query, cb) -> cb.lessThan(root.get("slaDueAt"), now));
  }

  public static Specification<Ticket> createdFrom(Instant from) {
    return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
  }

  public static Specification<Ticket> createdBefore(Instant to) {
    return (root, query, cb) -> cb.lessThan(root.get("createdAt"), to);
  }

  /**
   * Case-insensitive "contains" over number, subject, customer and description. {@code ILIKE} can
   * use the pg_trgm GIN indexes. User input is escaped, so {@code 100%} searches for a literal
   * percent sign rather than acting as a wildcard.
   */
  public static Specification<Ticket> matches(String text) {
    String pattern = "%" + escapeLike(text.strip()) + "%";
    return (root, query, cb) -> {
      HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) cb;
      return cb.or(
          hcb.ilike(root.get("ticketNumber"), pattern, '\\'),
          hcb.ilike(root.get("subject"), pattern, '\\'),
          hcb.ilike(root.get("customerName"), pattern, '\\'),
          hcb.ilike(root.get("description"), pattern, '\\'));
    };
  }

  static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
