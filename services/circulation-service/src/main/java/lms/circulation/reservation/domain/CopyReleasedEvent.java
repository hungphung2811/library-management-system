package lms.circulation.reservation.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** copyId: bản sao đang được giữ (nếu có); consumer chính là catalog-service (đổi trạng thái bản sao). */
public record CopyReleasedEvent(UUID eventId, UUID copyId, UUID titleId, Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return copyId.toString();
    }
}
