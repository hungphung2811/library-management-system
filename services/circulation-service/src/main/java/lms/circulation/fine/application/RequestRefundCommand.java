package lms.circulation.fine.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Do context borrowing gọi khi thủ thư ghi nhận tìm lại sách: borrowing biết lượt mượn đã báo mất và ngày báo mất
 * (lostOn); fine tìm khoản bồi thường mất tương ứng và kiểm hạn tìm lại theo chính sách đền bù.
 */
public record RequestRefundCommand(UUID borrowingId, UUID bookCopyId, LocalDate lostOn, String restoreStatus,
                                   String checkNote, String refundMethod, String referenceCode,
                                   UUID requestedBy) {
}
