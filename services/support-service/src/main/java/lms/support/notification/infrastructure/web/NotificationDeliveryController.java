package lms.support.notification.infrastructure.web;

import java.time.Instant;
import java.util.UUID;
import lms.security.CurrentUser;
import lms.support.notification.application.DeliveryPort.DeliveryCriteria;
import lms.support.notification.application.DeliveryUseCases;
import lms.support.notification.application.DeliveryUseCases.DeliveryPageView;
import lms.support.notification.application.DeliveryUseCases.DeliveryView;
import lms.support.notification.domain.DeliveryStatus;
import lms.support.notification.domain.NotificationChannel;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lịch sử gửi và hàng chờ lỗi của tenant (quản trị): khóa động NotificationDeliveryController/search và
 * NotificationDeliveryController/retry, mặc định chỉ admin tenant (ADR-0030 3.2). Địa chỉ chỉ ở dạng đã che.
 */
@RestController
@RequestMapping("/internal/notification-deliveries")
public class NotificationDeliveryController {

    /** Tham số lọc (record, Spring tự bind): type là mã loại thông báo, from/to ISO-8601 theo createdAt. */
    public record DeliverySearchQuery(DeliveryStatus status, NotificationChannel channel, String type, Instant from,
                                      Instant to, Integer page, Integer size) {
    }

    private final DeliveryUseCases deliveries;

    public NotificationDeliveryController(DeliveryUseCases deliveries) {
        this.deliveries = deliveries;
    }

    @GetMapping
    public DeliveryPageView search(DeliverySearchQuery query) {
        return deliveries.search(new DeliveryCriteria(query.status(), query.channel(), query.type(), query.from(),
                query.to(), query.page() == null ? 0 : query.page(), query.size() == null ? 20 : query.size()));
    }

    /** Đưa lượt DEAD về PENDING (409 DELIVERY_NOT_RETRYABLE nếu không phải DEAD), ghi người bấm. */
    @PostMapping("/{id}/retry")
    public DeliveryView retry(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return deliveries.retry(id, CurrentUser.from(jwt).userId());
    }
}
