package lms.sales.ordering.application;

import java.time.Instant;
import java.util.UUID;
import lms.sales.ordering.domain.OrderChannel;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.ordering.domain.Recipient;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xem/tìm đơn, liên hệ đầy đủ (khóa riêng ở web), dữ liệu phiếu in. Danh sách/chi tiết che liên hệ khách. Đơn chuyển
 * khoản quá hạn: lọc {@code paymentStatus=UNPAID} + {@code to} (tạo trước thời điểm) rồi hủy tay.
 */
public class OrderQueryUseCases {

    public record SearchQuery(OrderStatus status, PaymentStatus paymentStatus, OrderChannel channel, Instant from,
                              Instant to, String keyword, int page, int size) {
    }

    /**
     * Liên hệ đầy đủ: khách vãng lai (POS) hoặc người nhận (online); đơn POS đã ẩn danh trả trường null (không lỗi).
     */
    public record Contact(String name, String phone, String address, Instant piiAnonymizedAt) {
        @Override
        public String toString() {
            return "Contact[***]";
        }
    }

    private final SalesOrderRepository orders;

    public OrderQueryUseCases(SalesOrderRepository orders) {
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID tenantId, UUID orderId) {
        return OrderResponse.from(load(tenantId, orderId));
    }

    @Transactional(readOnly = true)
    public Contact contact(UUID tenantId, UUID orderId) {
        SalesOrder o = load(tenantId, orderId);
        Recipient r = o.recipient();
        if (r != null) {
            return new Contact(r.name(), r.phone(), r.address(), o.piiAnonymizedAt());
        }
        return new Contact(o.walkInName(), o.walkInPhone(), null, o.piiAnonymizedAt());
    }

    @Transactional(readOnly = true)
    public ReceiptResponse receipt(UUID tenantId, UUID orderId) {
        var order = load(tenantId, orderId);
        if (order.channel() == lms.sales.ordering.domain.OrderChannel.POS
                && order.paymentStatus() == lms.sales.ordering.domain.PaymentStatus.UNPAID) {
            // Đơn POS chờ thanh toán hoặc đã hủy chưa thu: chưa phải phiếu thu (ADR-0021)
            throw new lms.sales.shared.domain.SalesException(lms.sales.shared.domain.ErrorCode.ORDER_NOT_PAID,
                    "Đơn chưa thu tiền, chưa có phiếu thu");
        }
        return ReceiptResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PageResult<OrderResponse> search(UUID tenantId, SearchQuery q) {
        var criteria = new SalesOrderRepository.Criteria(q.status(), q.paymentStatus(), q.channel(), q.from(),
                q.to(), q.keyword());
        return orders.search(tenantId, criteria, q.page(), q.size()).map(OrderResponse::from);
    }

    private SalesOrder load(UUID tenantId, UUID id) {
        return orders.findById(tenantId, id)
                .orElseThrow(() -> new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));
    }
}
