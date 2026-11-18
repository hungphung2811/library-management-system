package lms.sales.ordering.domain;

public enum PaymentMethod {
    CASH,
    BANK_TRANSFER,
    CARD,
    COD,
    /** Quét mã QR chuyển khoản (ADR-0020): chỉ POS; đơn chờ thanh toán đến khi cổng báo đã nhận tiền. */
    QR;

    /**
     * POS nhận tiền mặt, chuyển khoản, thẻ (đã thu xong ngoài hệ thống) và QR (thu qua cổng thanh toán, đơn chỉ
     * thành PAID khi cổng xác nhận).
     */
    public boolean allowedForPos() {
        return this != COD;
    }
}
