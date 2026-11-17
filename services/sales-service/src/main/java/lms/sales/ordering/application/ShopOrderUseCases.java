package lms.sales.ordering.application;

import java.util.UUID;
import lms.sales.ordering.domain.OrderChannel;
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

    public ShopOrderUseCases(SalesOrderRepository orders, OrderWorkflowUseCases workflow) {
        this.orders = orders;
        this.workflow = workflow;
    }

    @Transactional(readOnly = true)
    public PageResult<ShopOrderResponse> myOrders(UUID tenantId, UUID customerUserId, int page, int size) {
        requireCustomer(customerUserId);
        var criteria = new SalesOrderRepository.Criteria(null, null, OrderChannel.ONLINE, null, null, null,
                customerUserId);
        return orders.search(tenantId, criteria, page, size).map(ShopOrderResponse::from);
    }

    @Transactional(readOnly = true)
    public ShopOrderResponse myOrder(UUID tenantId, UUID customerUserId, UUID orderId) {
        return ShopOrderResponse.from(own(tenantId, customerUserId, orderId));
    }

    /** Khách chỉ hủy được khi còn PENDING_CONFIRMATION; hoàn tồn giữ (RELEASE) trong cùng transaction. */
    @Transactional
    public ShopOrderResponse cancelMyOrder(UUID tenantId, UUID customerUserId, UUID orderId, String reason) {
        SalesOrder o = own(tenantId, customerUserId, orderId);
        OrderResponse r = workflow.cancelLoaded(tenantId, customerUserId, o,
                reason == null || reason.isBlank() ? "Khách hủy đơn" : reason, true);
        return new ShopOrderResponse(r, ShopOrderResponse.from(o).recipient());
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
