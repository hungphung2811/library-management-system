package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.ConditionGrade;
import java.util.List;
import java.util.UUID;

/** overrides: ghi đè tình trạng lúc mượn từng cuốn (null hoặc rỗng = dùng dữ liệu của bản sao). */
public record BorrowBookCommand(UUID userId, List<UUID> bookCopyIds, List<ConditionOverride> overrides) {

    public BorrowBookCommand(UUID userId, List<UUID> bookCopyIds) {
        this(userId, bookCopyIds, null);
    }

    public record ConditionOverride(UUID bookCopyId, ConditionGrade grade, String note) {
    }
}
