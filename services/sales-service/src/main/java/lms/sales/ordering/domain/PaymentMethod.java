package lms.sales.ordering.domain;

public enum PaymentMethod {
    CASH,
    BANK_TRANSFER,
    CARD,
    COD,
    /** Quét mã QR chuyển khoản (ADR-0020): chỉ POS; đơn chờ thanh toán đến khi cổng báo đã nhận tiền. */
    QR,
    /** Ví MoMo qua cổng sandbox của MoMo (quét mã bằng app MoMo test); cùng luồng chờ cổng báo như QR. */
    MOMO,
    /** Chuyển khoản QR qua SePay Test mode (ADR-0028): cổng dò sổ giao dịch khi FE thăm dò; cùng luồng chờ cổng báo như QR. */
    SEPAY;

    /** Thu qua cổng thanh toán ngoài (QR, MoMo, SePay): đơn chỉ PAID khi cổng xác nhận, không đánh dấu thu tay. */
    public boolean isGateway() {
        return this == QR || this == MOMO || this == SEPAY;
    }

    /**
     * POS nhận tiền mặt, chuyển khoản, thẻ (đã thu xong ngoài hệ thống) và QR (thu qua cổng thanh toán, đơn chỉ
     * thành PAID khi cổng xác nhận).
     */
    public boolean allowedForPos() {
        return this != COD;
    }
}
