package dev.untangle.notification.infrastructure;

import dev.untangle.notification.domain.Notification;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /** Notification drawer, newest first (index {@code ix_notifications_recipient}). */
  Page<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);

  /** Bell badge (partial index {@code ix_notifications_unread}). */
  long countByRecipientIdAndReadAtIsNull(UUID recipientId);
}
