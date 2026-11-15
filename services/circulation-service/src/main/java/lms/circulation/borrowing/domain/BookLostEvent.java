package lms.circulation.borrowing.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BookLostEvent(UUID eventId, UUID borrowingId, UUID userId, UUID bookCopyId, LocalDate lostDate,
                            Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return borrowingId.toString();
    }
}
