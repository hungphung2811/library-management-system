package lms.circulation.reservation.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Độc giả đã nhận bản sao được giữ (lập phiếu mượn). Catalog bỏ qua: bản sao đã được đánh dấu LOANED khi mượn. */
public record ReservationFulfilledEvent(UUID eventId, UUID reservationId, UUID readerId, UUID titleId, UUID copyId,
                                        Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return reservationId.toString();
    }
}
