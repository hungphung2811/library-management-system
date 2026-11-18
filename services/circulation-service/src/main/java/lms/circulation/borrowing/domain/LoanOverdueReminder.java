package lms.circulation.borrowing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lms.common.kernel.DomainEvent;

/**
 * Nhắc phiếu mượn đã quá hạn trả (topic borrowing.events, ADR-0030 D10). reminderNo là lần nhắc thứ mấy (1..maxReminders)
 * của (phiếu, hạn trả); không chứa email hay tên người. Consumer: support (thông báo LOAN_REMINDER).
 */
public record LoanOverdueReminder(UUID eventId, UUID borrowingId, UUID readerId, LocalDate dueDate, long daysOverdue,
                                  int reminderNo, int maxReminders, int itemCount,
                                  Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return borrowingId.toString();
    }
}
