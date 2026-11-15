package lms.circulation.borrowing.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Một bản sao sách vật lý (BookCopy, thuộc catalog) trong phiếu mượn. Đang mượn khi returnDate == null;
 * đóng lượt mượn thì có ngày đóng và tình trạng (GOOD, DAMAGED, LOST).
 */
public record BorrowingItem(UUID bookCopyId, LocalDate returnDate, ItemCondition condition) {

    public BorrowingItem {
        if (bookCopyId == null) throw new IllegalArgumentException("bookCopyId không được null");
        if ((returnDate == null) != (condition == null)) {
            throw new IllegalArgumentException("Ngày đóng và tình trạng phải cùng có hoặc cùng không");
        }
    }

    public static BorrowingItem borrowed(UUID bookCopyId) {
        return new BorrowingItem(bookCopyId, null, null);
    }

    /** Đã đóng lượt mượn (trả hoặc báo mất). */
    public boolean isReturned() {
        return returnDate != null;
    }

    public BorrowingItem closedOn(LocalDate date, ItemCondition condition) {
        return new BorrowingItem(bookCopyId, date, condition);
    }
}
