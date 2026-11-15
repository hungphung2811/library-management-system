package lms.support.notification.infrastructure.web;

import java.util.List;
import java.util.UUID;
import lms.security.CurrentUser;
import lms.security.permission.AuthenticatedOnly;
import lms.support.notification.application.CountUnreadNotificationsUseCase;
import lms.support.notification.application.ListMyNotificationsUseCase;
import lms.support.notification.application.MarkAllNotificationsReadUseCase;
import lms.support.notification.application.MarkNotificationReadUseCase;
import lms.support.notification.application.NotificationResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Người dùng chỉ thao tác trên thông báo của chính mình: userId lấy từ token, tenant từ TenantContext. */
@RestController
@RequestMapping("/internal/notifications")
public class NotificationController {

    public record UnreadCountResponse(long unread) {
    }

    public record MarkAllReadResponse(int updated) {
    }

    private final ListMyNotificationsUseCase listMine;
    private final MarkNotificationReadUseCase markRead;
    private final MarkAllNotificationsReadUseCase markAllRead;
    private final CountUnreadNotificationsUseCase countUnread;

    public NotificationController(ListMyNotificationsUseCase listMine, MarkNotificationReadUseCase markRead,
                                  MarkAllNotificationsReadUseCase markAllRead,
                                  CountUnreadNotificationsUseCase countUnread) {
        this.listMine = listMine;
        this.markRead = markRead;
        this.markAllRead = markAllRead;
        this.countUnread = countUnread;
    }

    @AuthenticatedOnly
    @GetMapping("/me")
    public List<NotificationResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return listMine.execute(CurrentUser.from(jwt).userId());
    }

    @AuthenticatedOnly
    @GetMapping("/me/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return new UnreadCountResponse(countUnread.execute(CurrentUser.from(jwt).userId()));
    }

    @AuthenticatedOnly
    @PostMapping("/{id}/read")
    public NotificationResponse read(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return markRead.execute(id, CurrentUser.from(jwt).userId());
    }

    @AuthenticatedOnly
    @PostMapping("/read-all")
    public MarkAllReadResponse readAll(@AuthenticationPrincipal Jwt jwt) {
        return new MarkAllReadResponse(markAllRead.execute(CurrentUser.from(jwt).userId()));
    }
}
