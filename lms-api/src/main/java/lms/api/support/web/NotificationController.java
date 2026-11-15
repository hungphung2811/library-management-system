package lms.api.support.web;

import java.util.List;
import java.util.UUID;
import lms.api.support.client.SupportClient;
import lms.api.support.dto.MarkAllReadView;
import lms.api.support.dto.NotificationView;
import lms.api.support.dto.UnreadCountView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API cho FE: thông báo của người đang đăng nhập (support-service lọc theo token và tenant). */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final SupportClient support;

    public NotificationController(SupportClient support) {
        this.support = support;
    }

    @GetMapping("/me")
    public List<NotificationView> mine() {
        return support.myNotifications();
    }

    @GetMapping("/me/unread-count")
    public UnreadCountView unreadCount() {
        return support.myUnreadNotificationCount();
    }

    @PostMapping("/{id}/read")
    public NotificationView read(@PathVariable UUID id) {
        return support.markNotificationRead(id);
    }

    @PostMapping("/read-all")
    public MarkAllReadView readAll() {
        return support.markAllNotificationsRead();
    }
}
