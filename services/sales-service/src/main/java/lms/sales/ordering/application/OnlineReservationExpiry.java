package lms.sales.ordering.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lms.sales.ordering.domain.SalesOrder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hết hạn giữ hàng của MỘT đơn online (docs/security/ORDER_ABUSE_AUDIT.md, A1), một transaction của sales_db: khóa dòng
 * đơn bằng SKIP LOCKED, kiểm lại điều kiện sau khi giữ khóa, rồi hủy đơn, nhả tồn đang giữ (RELEASE) và ghi audit cùng
 * transaction nên không bao giờ nhả kho hai lần hay nhả mà đơn còn sống.
 *
 * <p>Tranh chấp với nhân viên xác nhận/hủy cùng lúc: đơn đã xác nhận (hạn bị xóa) hoặc đã hủy thì không còn thỏa
 * điều kiện hết hạn nên trả false; xác nhận đang giữ khóa dòng thì bị bỏ qua (SKIP LOCKED) và lượt quét sau xử lý lại.
 * Không gọi dịch vụ ngoài trong transaction (đơn online hiện không đi qua cổng thanh toán).
 */
public class OnlineReservationExpiry {

    static final String EXPIRE_REASON = "Hết hạn giữ hàng";

    private final SalesOrderRepository orders;
    private final OrderWorkflowUseCases workflow;
    private final Clock clock;

    public OnlineReservationExpiry(SalesOrderRepository orders, OrderWorkflowUseCases workflow, Clock clock) {
        this.orders = orders;
        this.workflow = workflow;
        this.clock = clock;
    }

    /** True khi đơn đã được hủy và nhả hàng; false khi không còn cần (đã xử lý, chưa tới hạn) hoặc đang bị khóa. */
    @Transactional
    public boolean expireOne(UUID tenantId, UUID orderId) {
        Optional<SalesOrder> locked = orders.findByIdSkipLocked(tenantId, orderId);
        if (locked.isEmpty() || !locked.get().isReservationExpiredAt(clock.instant())) {
            return false;
        }
        workflow.cancelLoaded(tenantId, null, locked.get(), EXPIRE_REASON, false);
        return true;
    }
}
