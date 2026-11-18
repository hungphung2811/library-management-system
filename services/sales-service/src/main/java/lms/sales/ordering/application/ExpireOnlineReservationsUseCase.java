package lms.sales.ordering.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dọn đơn online quá hạn giữ hàng của MỘT tenant (job nền gọi, đã đặt TenantContext). KHÔNG transactional: mỗi đơn là
 * một transaction riêng ({@link OnlineReservationExpiry#expireOne}), đơn lỗi chỉ ghi log rồi sang đơn kế để một đơn hỏng
 * không kéo theo cả lô. Trả số đơn đã hết hạn. Nhiều worker/instance chạy cùng lúc không xử lý trùng nhờ khóa dòng
 * {@code FOR UPDATE SKIP LOCKED} ở từng đơn.
 */
public class ExpireOnlineReservationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpireOnlineReservationsUseCase.class);

    private final SalesOrderRepository orders;
    private final OnlineReservationExpiry expiry;
    private final Clock clock;
    private final int batchSize;

    public ExpireOnlineReservationsUseCase(SalesOrderRepository orders, OnlineReservationExpiry expiry, Clock clock,
                                           int batchSize) {
        this.orders = orders;
        this.expiry = expiry;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    public int execute(UUID tenantId) {
        List<UUID> ids = orders.findExpiredReservationIds(tenantId, clock.instant(), batchSize);
        int expired = 0;
        for (UUID id : ids) {
            try {
                if (expiry.expireOne(tenantId, id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                // Thua cuộc đua với xác nhận/hủy cùng lúc (409) hoặc lỗi tạm: lượt sau job quét lại nếu đơn còn quá hạn
                log.warn("Hết hạn giữ hàng của đơn {} lỗi: {}", id, e.getClass().getSimpleName());
            }
        }
        return expired;
    }
}
