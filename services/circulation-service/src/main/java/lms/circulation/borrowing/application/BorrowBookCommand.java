package lms.circulation.borrowing.application;

import java.util.List;
import java.util.UUID;

public record BorrowBookCommand(UUID userId, List<UUID> bookCopyIds) {
}
