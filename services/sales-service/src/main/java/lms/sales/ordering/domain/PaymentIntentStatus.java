package lms.sales.ordering.domain;

/** Trạng thái lệnh thanh toán: PENDING chuyển một chiều sang PAID, EXPIRED hoặc CANCELLED (đều là trạng thái cuối). */
public enum PaymentIntentStatus {
    PENDING,
    PAID,
    EXPIRED,
    CANCELLED
}
