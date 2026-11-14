package lms.api.circulation.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BorrowingView(UUID id, UUID userId, String status, LocalDate borrowDate, LocalDate dueDate,
                            int renewCount, List<Item> items) {

    public record Item(UUID bookCopyId, LocalDate returnDate) {
    }
}
