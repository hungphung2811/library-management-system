package lms.circulation.fine.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Dữ liệu để lập phạt khi đóng một lượt mượn. Do context borrowing gọi (borrowing biết số ngày trễ và tình trạng sách);
 * fine không biết gì về borrowing nên không có vòng phụ thuộc. Giá gốc lấy qua cổng {@link PriceSource}, trừ khi
 * coverPrice được truyền sẵn (test, nơi đã biết giá bìa).
 * damageLevelCode, damageNote, evidenceUrls chỉ dùng cho RETURNED_DAMAGED (mã bậc) và LOST (ghi chú, ảnh).
 */
public record AssessFineCommand(UUID readerId, UUID borrowingId, UUID bookCopyId, BigDecimal coverPrice,
                                long overdueDays, Outcome outcome, String damageLevelCode, String damageNote,
                                List<String> evidenceUrls) {

    public AssessFineCommand(UUID readerId, UUID borrowingId, UUID bookCopyId, BigDecimal coverPrice,
                             long overdueDays, Outcome outcome) {
        this(readerId, borrowingId, bookCopyId, coverPrice, overdueDays, outcome, null, null, null);
    }

    public enum Outcome {
        RETURNED_GOOD, RETURNED_DAMAGED, LOST
    }
}
