package lms.sales.ordering.application;

import java.util.UUID;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentStatus;

/**
 * Trạng thái thanh toán của một đơn online cho CHÍNH khách (ADR-0029 mục 3.1). {@code intent} null khi đơn không qua
 * cổng. Đây là kết quả tầng application, đầy đủ trường; tầng web lọc lại bằng allowlist ({@code ShopPaymentView}).
 */
public record ShopPaymentResponse(UUID orderId, String orderNo, OrderStatus orderStatus, PaymentStatus paymentStatus,
                                  PaymentMethod paymentMethod, PaymentIntentResponse intent) {
}
