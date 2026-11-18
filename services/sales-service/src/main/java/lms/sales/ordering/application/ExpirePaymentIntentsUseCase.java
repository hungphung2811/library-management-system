package lms.sales.ordering.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dọn lệnh thanh toán QR quá hạn của một tenant (job nền gọi, đã đặt TenantContext). KHÔNG transactional: mỗi lệnh là
 * một transaction riêng ({@link PaymentIntentUseCases#expireOne}), lệnh lỗi (vd vừa được thu cùng lúc) chỉ ghi log rồi
 * sang lệnh kế, không kéo theo các lệnh khác. Trả số lệnh đã hết hạn.
 */
public class ExpirePaymentIntentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpirePaymentIntentsUseCase.class);

    private final PaymentIntentRepository intents;
    private final PaymentIntentUseCases useCases;
    private final Clock clock;
    private final int batchSize;

    public ExpirePaymentIntentsUseCase(PaymentIntentRepository intents, PaymentIntentUseCases useCases, Clock clock,
                                       int batchSize) {
        this.intents = intents;
        this.useCases = useCases;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    public int execute(UUID tenantId) {
        List<UUID> ids = intents.findExpiredPendingIds(tenantId, clock.instant(), batchSize);
        int expired = 0;
        for (UUID id : ids) {
            try {
                if (useCases.expireOne(tenantId, id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Hết hạn lệnh thanh toán {} lỗi: {}", id, e.getClass().getSimpleName());
            }
        }
        return expired;
    }
}
