package dev.untangle.comment.application;

import dev.untangle.agent.domain.Agent;
import dev.untangle.comment.domain.CommentType;
import dev.untangle.comment.domain.TicketComment;
import dev.untangle.comment.infrastructure.TicketCommentRepository;
import dev.untangle.ticket.domain.Ticket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and appends a ticket's timeline. The ticket feature calls this; comments never call back
 * into tickets, so the dependency is one-way (ticket → comment).
 *
 * <p>Permission and closed-ticket checks happen in {@code TicketService} <b>before</b> anything is
 * appended here.
 */
@Service
public class Timeline {

  public record AuthorRef(UUID id, String fullName) {}

  /** One timeline entry. {@code body} is plain text; the UI must render it as text, never HTML. */
  public record Entry(
      UUID id,
      CommentType type,
      String body,
      AuthorRef author,
      Map<String, Object> metadata,
      Instant createdAt) {

    static Entry of(TicketComment c) {
      Agent a = c.getAuthor();
      return new Entry(
          c.getId(),
          c.getType(),
          c.getBody(),
          a == null ? null : new AuthorRef(a.getId(), a.getFullName()),
          c.getMetadata(),
          c.getCreatedAt());
    }
  }

  private final TicketCommentRepository comments;

  public Timeline(TicketCommentRepository comments) {
    this.comments = comments;
  }

  @Transactional(readOnly = true)
  public List<Entry> list(UUID ticketId) {
    return comments.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId).stream()
        .map(Entry::of)
        .toList();
  }

  @Transactional
  public Entry byAgent(Ticket ticket, Agent author, CommentType type, String body) {
    TicketComment saved = comments.saveAndFlush(TicketComment.byAgent(ticket, author, type, body));
    return Entry.of(saved);
  }

  /** A system event; {@code actor} is the person who caused it, or null for automatic events. */
  @Transactional
  public void system(Ticket ticket, Agent actor, String body, Map<String, Object> metadata) {
    comments.save(TicketComment.system(ticket, actor, body, metadata));
  }
}
