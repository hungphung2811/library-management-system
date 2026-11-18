package lms.sales.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Refund;
import lms.sales.ordering.domain.RefundStatus;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.shift.application.ShiftAttributionPort;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hoàn tiền: danh sách và đánh dấu đã hoàn (PENDING sang REFUNDED, ghi người, thời điểm, phương thức). Đánh dấu hoàn
 * cập nhật trục thanh toán của đơn (PARTIALLY_REFUNDED hoặc REFUNDED theo tổng đã hoàn) trong cùng transaction.
 */
public class RefundUseCases {

    private final RefundRepository refunds;
    private final SalesOrderRepository orders;
    private final SalesAuditPort audit;
    private final Clock clock;

    public RefundUseCases(RefundRepository refunds, SalesOrderRepository orders, SalesAuditPort audit, Clock clock) {
        this.refunds = refunds;
        this.orders = orders;
        this.audit = audit;
        this.clock = clock;
    }

    /** Ca thu ngân (ADR-0030): hoàn tiền vào ca của người đánh dấu. Mặc định không gắn ca (test cũ). */
    private ShiftAttributionPort shifts = ShiftAttributionPort.NONE;

    public RefundUseCases withShiftAttribution(ShiftAttributionPort port) {
        this.shifts = port == null ? ShiftAttributionPort.NONE : port;
        return this;
    }

    @Transactional(readOnly = true)
    public PageResult<RefundResponse> search(UUID tenantId, RefundStatus status, int page, int size) {
        return refunds.search(tenantId, status, page, size).map(RefundResponse::from);
    }

    @Transactional
    public RefundResponse markRefunded(UUID tenantId, UUID actor, UUID refundId, PaymentMethod method,
                                       String reference, Long expectedVersion) {
        Refund refund = refunds.findById(tenantId, refundId)
                .orElseThrow(() -> new SalesException(ErrorCode.REFUND_NOT_FOUND, "Không có khoản hoàn"));
        if (expectedVersion != null && expectedVersion != refund.version()) {
            throw new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Khoản hoàn đã bị thay đổi bởi thao tác khác");
        }
        Instant now = clock.instant();
        // ADR-0030: ca của người ghi nhận hoàn; hoàn TIỀN MẶT khi cờ tenant bật mà chưa mở ca thì 409 SHIFT_NOT_OPEN
        UUID shiftId = shifts.resolve(tenantId, actor, method == PaymentMethod.CASH);
        refund.markRefunded(method, reference, actor, now, shiftId);
        Refund saved = refunds.save(refund);

        SalesOrder order = orders.findById(tenantId, saved.orderId())
                .orElseThrow(() -> new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));
        List<Refund> all = refunds.findByOrderId(tenantId, order.id());
        long refunded = all.stream().filter(r -> r.status() == RefundStatus.REFUNDED)
                .mapToLong(r -> r.amount().amount()).sum();
        order.applyRefundedTotal(Money.of(refunded));
        orders.save(order);

        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.REFUND_COMPLETED, "Refund",
                saved.id(), actor, now, Map.of("orderId", order.id().toString(), "amount", saved.amount().amount(),
                "method", method.name(), "paymentStatus", order.paymentStatus().name())));
        return RefundResponse.from(saved);
    }
}
