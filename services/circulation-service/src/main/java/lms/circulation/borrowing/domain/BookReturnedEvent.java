package lms.circulation.borrowing.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Consumer: Audit. condition: GOOD hoặc DAMAGED. Phí phạt đã được lập đồng bộ ở context fine; catalog đã đổi trạng thái bản sao. */
public record BookReturnedEvent(UUID eventId, UUID borrowingId, UUID userId, UUID bookCopyId,
                                LocalDate returnDate, long overdueDays, Instant occurredAt, String condition)
        implements DomainEvent {

    @Override
    public String aggregateId() {
        return borrowingId.toString();
    }
}
