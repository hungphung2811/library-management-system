package lms.circulation.fine.domain;

import lms.common.kernel.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FinePaidEvent(UUID eventId, UUID fineId, UUID readerId, BigDecimal amount, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String aggregateId() {
        return fineId.toString();
    }
}
