package lms.circulation.borrowing.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Phiếu mượn đủ điều kiện nhắc ở lượt quét. openItemCount: số cuốn chưa trả; remindersSent: số lần đã nhắc cùng loại
 * cho đúng hạn trả hiện tại (0 nếu chưa nhắc lần nào).
 */
public record LoanReminderCandidate(UUID borrowingId, UUID readerId, LocalDate dueDate, int openItemCount,
                                    int remindersSent) {
}
