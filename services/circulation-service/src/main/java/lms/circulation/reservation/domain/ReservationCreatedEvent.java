package lms.circulation.reservation.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Consumer dự kiến: Audit, Notification. */
public record ReservationCreatedEvent(UUID eventId, UUID reservationId, UUID readerId, UUID titleId,
                                      Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return reservationId.toString();
    }
}
