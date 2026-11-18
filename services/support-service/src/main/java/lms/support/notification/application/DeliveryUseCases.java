package lms.support.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.support.notification.application.DeliveryPort.DeliveryCriteria;
import lms.support.notification.application.DeliveryPort.DeliveryPage;
import lms.support.notification.domain.Delivery;
import lms.support.notification.domain.DeliveryNotFoundException;
import org.springframework.transaction.annotation.Transactional;

/** Xem lịch sử gửi, gửi lại lượt DEAD, xem lịch sử của chính mình, dọn dữ liệu cũ (ADR-0030 D8). */
public class DeliveryUseCases {

    /** Góc nhìn quản trị: địa chỉ chỉ ở dạng che; không có tham số (đã giới hạn là dữ liệu không cá nhân) lẫn nội dung. */
    public record DeliveryView(UUID id, UUID eventId, String eventType, String type, String category, String channel,
                               String status, String skipReason, UUID recipientUserId, String recipientAddressMasked,
                               int attempts, Instant nextAttemptAt, String lastErrorCode, Instant lastErrorAt,
                               Instant sentAt, String templateSource, Integer templateVersion, UUID lastRetryBy,
                               Instant lastRetryAt, Instant createdAt) {
        static DeliveryView of(Delivery d) {
            return new DeliveryView(d.id(), d.eventId(), d.eventType(), d.type().name(), d.type().category().name(),
                    d.channel().name(), d.status().name(), d.skipReason() == null ? null : d.skipReason().name(),
                    d.recipientUserId(), d.recipientAddressMasked(), d.attempts(), d.nextAttemptAt(),
                    d.lastErrorCode(), d.lastErrorAt(), d.sentAt(), d.templateSource(), d.templateVersion(),
                    d.lastRetryBy(), d.lastRetryAt(), d.createdAt());
        }
    }

    /** Góc nhìn của người nhận: chỉ gồm điều họ cần biết (đã gửi gì, đến đâu, vì sao không gửi). */
    public record MyDeliveryView(UUID id, String type, String category, String channel, String status,
                                 String skipReason, String recipientAddressMasked, Instant sentAt, Instant createdAt) {
        static MyDeliveryView of(Delivery d) {
            return new MyDeliveryView(d.id(), d.type().name(), d.type().category().name(), d.channel().name(),
                    d.status().name(), d.skipReason() == null ? null : d.skipReason().name(),
                    d.recipientAddressMasked(), d.sentAt(), d.createdAt());
        }
    }

    public record DeliveryPageView(List<DeliveryView> items, long total, int page, int size) {
    }

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MY_LIMIT = 50;

    private final DeliveryPort deliveries;
    private final Clock clock;

    public DeliveryUseCases(DeliveryPort deliveries, Clock clock) {
        this.deliveries = deliveries;
        this.clock = clock;
    }

    @Transactional(value = NotificationTransactions.MANAGER, readOnly = true)
    public DeliveryPageView search(DeliveryCriteria criteria) {
        int size = Math.min(Math.max(criteria.size(), 1), MAX_PAGE_SIZE);
        int page = Math.max(criteria.page(), 0);
        DeliveryPage result = deliveries.search(new DeliveryCriteria(criteria.status(), criteria.channel(),
                criteria.type(), criteria.from(), criteria.to(), page, size));
        return new DeliveryPageView(result.items().stream().map(DeliveryView::of).toList(), result.total(), page, size);
    }

    /** Đưa lượt DEAD về PENDING (attempts giữ, mở đợt thử mới), ghi người bấm. */
    @Transactional(NotificationTransactions.MANAGER)
    public DeliveryView retry(UUID id, UUID by) {
        Delivery d = deliveries.find(id).orElseThrow(DeliveryNotFoundException::new);
        d.requeue(by, clock.instant());
        deliveries.save(d);
        return DeliveryView.of(d);
    }

    @Transactional(value = NotificationTransactions.MANAGER, readOnly = true)
    public List<MyDeliveryView> mine(UUID userId) {
        return deliveries.findByRecipient(userId, MY_LIMIT).stream().map(MyDeliveryView::of).toList();
    }

    /** Dọn delivery đã kết thúc quá hạn lưu giữ trong tenant hiện tại. */
    @Transactional(NotificationTransactions.MANAGER)
    public int purge(java.time.Duration retention) {
        return deliveries.deleteFinishedBefore(clock.instant().minus(retention));
    }
}
