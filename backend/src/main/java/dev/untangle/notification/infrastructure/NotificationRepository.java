package dev.untangle.notification.infrastructure;

import dev.untangle.notification.domain.Notification;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /** Notification drawer, newest first (index {@code ix_notifications_recipient}). */
  @EntityGraph(attributePaths = "ticket")
  Page<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);

  /** Bell badge (partial index {@code ix_notifications_unread}). */
  long countByRecipientIdAndReadAtIsNull(UUID recipientId);

  @EntityGraph(attributePaths = "ticket")
  Page<Notification> findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(
      UUID recipientId, Pageable pageable);

  Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

  @Modifying
  @Query(
      "update Notification n set n.readAt = :now"
          + " where n.recipient.id = :recipientId and n.readAt is null")
  int markAllRead(UUID recipientId, Instant now);
}
