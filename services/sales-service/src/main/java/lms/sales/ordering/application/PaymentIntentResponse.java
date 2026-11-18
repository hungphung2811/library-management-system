package lms.sales.ordering.application;

import java.time.Instant;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.domain.PaymentIntentStatus;

/**
 * Lệnh thanh toán trả cho FE. {@code status} là trạng thái hiệu lực: lệnh PENDING đã quá hạn báo EXPIRED ngay (job dọn
 * sẽ ghi vào DB và trả tồn sau). Tiền là số đồng. {@code provider}: MANUAL (thu tay, không có qrPayload) hoặc cổng QR
 * (SIMULATED); tenderedAmount/changeDue chỉ có với tiền mặt đã xác nhận, transactionRef với lệnh đã xác nhận có mã.
 */
public record PaymentIntentResponse(UUID id, UUID orderId, String reference, long amount,
                                    PaymentIntentStatus status, String provider, String qrPayload,
                                    Instant expiresAt, Instant paidAt, Instant createdAt,
                                    Long tenderedAmount, Long changeDue, String transactionRef) {

    public static PaymentIntentResponse from(PaymentIntent i, Instant now) {
        PaymentIntentStatus effective = i.isExpiredAt(now) ? PaymentIntentStatus.EXPIRED : i.status();
        return new PaymentIntentResponse(i.id(), i.orderId(), i.reference(), i.amount().amount(), effective,
                i.provider(), i.qrPayload(), i.expiresAt(), i.paidAt(), i.createdAt(), i.tenderedAmount(),
                i.changeDue(), i.transactionRef());
    }
}
