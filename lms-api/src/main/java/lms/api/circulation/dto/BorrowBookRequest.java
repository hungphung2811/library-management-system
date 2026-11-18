package lms.api.circulation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** items (tùy chọn): ghi đè tình trạng lúc mượn từng cuốn; mặc định tự điền từ bản sao. */
public record BorrowBookRequest(@NotNull UUID userId, @NotEmpty List<@NotNull UUID> bookCopyIds,
                                @Valid List<@NotNull ItemOverride> items) {

    public BorrowBookRequest(UUID userId, List<UUID> bookCopyIds) {
        this(userId, bookCopyIds, null);
    }

    public record ItemOverride(@NotNull UUID bookCopyId,
                               @Pattern(regexp = "NEW|GOOD|FAIR|WORN") String conditionGrade,
                               @Size(max = 500) String conditionNote) {
    }
}
