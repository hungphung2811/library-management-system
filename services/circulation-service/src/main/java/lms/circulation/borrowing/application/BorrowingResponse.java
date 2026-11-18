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

    /**
     * condition: GOOD, DAMAGED, LOST; null khi còn đang mượn. conditionAtBorrow: tình trạng bản sao lúc lập phiếu; null =
     * lượt mượn cũ không có dữ liệu lúc mượn; khác null nhưng grade null = bản sao chưa ghi nhận tình trạng.
     */
    public record Item(UUID bookCopyId, LocalDate returnDate, String condition, ConditionAtBorrow conditionAtBorrow) {

        public Item(UUID bookCopyId, LocalDate returnDate, String condition) {
            this(bookCopyId, returnDate, condition, null);
        }

        public Item(UUID bookCopyId, LocalDate returnDate) {
            this(bookCopyId, returnDate, null, null);
        }
    }

    /** grade: NEW, GOOD, FAIR, WORN hoặc null (chưa ghi nhận); note tối đa 500 ký tự. Bất biến sau khi lập phiếu. */
    public record ConditionAtBorrow(String grade, String note) {
    }
}
