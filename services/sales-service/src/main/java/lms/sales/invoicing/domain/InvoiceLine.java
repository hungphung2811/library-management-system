package lms.sales.invoicing.domain;

import java.util.UUID;
import lms.sales.shared.domain.Money;

/**
 * Dòng hóa đơn (snapshot). {@code vatRateBps} null là dòng không chịu thuế (phí ship, ADR-0010 S3) và khi đó tiền thuế
 * bằng 0. Bất biến: thành tiền = tiền trước thuế + tiền thuế; số lượng dương. {@code orderLineId} null cho dòng phí ship.
 */
public record InvoiceLine(int lineNo, UUID orderLineId, String itemName, String isbn, String unit, int quantity,
                          Money unitPrice, Money discount, Money amountBeforeVat, Integer vatRateBps,
                          Money vatAmount, Money lineTotal) {

    public static final String BOOK_UNIT = "cuốn";
    public static final String SHIPPING_NAME = "Phí vận chuyển";
    public static final String SHIPPING_UNIT = "lần";

    public InvoiceLine {
        if (lineNo <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("Dòng hóa đơn cần số thứ tự và số lượng dương");
        }
        if (itemName == null || itemName.isBlank() || unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("Dòng hóa đơn cần tên hàng và đơn vị tính");
        }
        if (vatRateBps != null && (vatRateBps < 0 || vatRateBps > 10_000)) {
            throw new IllegalArgumentException("Thuế suất ngoài khoảng 0 đến 10000 basis point");
        }
        if (vatRateBps == null && !vatAmount.isZero()) {
            throw new IllegalArgumentException("Dòng không chịu thuế không có tiền thuế");
        }
        if (amountBeforeVat.plus(vatAmount).amount() != lineTotal.amount()) {
            throw new IllegalArgumentException("Thành tiền phải bằng tiền trước thuế cộng tiền thuế");
        }
    }

    /** Dòng phí vận chuyển: không chịu thuế, một lần. */
    public static InvoiceLine shipping(int lineNo, Money fee) {
        return new InvoiceLine(lineNo, null, SHIPPING_NAME, null, SHIPPING_UNIT, 1, fee, Money.ZERO, fee, null,
                Money.ZERO, fee);
    }

    /** Dòng được đánh số lại (dùng khi sao chép dòng sang hóa đơn thay thế). */
    public InvoiceLine withLineNo(int newLineNo) {
        return new InvoiceLine(newLineNo, orderLineId, itemName, isbn, unit, quantity, unitPrice, discount,
                amountBeforeVat, vatRateBps, vatAmount, lineTotal);
    }
}
