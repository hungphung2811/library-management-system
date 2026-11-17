package lms.sales.ordering.infrastructure.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.application.OrderResponse;

/**
 * Đơn trả về cho BFF/FE. Nhân viên: liên hệ (khách vãng lai hoặc người nhận) luôn ở dạng che một phần; xem đầy đủ
 * qua getContact (khóa riêng). Khách dùng {@link ShopOrderView}. Tiền
 * là số nguyên đồng. Đơn POS không có địa chỉ nên {@code contact.address} là null.
 */
public record OrderView(UUID id, String orderNo, String channel, String status, String paymentStatus,
                        String paymentMethod, String fulfilment, UUID customerUserId, Contact contact,
                        int vatRateBps, boolean pricesIncludeVat, String currency, long subtotal, long discountTotal,
                        long vatTotal, long shippingFee, long grandTotal, Instant createdAt, Instant confirmedAt,
                        Instant paidAt, Instant completedAt, Instant cancelledAt, String cancelReason,
                        UUID exchangeOfReturnId, long version, List<OrderResponse.Line> lines) {

    public record Contact(String name, String phone, String address, Instant piiAnonymizedAt) {
        @Override
        public String toString() {
            return "Contact[***]";
        }
    }

    static OrderView from(OrderResponse o) {
        String name = o.walkInNameMasked() != null ? o.walkInNameMasked() : o.recipientNameMasked();
        String phone = o.walkInPhoneMasked() != null ? o.walkInPhoneMasked() : o.recipientPhoneMasked();
        return build(o, new Contact(name, phone, o.recipientAddressMasked(), o.piiAnonymizedAt()));
    }

    private static OrderView build(OrderResponse o, Contact contact) {
        return new OrderView(o.id(), o.orderNo(), o.channel().name(), o.status().name(), o.paymentStatus().name(),
                o.paymentMethod().name(), o.fulfilment().name(), o.customerUserId(), contact, o.vatRateBps(),
                o.pricesIncludeVat(), o.currency(), o.subtotal(), o.discountTotal(), o.vatTotal(), o.shippingFee(),
                o.grandTotal(), o.createdAt(), o.confirmedAt(), o.paidAt(), o.completedAt(), o.cancelledAt(),
                o.cancelReason(), o.exchangeOfReturnId(), o.version(), o.lines());
    }
}
