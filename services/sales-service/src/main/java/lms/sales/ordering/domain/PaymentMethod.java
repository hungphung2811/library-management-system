package lms.sales.ordering.domain;

public enum PaymentMethod {
    CASH,
    BANK_TRANSFER,
    CARD,
    COD;

    /** POS chỉ nhận tiền mặt, chuyển khoản, thẻ (đã thu xong ngoài hệ thống). */
    public boolean allowedForPos() {
        return this != COD;
    }
}
