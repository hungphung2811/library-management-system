package lms.api.circulation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record BorrowBookRequest(@NotNull UUID userId, @NotEmpty List<@NotNull UUID> bookCopyIds) {
}
