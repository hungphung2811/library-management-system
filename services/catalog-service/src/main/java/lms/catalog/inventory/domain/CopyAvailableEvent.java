package lms.catalog.inventory.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Một bản sao vừa sẵn sàng cho mượn (nhập kho hoặc được nhả). Consumer: circulation (giữ cho người chờ, QĐ9). */
public record CopyAvailableEvent(UUID eventId, UUID copyId, UUID titleId, Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return copyId.toString();
    }
}
