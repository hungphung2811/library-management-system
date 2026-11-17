package lms.sales.ordering.application;

import java.time.Instant;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Refund;
import lms.sales.ordering.domain.RefundSource;
import lms.sales.ordering.domain.RefundStatus;

public record RefundResponse(UUID id, UUID orderId, RefundSource source, UUID returnId, long amount,
                             RefundStatus status, PaymentMethod method, String reference, Instant createdAt,
                             UUID refundedBy, Instant refundedAt, long version) {

    public static RefundResponse from(Refund r) {
        return new RefundResponse(r.id(), r.orderId(), r.source(), r.returnId(), r.amount().amount(), r.status(),
                r.method(), r.reference(), r.createdAt(), r.refundedBy(), r.refundedAt(), r.version());
    }
}
