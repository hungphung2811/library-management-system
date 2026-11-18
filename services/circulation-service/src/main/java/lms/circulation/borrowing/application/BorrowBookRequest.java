package lms.circulation.borrowing.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lms.circulation.borrowing.domain.ConditionGrade;
import java.util.List;
import java.util.UUID;

/**
 * items là tùy chọn: ghi đè tình trạng lúc mượn của từng cuốn khi thực tế khác dữ liệu của bản sao (mặc định tự điền
 * từ conditionGrade/conditionNote của bản sao ở catalog). Mỗi bookCopyId trong items phải nằm trong bookCopyIds.
 */
public record BorrowBookRequest(
        @NotNull UUID userId,
        @NotEmpty List<@NotNull UUID> bookCopyIds,
        @Valid List<@NotNull ItemOverride> items) {

    public BorrowBookRequest(UUID userId, List<UUID> bookCopyIds) {
        this(userId, bookCopyIds, null);
    }

    public record ItemOverride(@NotNull UUID bookCopyId, ConditionGrade conditionGrade,
                               @Size(max = 500) String conditionNote) {
    }
}
