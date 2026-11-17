package lms.sales.ordering.infrastructure.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.application.ShopOrderResponse;

/**
 * Đơn trả về cho KHÁCH (allowlist, chống lộ trường, customer-api-data-authorization A4). Khác {@link OrderView}
 * của nhân viên: KHÔNG có customerUserId, version, tenant, ISBN, giảm giá từng dòng, lý do hủy (nhân viên có thể ghi
 * nội dung nội bộ), id dòng, người thao tác, ghi chú nội bộ. Thêm trường mới phải sửa test allowlist
 * ({@code ShopOrderViewFieldsTest}). Người nhận là dữ liệu của chính khách nên hiển thị đầy đủ.
 */
public record ShopOrderView(UUID id, String orderNo, String status, String paymentStatus, String paymentMethod,
                            String fulfilment, Recipient recipient, String currency, long subtotal,
                            long discountTotal, long vatTotal, long shippingFee, long grandTotal, Instant createdAt,
                            Instant confirmedAt, Instant paidAt, Instant completedAt, Instant cancelledAt,
                            List<Line> lines) {

    public record Recipient(String name, String phone, String address) {
        @Override
        public String toString() {
            return "Recipient[***]";
        }
    }

    public record Line(UUID titleId, String titleName, int quantity, long unitPrice, long lineTotal) {
    }

    static ShopOrderView from(ShopOrderResponse r) {
        var o = r.order();
        var rc = r.recipient();
        return new ShopOrderView(o.id(), o.orderNo(), o.status().name(), o.paymentStatus().name(),
                o.paymentMethod().name(), o.fulfilment().name(),
                rc == null ? null : new Recipient(rc.name(), rc.phone(), rc.address()), o.currency(), o.subtotal(),
                o.discountTotal(), o.vatTotal(), o.shippingFee(), o.grandTotal(), o.createdAt(), o.confirmedAt(),
                o.paidAt(), o.completedAt(), o.cancelledAt(),
                o.lines().stream().map(l -> new Line(l.titleId(), l.titleName(), l.quantity(), l.unitPrice(),
                        l.lineTotal())).toList());
    }
}
