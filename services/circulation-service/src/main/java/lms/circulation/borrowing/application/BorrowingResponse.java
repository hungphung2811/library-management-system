package lms.circulation.borrowing.application;

import lms.circulation.fine.application.FineResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** fines: các khoản phạt vừa được lập bởi thao tác trả/báo mất này (rỗng với thao tác khác). */
public record BorrowingResponse(
        UUID id,
        UUID userId,
        String status,
        LocalDate borrowDate,
        LocalDate dueDate,
        int renewCount,
        List<Item> items,
        List<FineResponse> fines) {

    public BorrowingResponse(UUID id, UUID userId, String status, LocalDate borrowDate, LocalDate dueDate,
                             int renewCount, List<Item> items) {
        this(id, userId, status, borrowDate, dueDate, renewCount, items, List.of());
    }

    /** condition: GOOD, DAMAGED, LOST; null khi còn đang mượn. */
    public record Item(UUID bookCopyId, LocalDate returnDate, String condition) {

        public Item(UUID bookCopyId, LocalDate returnDate) {
            this(bookCopyId, returnDate, null);
        }
    }
}
