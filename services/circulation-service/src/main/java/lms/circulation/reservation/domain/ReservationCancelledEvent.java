package lms.circulation.reservation.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** copyId: bản sao đang được giữ (nếu có); consumer chính là catalog-service (đổi trạng thái bản sao). */
public record ReservationCancelledEvent(UUID eventId, UUID reservationId, UUID readerId, UUID titleId, UUID copyId, Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return reservationId.toString();
    }
}
