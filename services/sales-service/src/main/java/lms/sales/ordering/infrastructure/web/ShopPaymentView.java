package lms.sales.ordering.infrastructure.web;

import java.time.Instant;
import java.util.UUID;
import lms.sales.ordering.application.PaymentIntentResponse;
import lms.sales.ordering.application.ShopPaymentResponse;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.shared.domain.Money;

/**
 * Trạng thái thanh toán của đơn online trả về cho KHÁCH (allowlist, ADR-0029 mục 3.1). KHÔNG có: tenantId, id của lệnh,
 * providerTxnId, transactionRef, tenderedAmount, changeDue, createdAt của lệnh, version. {@code intent} null với đơn không
 * qua cổng. {@code qrPayload} chỉ có khi lệnh PENDING (trạng thái khác trả null, tránh khách quét mã đã hết hạn);
 * {@code reference} luôn trả (khách cần mã này khi khiếu nại với cửa hàng). {@code intent.status} là trạng thái hiệu
 * lực (PENDING đã quá hạn báo EXPIRED). Thêm trường mới phải sửa test allowlist ({@code ShopPaymentViewFieldsTest}).
 */
public record ShopPaymentView(UUID orderId, String orderNo, String orderStatus, String paymentStatus,
                              String paymentMethod, Intent intent) {

    public record Intent(String status, String provider, String qrPayload, long amount, String currency,
                         String reference, Instant expiresAt, Instant paidAt) {
    }

    static ShopPaymentView from(ShopPaymentResponse r) {
        return new ShopPaymentView(r.orderId(), r.orderNo(), r.orderStatus().name(), r.paymentStatus().name(),
                r.paymentMethod().name(), intent(r.intent()));
    }

    private static Intent intent(PaymentIntentResponse i) {
        if (i == null) {
            return null;
        }
        return new Intent(i.status().name(), i.provider(),
                i.status() == PaymentIntentStatus.PENDING ? i.qrPayload() : null, i.amount(), Money.CURRENCY,
                i.reference(), i.expiresAt(), i.paidAt());
    }
}
