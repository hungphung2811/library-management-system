package lms.sales.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.shared.domain.Money;

/**
 * Dữ liệu phiếu in. Không chứa dữ liệu cá nhân của khách. Phí ship tách khỏi phần chịu thuế:
 * taxableAmount = Σ amount các dòng (không gồm phí ship); grandTotal = Σ lineTotal + shippingFee.
 */
public record ReceiptResponse(UUID orderId, String orderNo, Instant issuedAt, String currency, int vatRateBps,
                              boolean pricesIncludeVat, List<Line> lines, long subtotal, long lineDiscountTotal,
                              long orderDiscountTotal, long discountTotal, long taxableAmount, long vatTotal,
                              long shippingFee, long grandTotal, PaymentMethod paymentMethod) {

    public record Line(int lineNo, String titleName, String isbn, int quantity, long unitPrice, long discount,
                       long amount, long vatAmount, long lineTotal) {
    }

    public static ReceiptResponse from(SalesOrder o) {
        long lineDisc = o.lines().stream().mapToLong(l -> l.lineDiscount().amount()).sum();
        long orderDisc = o.lines().stream().mapToLong(l -> l.allocatedOrderDiscount().amount()).sum();
        long taxable = o.lines().stream().mapToLong(l -> l.amount().amount()).sum();
        return new ReceiptResponse(o.id(), o.orderNo(), o.completedAt() != null ? o.completedAt() : o.createdAt(),
                Money.CURRENCY, o.vatRateBps(), o.pricesIncludeVat(),
                o.lines().stream().map(l -> new Line(l.lineNo(), l.titleName(), l.isbn(), l.quantity(),
                        l.unitPrice().amount(), l.lineDiscount().amount() + l.allocatedOrderDiscount().amount(),
                        l.amount().amount(), l.vatAmount().amount(), l.lineTotal().amount())).toList(),
                o.subtotal().amount(), lineDisc, orderDisc, o.discountTotal().amount(), taxable,
                o.vatTotal().amount(), o.shippingFee().amount(), o.grandTotal().amount(), o.paymentMethod());
    }
}
