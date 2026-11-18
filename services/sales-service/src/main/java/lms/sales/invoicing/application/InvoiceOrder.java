package lms.sales.invoicing.application;

import java.util.List;
import java.util.UUID;
import lms.sales.shared.domain.Money;

/**
 * Ảnh chụp đơn hàng mà hóa đơn cần (POJO của context invoicing: invoicing không biết kiểu của ordering, ordering cài
 * {@link InvoiceOrderSourcePort}). Trạng thái là chuỗi tên enum của đơn. Tiền theo snapshot trên đơn: thuế suất một
 * mức cho cả đơn, mỗi dòng đã có tiền thuế và thành tiền.
 */
public record InvoiceOrder(UUID orderId, String orderNo, String channel, String status, String paymentStatus,
                           String paymentMethod, UUID customerUserId, boolean pricesIncludeVat, int vatRateBps,
                           Money shippingFee, Money grandTotal, boolean hasReturns, List<Line> lines) {

    /** Dòng đơn: {@code discount} là giảm giá dòng cộng phần giảm giá đơn phân bổ về dòng. */
    public record Line(UUID orderLineId, String titleName, String isbn, int quantity, Money unitPrice,
                       Money discount, Money vatAmount, Money lineTotal) {
    }

    public InvoiceOrder {
        lines = List.copyOf(lines);
    }

    /** Đơn đã hoàn tất và đã thu đủ tiền (chưa hoàn tiền một phần/toàn phần). */
    public boolean isCompletedAndPaid() {
        return "COMPLETED".equals(status) && "PAID".equals(paymentStatus);
    }

    public boolean isCancelled() {
        return "CANCELLED".equals(status);
    }
}
