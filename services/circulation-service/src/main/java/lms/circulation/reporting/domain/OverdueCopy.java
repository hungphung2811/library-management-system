package lms.circulation.reporting.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Một bản sao chưa trả của một phiếu quá hạn (một dòng của báo cáo mượn quá hạn). */
public record OverdueCopy(UUID borrowingId, UUID userId, UUID bookCopyId, LocalDate borrowDate, LocalDate dueDate) {

    /** Số ngày quá hạn tính tới {@code today} (&gt;= 1 với phiếu quá hạn đúng định nghĩa dueDate &lt; today). */
    public long daysOverdue(LocalDate today) {
        return ChronoUnit.DAYS.between(dueDate, today);
    }
}
