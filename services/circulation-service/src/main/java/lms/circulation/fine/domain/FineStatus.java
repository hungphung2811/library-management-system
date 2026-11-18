package lms.circulation.fine.domain;

/**
 * UNPAID chưa thu; PAID đã thu; WAIVED miễn toàn phần (không còn nợ); REDUCED giảm một phần, còn thu phần
 * payableAmount thấp hơn amount. Chỉ UNPAID và REDUCED còn phải thu.
 */
public enum FineStatus {
    UNPAID, PAID, WAIVED, REDUCED;

    /** Còn phải thu (tính vào tổng nợ, thu qua POST /payments). */
    public boolean collectible() {
        return this == UNPAID || this == REDUCED;
    }
}
