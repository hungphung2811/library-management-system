package lms.sales.ordering.domain;

public enum OrderStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
    READY_FOR_PICKUP,
    SHIPPING,
    COMPLETED,
    CANCELLED,
    /** Đơn POS thanh toán QR đang chờ cổng báo nhận tiền (ADR-0020); hàng đang được giữ (reserved). */
    AWAITING_PAYMENT
}
