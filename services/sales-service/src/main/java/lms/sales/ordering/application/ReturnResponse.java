package lms.sales.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.domain.Disposition;
import lms.sales.ordering.domain.SalesReturn;

/** Phiếu trả. {@code refundId} là khoản hoàn sinh cùng phiếu (null nếu tiền hoàn bằng 0). */
public record ReturnResponse(UUID id, String returnNo, UUID orderId, String reason, boolean late, String lateReason,
                             int daysOverdue, long refundTotal, UUID refundId, UUID createdBy, Instant createdAt,
                             List<Line> lines) {

    public record Line(UUID id, UUID orderLineId, UUID titleId, int quantity, Disposition disposition,
                       long refundAmount) {
    }

    public static ReturnResponse from(SalesReturn r, UUID refundId) {
        return new ReturnResponse(r.id(), r.returnNo(), r.orderId(), r.reason(), r.late(), r.lateReason(),
                r.daysOverdue(), r.refundTotal().amount(), refundId, r.createdBy(), r.createdAt(),
                r.lines().stream().map(l -> new Line(l.id(), l.orderLineId(), l.titleId(), l.quantity(),
                        l.disposition(), l.refundAmount().amount())).toList());
    }
}
