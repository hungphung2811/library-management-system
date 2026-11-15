package lms.circulation.fine.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dữ liệu để lập phạt khi đóng một lượt mượn. Do context borrowing gọi (borrowing biết số ngày trễ, tình trạng sách
 * và lấy giá bìa từ catalog); fine không biết gì về borrowing nên không có vòng phụ thuộc.
 */
public record AssessFineCommand(UUID readerId, UUID borrowingId, UUID bookCopyId, BigDecimal coverPrice,
                                long overdueDays, Outcome outcome) {

    public enum Outcome {
        RETURNED_GOOD, RETURNED_DAMAGED, LOST
    }
}
