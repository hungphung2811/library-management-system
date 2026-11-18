package lms.sales.ordering.application;

import java.util.UUID;
import lms.sales.ordering.domain.OrderChannel;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Khách xem/hủy đơn của chính mình. {@code customerUserId} luôn lấy từ token (CurrentUser) ở web; mọi truy vấn có
 * điều kiện sở hữu, đơn của người khác trả 404 ORDER_NOT_FOUND (không lộ đơn tồn tại).
 */
public class ShopOrderUseCases {

    private final SalesOrderRepository orders;
    private final OrderWorkflowUseCases workflow;
    /** Hủy đơn chờ thanh toán trực tuyến (ADR-0029); null thì đơn AWAITING_PAYMENT không hủy được (409). */
    private final ShopPaymentUseCases payments;

    public ShopOrderUseCases(SalesOrderRepository orders, OrderWorkflowUseCases workflow) {
        this(orders, workflow, null);
    }

    public ShopOrderUseCases(SalesOrderRepository orders, OrderWorkflowUseCases workflow,
                             ShopPaymentUseCases payments) {
        this.orders = orders;
        this.workflow = workflow;
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public PageResult<ShopOrderResponse> myOrders(UUID tenantId, UUID customerUserId, int page, int size) {
        requireCustomer(customerUserId);
        var criteria = new SalesOrderRepository.Criteria(null, null, OrderChannel.ONLINE, null, null, null,
                customerUserId);
        return orders.search(tenantId, criteria, page, size).map(ShopOrderResponse::from);
    }

    /**
     * Số đơn ONLINE của chính khách: {@code total} (mọi trạng thái, kể cả đã hủy) và {@code pending} (đang chờ xác nhận).
     * Chỉ đếm, không đọc dòng đơn; {@code total} lấy từ trang 1 dòng của cùng truy vấn danh sách nên luôn khớp {@code myOrders}.
     */
    @Transactional(readOnly = true)
    public OrderCounts myOrderCounts(UUID tenantId, UUID customerUserId) {
        requireCustomer(customerUserId);
        var criteria = new SalesOrderRepository.Criteria(null, null, OrderChannel.ONLINE, null, null, null,
                customerUserId);
        long total = orders.search(tenantId, criteria, 0, 1).total();
        return new OrderCounts(total, orders.countPendingOnline(tenantId, customerUserId));
    }

    public record OrderCounts(long total, long pending) {
    }

    @Transactional(readOnly = true)
    public ShopOrderResponse myOrder(UUID tenantId, UUID customerUserId, UUID orderId) {
        return ShopOrderResponse.from(own(tenantId, customerUserId, orderId));
    }

    /**
     * Khách hủy đơn của mình. KHÔNG transactional ở mức này (đơn chờ thanh toán có lời gọi cổng): AWAITING_PAYMENT đi
     * luồng hủy lệnh ({@link ShopPaymentUseCases#cancelAwaiting}); còn lại một transaction trong
     * {@link OrderWorkflowUseCases#cancelOwnOrder}: chỉ hủy được khi PENDING_CONFIRMATION và UNPAID (đơn đã thanh toán
     * thì 409, khách liên hệ cửa hàng), hoàn tồn giữ (RELEASE) trong cùng transaction.
     */
    public ShopOrderResponse cancelMyOrder(UUID tenantId, UUID customerUserId, UUID orderId, String reason) {
        SalesOrder o = own(tenantId, customerUserId, orderId);
        String why = reason == null || reason.isBlank() ? "Khách hủy đơn" : reason;
        if (o.status() == OrderStatus.AWAITING_PAYMENT && payments != null) {
            return payments.cancelAwaiting(tenantId, customerUserId, orderId, why);
        }
        return workflow.cancelOwnOrder(tenantId, customerUserId, orderId, why);
    }

    private SalesOrder own(UUID tenantId, UUID customerUserId, UUID orderId) {
        requireCustomer(customerUserId);
        return orders.findByIdAndCustomer(tenantId, orderId, customerUserId)
                .orElseThrow(() -> new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));
    }

    private static void requireCustomer(UUID customerUserId) {
        if (customerUserId == null) {
            throw SalesException.invalidRequest("Cần đăng nhập bằng tài khoản khách");
        }
    }
}
