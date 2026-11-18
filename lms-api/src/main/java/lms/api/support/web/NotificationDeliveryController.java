package lms.api.support.web;

import java.util.UUID;
import lms.api.support.client.NotificationAdminClient;
import lms.api.support.dto.NotificationAdminDtos.DeliveryPageView;
import lms.api.support.dto.NotificationAdminDtos.DeliveryView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API cho FE (ADR-0030 D8): lịch sử gửi, hàng chờ lỗi (DEAD) và gửi lại. Quyền do support-service kiểm. */
@RestController
@RequestMapping("/api/notification-deliveries")
public class NotificationDeliveryController {

    /** Tham số lọc và phân trang; Spring tự bind từ query string. from/to là ISO-8601 theo thời điểm tạo. */
    public record DeliveryQuery(String status, String channel, String type, String from, String to, Integer page,
                                Integer size) {
    }

    private final NotificationAdminClient notifications;

    public NotificationDeliveryController(NotificationAdminClient notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public DeliveryPageView search(DeliveryQuery query) {
        return notifications.searchDeliveries(query.status(), query.channel(), query.type(), query.from(), query.to(),
                query.page() == null ? 0 : query.page(), query.size() == null ? 20 : query.size());
    }

    @PostMapping("/{id}/retry")
    public DeliveryView retry(@PathVariable UUID id) {
        return notifications.retryDelivery(id);
    }
}
