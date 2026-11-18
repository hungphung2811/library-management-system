package lms.sales.ordering.application;

import java.time.Instant;
import java.util.Optional;
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

    /**
     * Hỏi cổng xem lệnh đã được thanh toán chưa (cổng không có webhook tới được máy này, như MoMo sandbox). Mặc định
     * rỗng: cổng đẩy kết quả bằng webhook (SIMULATED) không cần hỏi. Lỗi mạng/cổng thì ném {@link SalesException}.
     */
    default Optional<GatewayPayment> queryPayment(String reference) {
        return Optional.empty();
    }

    /**
     * Như {@link #queryPayment(String)} nhưng cho cổng không tra được theo mã lệnh mà phải dò sổ giao dịch (SePay): cần
     * thêm số tiền kỳ vọng và mốc {@code notBefore} (thời điểm tạo lệnh) để không khớp nhầm giao dịch cũ hoặc sai tiền.
     * Mặc định gọi bản theo mã tham chiếu.
     */
    default Optional<GatewayPayment> queryPayment(String reference, long amount, Instant notBefore) {
        return queryPayment(reference);
    }

    /** Kết quả truy vấn: {@code paid} khi cổng báo thành công, kèm mã giao dịch và số tiền (đồng) của cổng. */
    record GatewayPayment(boolean paid, String providerTxnId, long amount) {
    }

    record ChargeRequest(String reference, Money amount, Instant expiresAt) {
    }

    record QrCharge(String qrPayload) {
    }
}
