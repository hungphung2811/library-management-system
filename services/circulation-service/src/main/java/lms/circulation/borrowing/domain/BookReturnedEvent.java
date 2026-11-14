package lms.circulation.borrowing.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Consumer: Inventory (bản sao available trở lại), Fine (tính phạt nếu overdueDays &gt; 0). */
public record BookReturnedEvent(UUID eventId, UUID borrowingId, UUID userId, UUID bookCopyId,
                                LocalDate returnDate, long overdueDays, Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return borrowingId.toString();
    }
}
