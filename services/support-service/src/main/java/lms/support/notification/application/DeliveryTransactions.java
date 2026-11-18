package lms.support.notification.application;

import java.time.Instant;
import java.util.List;
import lms.support.notification.domain.Delivery;
import org.springframework.transaction.annotation.Transactional;

/**
 * Các bước ngắn của dispatcher, MỖI bước một transaction audit_db: lấy lô (khóa + chuyển SENDING rồi commit), ghi
 * kết quả từng lượt gửi. Việc gửi (gọi SMTP, identity) nằm NGOÀI transaction nên không giữ khóa hàng hay kết nối DB
 * trong lúc chờ mạng (ADR-0030 D8). Tách thành bean riêng để proxy @Transactional có hiệu lực (không gọi nội bộ).
 */
public class DeliveryTransactions {

    private final DeliveryPort deliveries;

    public DeliveryTransactions(DeliveryPort deliveries) {
        this.deliveries = deliveries;
    }

    @Transactional(NotificationTransactions.MANAGER)
    public List<Delivery> lease(int limit, Instant now) {
        return deliveries.leaseDue(limit, now);
    }

    @Transactional(NotificationTransactions.MANAGER)
    public void record(Delivery delivery) {
        deliveries.save(delivery);
    }

    @Transactional(NotificationTransactions.MANAGER)
    public int releaseStuck(Instant staleBefore, Instant now) {
        return deliveries.releaseStuck(staleBefore, now);
    }

    @Transactional(value = NotificationTransactions.MANAGER, readOnly = true)
    public long countSentSince(Instant since) {
        return deliveries.countSentSince(since);
    }

    /** Gọi khi TenantContext đang ở phạm vi đọc ALL (kết nối nhận app.tenant_scope lúc bắt đầu transaction). */
    @Transactional(value = NotificationTransactions.MANAGER, readOnly = true)
    public long countSentSinceAllTenants(Instant since) {
        return deliveries.countSentSinceAllTenants(since);
    }

    @Transactional(NotificationTransactions.MANAGER)
    public int purgeFinishedBefore(Instant before) {
        return deliveries.deleteFinishedBefore(before);
    }
}
