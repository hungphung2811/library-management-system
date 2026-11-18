package lms.circulation.borrowing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lms.common.kernel.DomainEvent;

/**
 * Phiếu mượn sắp đến hạn trả (topic borrowing.events, ADR-0030 D10). Không chứa email hay tên người, chỉ id: support-service
 * tự tra người nhận. daysBefore là số ngày còn lại tới hạn (0 = hạn là hôm nay). Consumer: support (thông báo LOAN_REMINDER).
 */
public record LoanDueSoon(UUID eventId, UUID borrowingId, UUID readerId, LocalDate dueDate, int daysBefore,
                          int itemCount, Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return borrowingId.toString();
    }
}
