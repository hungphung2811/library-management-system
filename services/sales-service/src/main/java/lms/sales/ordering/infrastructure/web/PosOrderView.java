package lms.sales.ordering.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lms.sales.ordering.application.PaymentIntentResponse;
import lms.sales.ordering.application.PosOrderResult;

/**
 * Kết quả tạo đơn POS: các trường của {@link OrderView} (giữ nguyên hợp đồng cũ, trải phẳng) cộng khối
 * {@code paymentIntent} (chỉ khác null với đơn thanh toán QR, ADR-0020).
 */
public record PosOrderView(@JsonUnwrapped OrderView order, PaymentIntentResponse paymentIntent) {

    static PosOrderView from(PosOrderResult result) {
        return new PosOrderView(OrderView.from(result.order()), result.paymentIntent());
    }
}
