package lms.circulation.borrowing.application;

import java.time.LocalDate;
import java.util.UUID;
import lms.circulation.borrowing.domain.LoanReminderKind;

/** Một dòng sổ loan_reminders cần ghi: lần nhắc thứ seq của (phiếu, loại, hạn trả). */
public record LoanReminderRecord(UUID borrowingId, LoanReminderKind kind, LocalDate dueDate, int seq, LocalDate sentOn,
                                 UUID eventId) {
}
