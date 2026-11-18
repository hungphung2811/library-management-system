package lms.support.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.support.notification.domain.Delivery;
import lms.support.notification.domain.DeliveryStatus;
import lms.support.notification.domain.NotificationChannel;

/** Lưu trữ delivery của tenant hiện tại (TenantContext); mọi phương thức chạy trong transaction của use case. */
public interface DeliveryPort {

    /** Chèn nếu chưa có theo khóa (tenant, eventId, channel, recipientKey); true nếu chèn mới. */
    boolean insertIfAbsent(Delivery delivery);

    /**
     * Lấy tối đa {@code limit} delivery PENDING/RETRY đã đến hạn (theo next_attempt_at), khóa và bỏ qua dòng đang bị
     * instance khác khóa, chuyển sang SENDING rồi trả về.
     */
    List<Delivery> leaseDue(int limit, Instant now);

    void save(Delivery delivery);

    Optional<Delivery> find(UUID id);

    /** Số delivery SENT của tenant hiện tại từ {@code since}. */
    long countSentSince(Instant since);

    /** Số delivery SENT của mọi tenant từ {@code since} (phạm vi đọc ALL, cho giới hạn toàn cục). */
    long countSentSinceAllTenants(Instant since);

    /** Đưa dòng SENDING không đổi từ {@code staleBefore} về RETRY; trả số dòng. */
    int releaseStuck(Instant staleBefore, Instant now);

    DeliveryPage search(DeliveryCriteria criteria);

    List<Delivery> findByRecipient(UUID userId, int limit);

    /** Xóa delivery kết thúc cũ hơn {@code before}; trả số dòng. */
    int deleteFinishedBefore(Instant before);

    record DeliveryCriteria(DeliveryStatus status, NotificationChannel channel, String type, Instant from, Instant to,
                            int page, int size) {
    }

    record DeliveryPage(List<Delivery> items, long total, int page, int size) {
    }
}
