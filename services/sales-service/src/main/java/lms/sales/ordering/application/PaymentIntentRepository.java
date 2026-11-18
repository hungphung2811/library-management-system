package lms.sales.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentIntent;

/**
 * Lệnh thanh toán theo tenant (RLS). {@code save}: version lệch (hai tiến trình cùng đổi) thì ném 409
 * PAYMENT_INTENT_STATE_CONFLICT; trùng mã giao dịch của cổng ở lệnh khác thì 409 PAYMENT_DUPLICATE_TRANSACTION.
 */
public interface PaymentIntentRepository {

    PaymentIntent save(PaymentIntent intent);

    Optional<PaymentIntent> findById(UUID tenantId, UUID id);

    Optional<PaymentIntent> findByReference(UUID tenantId, String reference);

    Optional<PaymentIntent> findByOrderId(UUID tenantId, UUID orderId);

    /** Mã các lệnh PENDING đã quá hạn (expiresAt nhỏ hơn hoặc bằng now), cũ nhất trước. */
    List<UUID> findExpiredPendingIds(UUID tenantId, Instant now, int limit);
}
