package lms.api.circulation.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** tenantId: tenant của dòng (ADR-0008). fines: các khoản phạt vừa lập bởi thao tác trả/báo mất (rỗng với thao tác khác). */
public record BorrowingView(UUID id, UUID userId, String status, LocalDate borrowDate, LocalDate dueDate,
                            int renewCount, List<Item> items, List<FineView> fines, UUID tenantId) {

    public BorrowingView(UUID id, UUID userId, String status, LocalDate borrowDate, LocalDate dueDate,
                         int renewCount, List<Item> items) {
        this(id, userId, status, borrowDate, dueDate, renewCount, items, List.of(), null);
    }

    /** condition: GOOD, DAMAGED, LOST; null khi còn đang mượn. */
    public record Item(UUID bookCopyId, LocalDate returnDate, String condition) {

        public Item(UUID bookCopyId, LocalDate returnDate) {
            this(bookCopyId, returnDate, null);
        }
    }
}
