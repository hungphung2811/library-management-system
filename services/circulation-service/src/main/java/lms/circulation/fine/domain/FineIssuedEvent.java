package lms.circulation.fine.domain;

import lms.common.kernel.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Consumer tiềm năng: notification (báo độc giả có phí phạt), audit. */
public record FineIssuedEvent(UUID eventId, UUID fineId, UUID readerId, UUID borrowingId, UUID bookCopyId,
                              String reason, BigDecimal amount, Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return fineId.toString();
    }
}
