package dev.untangle.comment.infrastructure;

import dev.untangle.comment.domain.TicketComment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketCommentRepository extends JpaRepository<TicketComment, UUID> {

  /**
   * The timeline, oldest first (index {@code ix_comments_ticket_created}). Ties (same transaction)
   * are broken by the UUIDv7 id, which is time-ordered too. The entity graph loads each author in
   * the same query, so 30 comments cost one SQL statement, not 31 (no N+1).
   */
  @EntityGraph(attributePaths = "author")
  List<TicketComment> findByTicketIdOrderByCreatedAtAscIdAsc(UUID ticketId);
}
