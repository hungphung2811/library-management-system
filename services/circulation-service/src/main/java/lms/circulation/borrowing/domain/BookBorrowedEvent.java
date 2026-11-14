package lms.circulation.borrowing.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Consumer: Inventory (đánh dấu bản sao đang được mượn), Audit. */
public record BookBorrowedEvent(UUID eventId, UUID borrowingId, UUID userId, List<UUID> bookCopyIds,
                                LocalDate dueDate, Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return borrowingId.toString();
    }
}
