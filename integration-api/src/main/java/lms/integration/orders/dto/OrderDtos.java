package lms.integration.orders.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DTO v1 của nhóm orders (allowlist riêng). KHÔNG có thông tin liên hệ của khách/người nhận (tên, điện thoại, địa chỉ,
 * kể cả dạng che), customerUserId, ghi chú của khách hay của nhân viên, lý do hủy, tenantId, mã đơn đổi trả. Đơn
 * ở dạng chỉ đọc. Tiền là số nguyên đồng.
 */
public final class OrderDtos {

    private OrderDtos() {
    }

    /** Trạng thái đơn hợp lệ cho bộ lọc {@code status} (khớp OrderStatus của sales-service). */
    public static final Set<String> ORDER_STATUSES = Set.of("PENDING_CONFIRMATION", "CONFIRMED", "READY_FOR_PICKUP",
            "SHIPPING", "COMPLETED", "CANCELLED", "AWAITING_PAYMENT");

    public record OrderLineView(int lineNo, UUID titleId, String titleName, String isbn, int quantity, long unitPrice,
                                long lineDiscount, long allocatedOrderDiscount, long amount, long vatAmount,
                                long lineTotal) {
    }

    public record OrderView(UUID id, String orderNo, String channel, String status, String paymentStatus,
                            String paymentMethod, String fulfilment, String currency, int vatRateBps,
                            boolean pricesIncludeVat, long subtotal, long discountTotal, long vatTotal,
                            long shippingFee, long grandTotal, Instant createdAt, Instant confirmedAt, Instant paidAt,
                            Instant completedAt, Instant cancelledAt, long version, List<OrderLineView> lines) {
    }
}
