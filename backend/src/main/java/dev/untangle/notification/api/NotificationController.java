package dev.untangle.notification.api;

import dev.untangle.common.api.PageResponse;
import dev.untangle.notification.application.NotificationService;
import dev.untangle.notification.application.NotificationService.NotificationView;
import dev.untangle.notification.application.NotificationService.UnreadCount;
import dev.untangle.security.AuthenticatedAgent;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own notifications (bell + drawer). */
@Tag(name = "Notifications")
@Validated
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping
  public PageResponse<NotificationView> list(
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @AuthenticationPrincipal AuthenticatedAgent me) {
    return notifications.list(me.id(), unreadOnly, page, size);
  }

  @GetMapping("/unread-count")
  public UnreadCount unreadCount(@AuthenticationPrincipal AuthenticatedAgent me) {
    return notifications.unreadCount(me.id());
  }

  @PatchMapping("/{id}/read")
  public NotificationView markRead(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedAgent me) {
    return notifications.markRead(me.id(), id);
  }

  @PostMapping("/read-all")
  public UnreadCount markAllRead(@AuthenticationPrincipal AuthenticatedAgent me) {
    return notifications.markAllRead(me.id());
  }
}
