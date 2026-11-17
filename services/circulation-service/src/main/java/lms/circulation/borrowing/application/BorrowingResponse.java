package lms.circulation.borrowing.application;

import lms.circulation.fine.application.FineResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * fines: các khoản phạt vừa được lập bởi thao tác trả/báo mất này (rỗng với thao tác khác).
 * tenantId: tenant sở hữu phiếu (ADR-0008); null ở phản hồi của thao tác ghi trên phiếu vừa lập (chưa đọc lại từ DB).
 */
public record BorrowingResponse(
        UUID id,
        UUID userId,
        String status,
        LocalDate borrowDate,
        LocalDate dueDate,
        int renewCount,
        List<Item> items,
        List<FineResponse> fines,
        UUID tenantId) {

    public BorrowingResponse(UUID id, UUID userId, String status, LocalDate borrowDate, LocalDate dueDate,
                             int renewCount, List<Item> items) {
        this(id, userId, status, borrowDate, dueDate, renewCount, items, List.of(), null);
    }

    public BorrowingResponse(UUID id, UUID userId, String status, LocalDate borrowDate, LocalDate dueDate,
                             int renewCount, List<Item> items, List<FineResponse> fines) {
        this(id, userId, status, borrowDate, dueDate, renewCount, items, fines, null);
    }

    /** condition: GOOD, DAMAGED, LOST; null khi còn đang mượn. */
    public record Item(UUID bookCopyId, LocalDate returnDate, String condition) {

        public Item(UUID bookCopyId, LocalDate returnDate) {
            this(bookCopyId, returnDate, null);
        }
    }
}
