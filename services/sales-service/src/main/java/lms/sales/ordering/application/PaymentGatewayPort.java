package lms.sales.ordering.application;

import java.time.Instant;
import lms.sales.shared.domain.Money;

/**
 * Cổng thanh toán QR (ADR-0020). Phần tạo mã thanh toán nằm sau port này để thay cổng thật (VNPay, PayOS...) chỉ cần
 * đổi adapter; phần nhận kết quả (webhook, xác thực chữ ký) là của từng cổng ở tầng web.
 */
public interface PaymentGatewayPort {

    /** Tên cổng lưu ở {@code payment_intents.provider}. */
    String provider();

    /** Tạo dữ liệu mã QR cho một lệnh thanh toán; {@code reference} là nội dung chuyển khoản dùng để đối soát. */
    QrCharge createQr(ChargeRequest request);

    record ChargeRequest(String reference, Money amount, Instant expiresAt) {
    }

    record QrCharge(String qrPayload) {
    }
}
