package lms.sales.ordering.application;

import java.util.List;
import java.util.UUID;
import lms.sales.ordering.domain.Refund;
import lms.sales.ordering.domain.SalesReturn;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.springframework.transaction.annotation.Transactional;

/** Đọc phiếu trả (theo id, theo đơn). */
public class ReturnQueryUseCases {

    private final SalesReturnRepository returns;
    private final RefundRepository refunds;
    private final SalesOrderRepository orders;

    public ReturnQueryUseCases(SalesReturnRepository returns, RefundRepository refunds, SalesOrderRepository orders) {
        this.returns = returns;
        this.refunds = refunds;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public ReturnResponse get(UUID tenantId, UUID returnId) {
        SalesReturn r = returns.findById(tenantId, returnId)
                .orElseThrow(() -> new SalesException(ErrorCode.RETURN_NOT_FOUND, "Không có phiếu trả"));
        return respond(tenantId, r, refunds.findByOrderId(tenantId, r.orderId()));
    }

    @Transactional(readOnly = true)
    public List<ReturnResponse> listByOrder(UUID tenantId, UUID orderId) {
        orders.findById(tenantId, orderId)
                .orElseThrow(() -> new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));
        List<Refund> orderRefunds = refunds.findByOrderId(tenantId, orderId);
        return returns.findByOrderId(tenantId, orderId).stream().map(r -> respond(tenantId, r, orderRefunds))
                .toList();
    }

    private static ReturnResponse respond(UUID tenantId, SalesReturn r, List<Refund> orderRefunds) {
        UUID refundId = orderRefunds.stream().filter(f -> r.id().equals(f.returnId())).map(Refund::id).findFirst()
                .orElse(null);
        return ReturnResponse.from(r, refundId);
    }
}
